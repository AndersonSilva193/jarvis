package dev.jarvis.pc;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A lixeira do PC: mover, restaurar e listar, sem nunca aceitar caminho do modelo.
 *
 * <p>Serviço, e não código dentro das ferramentas, pelo mesmo motivo do {@link FluxoEdicaoPc}: a
 * conversa com o agente — prazo, timeout, aviso tardio, PC offline — é a parte que se repete, e a
 * ferramenta fica com o que é dela, que é decidir o que dizer ao usuário.
 *
 * <p>As três ações só trocam dado estruturado: o pedido vai todo em {@code dados} e a resposta vem
 * em {@code dados}. Quem autoriza esse par nas duas camadas é {@link ComandoPc#LIXEIRA}.
 */
@Service
public class LixeiraPcService {

    /** Teto da espera pelo resultado, como no {@link FluxoEdicaoPc}. */
    private static final Duration ESPERA = Duration.ofSeconds(55);

    /**
     * Quantas páginas do catálogo o {@link #nomePorId} percorre antes de desistir.
     *
     * <p>Com {@link #TAMANHO_PAGINA} de 50 isso cobre 500 arquivos. Não é para ser generoso: é
     * para o pior caso ter fim. Sem teto, um catálogo grande ou um {@code temMais} sempre
     * verdadeiro prenderia o turno inteiro numa varredura.
     */
    private static final int MAX_PAGINAS = 10;
    private static final int TAMANHO_PAGINA = 50;

    private final PcService pc;
    private final JsonMapper json;

    public LixeiraPcService(PcService pc, JsonMapper json) {
        this.pc = pc;
        this.json = json;
    }

    public boolean online() {
        return pc.online();
    }

    /**
     * O nome catalogado deste ID, perguntado ao agente.
     *
     * <p>É o que permite montar a pergunta de aprovação <b>em Java, de dado real</b>: o modelo
     * manda um ID e nada mais, e o nome que o usuário lê no "Posso?" vem do catálogo do PC. Aceitar
     * o nome do modelo deixaria ele nomear um arquivo e apagar outro — o "sim" do usuário valendo
     * para uma coisa que ele não leu.
     *
     * <p>O catálogo não tem busca por ID (o {@code nome} do {@code listar_arquivos} é filtro por
     * substring), então a varredura é por página mesmo. Vazio quando o ID não está lá.
     */
    public Optional<String> nomePorId(String id, OuvinteProgresso contexto) throws Exception {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) return Optional.empty();
        for (int pagina = 1; pagina <= MAX_PAGINAS; pagina++) {
            var pedido = json.createObjectNode()
                    .put("pagina", pagina).put("tamanhoPagina", TAMANHO_PAGINA);
            ResultadoPc resultado = executar("listar_arquivos", pedido, contexto);
            if (!resultado.ok() || resultado.dados() == null) return Optional.empty();
            // "arquivos", não "itens": o catálogo e a lixeira têm chaves diferentes, e confundi-las
            // faz a varredura achar uma lista vazia e jurar que o arquivo não existe.
            for (JsonNode item : resultado.dados().path("arquivos"))
                if (id.equalsIgnoreCase(Json.texto(item.path("id"))))
                    return Optional.of(Json.texto(item.path("nome")));
            if (!resultado.dados().path("temMais").asBoolean(false)) return Optional.empty();
        }
        return Optional.empty();
    }

    /** Move para a lixeira pelo ID do catálogo. O agente guarda os bytes e o registro. */
    public ResultadoPc mover(String id, OuvinteProgresso contexto) throws Exception {
        return executar("mover_para_lixeira", json.createObjectNode().put("id", id), contexto);
    }

    /** Devolve o arquivo à pasta. O agente nunca sobrescreve: renomeia e avisa. */
    public ResultadoPc restaurar(String idLixeira, OuvinteProgresso contexto) throws Exception {
        return executar("restaurar_da_lixeira",
                json.createObjectNode().put("idLixeira", idLixeira), contexto);
    }

    /** O que está na lixeira, já em JSON, para o modelo apresentar as opções. */
    public ResultadoPc listar(JsonNode filtro, OuvinteProgresso contexto) throws Exception {
        var pedido = json.createObjectNode();
        if (filtro != null && filtro.isObject()) {
            for (String campo : new String[] { "nome", "formato" })
                if (filtro.path(campo).isString()) pedido.put(campo, Json.texto(filtro.path(campo)));
            for (String campo : new String[] { "pagina", "tamanhoPagina" })
                if (filtro.path(campo).isNumber()) pedido.put(campo, (int) Json.numero(filtro.path(campo)));
        }
        return executar("listar_lixeira", pedido, contexto);
    }

    /**
     * Enfileira e <b>espera</b> o resultado.
     *
     * <p>Esperar não é detalhe: a frase que volta ao usuário afirma o que foi feito ("Movi
     * nota.md para a lixeira"), e responder antes do PC confirmar deixaria o Jarvis prometendo o
     * que ainda pode falhar — arquivo aberto no Excel, disco sem move atômico, edição em voo.
     */
    private ResultadoPc executar(String acao, JsonNode dados, OuvinteProgresso contexto)
            throws Exception {
        if (!pc.online()) throw new IllegalStateException("Agente do PC offline.");
        ComandoPc comando = new ComandoPc(UUID.randomUUID().toString(), acao, "",
                Instant.now().toString(), dados == null ? json.createObjectNode() : dados.deepCopy());
        try {
            return pc.enfileirar(comando, contexto.canal(), contexto.usuarioAutenticado())
                    .get(ESPERA.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // O comando segue valendo no PC; o aviso tardio é o que fecha o laço com o usuário.
            pc.pedirAviso(comando.id());
            throw new IllegalStateException("O PC não respondeu a tempo; avisarei o resultado.");
        } catch (java.util.concurrent.ExecutionException e) {
            pc.cancelar(comando.id());
            throw new IllegalStateException("A conexão com o PC falhou.");
        } catch (InterruptedException e) {
            pc.pedirAviso(comando.id());
            Thread.currentThread().interrupt();
            throw e;
        }
    }
}
