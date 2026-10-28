package dev.jarvis.aprovacao;

import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.jarvis.custo.Origem;
import dev.jarvis.ia.ClaudeClient;
import dev.jarvis.ia.provedor.BlocoDeSistema;
import dev.jarvis.ia.provedor.InstrucoesDeSistema;
import dev.jarvis.ia.provedor.ListaDeFerramentas;
import dev.jarvis.ia.provedor.Mensagem;
import dev.jarvis.ia.provedor.OpcoesDoPedido;
import dev.jarvis.ia.provedor.PedidoAoModelo;
import dev.jarvis.ia.provedor.ProvedorAnthropic;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.ia.provedor.RespostaDoModelo;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entende a resposta do usuário a uma pergunta de aprovação, em linguagem natural.
 *
 * <p>Quem decide uma aprovação é sempre o servidor, nunca o modelo principal: ele não vê a
 * pendência nem a decisão, só o resultado dela. Este classificador é o braço do servidor para
 * isso — recebe a pergunta que o Jarvis fez e a mensagem que chegou depois, e devolve uma das
 * cinco saídas de {@link Decisao}. Quem consome é o {@code Assistente.responder}, antes de
 * qualquer ida ao Claude do turno.
 *
 * <p>São dois caminhos. O primeiro é de graça: texto normalizado (sem acento, minúsculo, sem
 * pontuação final) e comparado com duas listas amplas de "sim" e "não". Só mensagens curtas (até
 * {@link #MAX_PALAVRAS_DA_LISTA} palavras) decidem por ali — "pode" é sim, mas "pode, mas só a
 * pasta src" é outra conversa. O segundo caminho é uma chamada curta ao Claude: sem ferramentas,
 * {@link #MAX_TOKENS} tokens de resposta e {@link #PRAZO} de prazo, com um system mínimo montado
 * aqui. O {@code SistemaDoJarvis} ficaria de fora de propósito: personalidade e regras de canal
 * não ajudam a dizer "sim ou não", e o prefixo cacheado da conversa não serve a um prompt deste
 * tamanho.
 *
 * <p><b>Tudo que der errado vira {@link Decisao#DUVIDA}</b> — rede, prazo, JSON torto, valor
 * desconhecido. Em dúvida nunca se executa: o preço de um "não entendi, confirme" é uma pergunta
 * repetida; o de um falso "sim" é uma máquina desligada.
 */
@Component
public class ClassificadorDeResposta {

    /** O que o servidor entendeu da mensagem que veio depois da pergunta. */
    public enum Decisao {
        /** Aceita como está. */
        SIM,
        /** Recusa. */
        NAO,
        /** Aceita com mudança ("pode, mas só a pasta src"): a proposta de antes não vale mais. */
        AJUSTE,
        /** A mensagem não responde à pergunta; a pendência continua esperando. */
        OUTRO_ASSUNTO,
        /** Não dá para saber. Nunca executa. */
        DUVIDA
    }

    private static final Logger log = LoggerFactory.getLogger(ClassificadorDeResposta.class);

    /** Acima disso a frase tem ressalva, condição ou outro assunto — e aí quem lê é o modelo. */
    private static final int MAX_PALAVRAS_DA_LISTA = 4;
    private static final int MAX_TOKENS = 60;
    private static final Duration PRAZO = Duration.ofSeconds(5);

    private static final Set<String> SIM = Set.of("sim", "pode", "pode sim", "pode ir", "manda",
            "manda ver", "bora", "vai", "ok", "okay", "beleza", "blz", "fechado", "faz",
            "faz isso", "claro", "confirmo", "autorizo", "pode prosseguir", "pode executar",
            "segue", "isso");

    private static final Set<String> NAO = Set.of("nao", "n", "agora nao", "deixa",
            "deixa pra la", "deixa quieto", "espera", "para", "cancela", "cancelar",
            "nem pensar", "melhor nao", "negativo");

    private static final String SISTEMA = """
            Você classifica a resposta de uma pessoa a uma pergunta de sim ou não feita por um \
            assistente. Responda apenas com um JSON {"decisao":"sim|nao|ajuste|outro_assunto|duvida"}, \
            sem mais nada.

            sim = autoriza a ação como foi proposta.
            nao = recusa a ação.
            ajuste = aceita, mas com uma mudança no que foi proposto (ex.: "pode, mas só a pasta src").
            outro_assunto = a mensagem não responde à pergunta; fala de outra coisa.
            duvida = não dá para saber se é sim ou não.

            Na dúvida, responda duvida.""";

    private final ProvedorDeModelo modelo;

    /** Sobre o {@link ClaudeClient}, que é como os testes o montam; nulo é "sem modelo". */
    public ClassificadorDeResposta(ClaudeClient claude, JsonMapper json) {
        this(ProvedorAnthropic.sobre(claude, json));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ClassificadorDeResposta(ProvedorDeModelo modelo) {
        this.modelo = modelo;
    }

    /**
     * Classificador que só usa as listas, sem nunca chamar o modelo. É o que mantém de pé os
     * caminhos que montam o {@code Assistente} à mão (testes, construtores antigos), no mesmo
     * espírito de {@code BuscaWebServidor.desligada()}.
     */
    public static ClassificadorDeResposta semModelo() {
        return new ClassificadorDeResposta((ProvedorDeModelo) null);
    }

    /**
     * @param pergunta a pergunta que o Jarvis fez, montada em Java a partir de dado real
     * @param resposta a mensagem que o usuário mandou depois
     */
    public Decisao classificar(String pergunta, String resposta) {
        String recebido = normalizar(resposta);
        if (recebido.isEmpty()) return Decisao.DUVIDA;
        if (recebido.split(" ").length <= MAX_PALAVRAS_DA_LISTA) {
            if (SIM.contains(recebido)) return Decisao.SIM;
            if (NAO.contains(recebido)) return Decisao.NAO;
        }
        if (modelo == null) return Decisao.DUVIDA;
        return pelaModelo(pergunta, resposta);
    }

    private Decisao pelaModelo(String pergunta, String resposta) {
        try {
            String mensagem = "Pergunta do assistente: " + (pergunta == null ? "" : pergunta)
                    + "\nResposta da pessoa: " + (resposta == null ? "" : resposta);
            RespostaDoModelo raiz = modelo.responder(new PedidoAoModelo(
                    InstrucoesDeSistema.de(new BlocoDeSistema(SISTEMA, null)),
                    List.of(Mensagem.texto(Mensagem.Papel.USUARIO, mensagem)),
                    ListaDeFerramentas.vazia(),
                    OpcoesDoPedido.curta(Origem.CLASSIFICADOR, MAX_TOKENS, PRAZO)));
            return deTexto(String.join("", raiz.textos()));
        } catch (InterruptedException e) {
            // Quem interrompeu quer o turno encerrado, não uma decisão a mais.
            Thread.currentThread().interrupt();
            return Decisao.DUVIDA;
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Não consegui classificar a resposta da aprovação ({}); fica em dúvida.",
                    e.toString());
            return Decisao.DUVIDA;
        }
    }

    /** O JSON pode vir cercado de texto; o que importa é o valor de "decisao". */
    private Decisao deTexto(String texto) {
        var achado = java.util.regex.Pattern
                .compile("\"decisao\"\\s*:\\s*\"([a-z_]+)\"").matcher(texto.toLowerCase(Locale.ROOT));
        if (!achado.find()) return Decisao.DUVIDA;
        return switch (achado.group(1)) {
            case "sim" -> Decisao.SIM;
            case "nao" -> Decisao.NAO;
            case "ajuste" -> Decisao.AJUSTE;
            case "outro_assunto" -> Decisao.OUTRO_ASSUNTO;
            default -> Decisao.DUVIDA;
        };
    }

    /**
     * Sem acento, minúsculo, sem pontuação final e com os espaços encolhidos. Mora aqui e é usada
     * também pelo {@link Aprovacoes}: duas regras de normalização dariam duas leituras diferentes
     * da mesma mensagem.
     */
    static String normalizar(String texto) {
        if (texto == null) return "";
        String semAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return semAcentos.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[.!?]+$", "").replace(',', ' ')
                .replaceAll("\\s+", " ").trim();
    }
}
