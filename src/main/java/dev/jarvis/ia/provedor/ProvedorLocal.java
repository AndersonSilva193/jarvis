package dev.jarvis.ia.provedor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.custo.Origem;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.util.Json;
import dev.jarvis.util.ValidadorDeEsquema;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O {@link ProvedorDeModelo} do Ollama do PC, falado pelo comando {@code inferir} do agente.
 *
 * <p>Nada aqui abre conexão: o pedido vai para a fila do {@link PcService}, o agente o busca no long
 * poll e devolve o resultado no POST de sempre. É o mesmo caminho do {@code controlar_pc}, então o
 * PC continua sem porta aberta.
 *
 * <p><b>O modelo local erra formato, e erro de formato nunca vira execução.</b> Toda chamada de
 * ferramenta que volta é conferida contra a lista do pedido e o {@code esquemaEntrada} da
 * ferramenta ({@link ValidadorDeEsquema}). Uma torta — nome que não existe, argumento que não é
 * JSON, argumento fora do esquema — é contada como erro de formato no {@link MedidorDeCusto} e
 * volta ao próprio modelo como resultado de erro, numa volta de correção, sem chegar a quem chamou.
 * Persistindo, a chamada falha com {@code erro_de_formato} e o {@link ProvedorDeApoio} passa ao
 * Claude.
 *
 * <p><b>O prazo é por quem espera</b> ({@link #prazo}): o classificador tem alguém olhando para o
 * celular, a introdução tem alguém na frente da tela; anotador e briefing são trabalho de fundo.
 */
@Component
public class ProvedorLocal implements ProvedorDeModelo {

    private static final Logger log = LoggerFactory.getLogger(ProvedorLocal.class);

    /** A tela acabou de acender: dez segundos é o que dá para esperar antes do Claude assumir. */
    static final Duration PRAZO_COM_ALGUEM_ESPERANDO = Duration.ofSeconds(10);
    /** Voltas de correção depois de uma chamada torta. Cada uma é uma inferência inteira. */
    static final int CORRECOES = 1;

    public static final String TIMEOUT = "timeout";
    public static final String ERRO_DE_FORMATO = "erro_de_formato";
    public static final String FALHA_LOCAL = "falha_local";

    private final PcService pc;
    /** Pode ser nulo nos testes que só conferem a conversão. */
    private final MedidorDeCusto medidor;
    private final JarvisProperties props;
    private final JsonMapper json;
    private final ConversorOllama conversor;

    @Autowired
    public ProvedorLocal(PcService pc, MedidorDeCusto medidor, JarvisProperties props, JsonMapper json) {
        this.pc = pc;
        this.medidor = medidor;
        this.props = props;
        this.json = json;
        this.conversor = new ConversorOllama(json);
    }

    @Override
    public RespostaDoModelo responder(PedidoAoModelo pedido) throws java.io.IOException, InterruptedException {
        Duration prazo = prazo(pedido.opcoes());
        long limite = System.nanoTime() + prazo.toNanos();
        Origem origem = pedido.opcoes().origem();
        ObjectNode corpo = conversor.pedido(pedido, origem == Origem.CONVERSA);
        ArrayNode mensagens = (ArrayNode) corpo.get("messages");
        Map<String, JsonNode> esquemas = new HashMap<>();
        for (DefinicaoDeFerramenta definicao : pedido.ferramentas().definicoes()) {
            esquemas.put(definicao.nome(), definicao.esquema());
        }

        for (int volta = 0; ; volta++) {
            JsonNode dados = enviar(corpo, Duration.ofNanos(Math.max(0, limite - System.nanoTime())));
            RespostaDoModelo bruta = conversor.resposta(dados);
            if (medidor != null) {
                medidor.registrarLocal(origem, bruta.uso().entrada(), bruta.uso().saida());
            }
            Conferida conferida = conferir(bruta, esquemas);
            if (conferida.problemas().isEmpty()) {
                return conferida.resposta();
            }
            if (medidor != null) {
                conferida.problemas().values().forEach(p -> medidor.registrarErroDeFormato());
            }
            log.info("Local devolveu {} chamada(s) fora do formato (volta {})",
                    conferida.problemas().size(), volta + 1);
            if (volta >= CORRECOES || !conferida.ecoavel()) {
                throw new FalhaLocal(ERRO_DE_FORMATO, "o modelo local insistiu em chamada fora do formato");
            }
            mensagens.add(conversor.assistente(bruta));
            for (Bloco bloco : bruta.blocos()) {
                if (!(bloco instanceof Bloco.Chamada c)) continue;
                String problema = conferida.problemas().get(c.id());
                mensagens.add(conversor.resultado(c.nome(), problema != null
                        ? "Erro de formato: " + problema + ". Nada foi executado; corrija e repita."
                        : "Não executada: corrija as chamadas inválidas e repita."));
            }
        }
    }

    /**
     * Quanto o local tem, contado do pedido inteiro (as voltas de correção dividem o mesmo prazo).
     * Nunca passa de {@code IA_LOCAL_TIMEOUT_SEGUNDOS}.
     */
    Duration prazo(OpcoesDoPedido opcoes) {
        Duration teto = props.prazoDaIaLocal();
        Duration proprio = switch (opcoes.origem()) {
            case CLASSIFICADOR -> opcoes.prazo() == null ? teto : opcoes.prazo();
            case INICIALIZACAO -> PRAZO_COM_ALGUEM_ESPERANDO;
            default -> teto;
        };
        return proprio.compareTo(teto) < 0 ? proprio : teto;
    }

    /** Um {@code inferir} e a espera por ele; desistir cancela o comando para ele nem começar. */
    private JsonNode enviar(ObjectNode corpo, Duration prazo) throws FalhaLocal, InterruptedException {
        if (prazo.isZero() || prazo.isNegative()) {
            throw new FalhaLocal(TIMEOUT, "o prazo do modelo local acabou");
        }
        ComandoPc comando = new ComandoPc(UUID.randomUUID().toString(), "inferir", "",
                Instant.now().toString(), corpo);
        ResultadoPc resultado;
        try {
            CompletableFuture<ResultadoPc> futuro = pc.inferir(comando, prazo);
            resultado = futuro.get(prazo.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pc.cancelar(comando.id());
            throw new FalhaLocal(TIMEOUT, "o modelo local não respondeu em " + prazo.toSeconds() + " s");
        } catch (InterruptedException e) {
            pc.cancelar(comando.id());
            throw e;
        } catch (ExecutionException | RuntimeException e) {
            throw new FalhaLocal(FALHA_LOCAL, "falha ao falar com o agente: " + e.getClass().getSimpleName());
        }
        if (!resultado.ok()) {
            // O código vem do agente, mas só como rótulo: fora do formato vira o genérico.
            String codigo = resultado.dados() == null ? "" : Json.texto(resultado.dados().path("codigoErro"));
            throw new FalhaLocal(codigo.matches("[a-z_]{1,32}") ? codigo : FALHA_LOCAL,
                    "o PC recusou a inferência");
        }
        if (resultado.dados() == null || !resultado.dados().path("message").isObject()) {
            throw new FalhaLocal(FALHA_LOCAL, "resposta do agente sem message");
        }
        return resultado.dados();
    }

    /**
     * A resposta conferida.
     *
     * @param problemas por id de chamada, o que está errado nela; vazio é resposta usável
     * @param ecoavel   dá para devolver a resposta ao modelo: o agente recusa o pedido inteiro com
     *                  nome de ferramenta fora de {@code [A-Za-z0-9_-]}, então uma chamada com
     *                  nome assim nem tem volta de correção
     */
    private record Conferida(RespostaDoModelo resposta, Map<String, String> problemas, boolean ecoavel) { }

    private Conferida conferir(RespostaDoModelo bruta, Map<String, JsonNode> esquemas) {
        Map<String, String> problemas = new HashMap<>();
        List<Bloco> blocos = new ArrayList<>();
        boolean ecoavel = true;
        for (Bloco bloco : bruta.blocos()) {
            if (!(bloco instanceof Bloco.Chamada c)) {
                blocos.add(bloco);
                continue;
            }
            if (!ConversorOllama.NOME_DE_FERRAMENTA.matcher(c.nome()).matches()) ecoavel = false;
            JsonNode entrada = c.entrada();
            String problema = null;
            if (!esquemas.containsKey(c.nome())) {
                problema = "a ferramenta '" + (ecoavel ? c.nome() : "?") + "' não existe neste pedido";
            } else if (entrada != null && entrada.isString()) {
                try {
                    entrada = json.readTree(entrada.stringValue());
                } catch (JacksonException e) {
                    problema = "os argumentos não são JSON";
                }
            }
            if (problema == null && (entrada == null || !entrada.isObject())) {
                problema = "os argumentos têm de ser um objeto JSON";
            }
            if (problema == null) {
                problema = ValidadorDeEsquema.problema(entrada, esquemas.get(c.nome())).orElse(null);
            }
            if (problema != null) problemas.put(c.id(), problema);
            blocos.add(new Bloco.Chamada(c.id(), c.nome(), entrada));
        }
        return new Conferida(new RespostaDoModelo(blocos, bruta.motivo(), bruta.uso()), problemas, ecoavel);
    }
}
