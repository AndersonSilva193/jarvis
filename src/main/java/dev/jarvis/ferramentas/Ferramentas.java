package dev.jarvis.ferramentas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import dev.jarvis.tela.CardTela;
import dev.jarvis.tela.ComandoTela;
import dev.jarvis.tela.TelaEvento;
import dev.jarvis.util.Json;
import dev.jarvis.ia.Canal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Reúne todas as ferramentas (o Spring injeta a lista) e faz a ponte com o formato da API. */
@Component
public class Ferramentas {

    private static final Logger log = LoggerFactory.getLogger(Ferramentas.class);

    private final Map<String, Ferramenta> porNome = new LinkedHashMap<>();
    private final JsonMapper json;
    private final ApplicationEventPublisher eventos;

    /** Sem barramento de eventos: o que vai para a tela simplesmente não sai daqui. */
    public Ferramentas(List<Ferramenta> ferramentas, JsonMapper json) {
        this(ferramentas, json, evento -> { });
    }

    @Autowired
    public Ferramentas(List<Ferramenta> ferramentas, JsonMapper json,
            ApplicationEventPublisher eventos) {
        this.json = json;
        this.eventos = eventos;
        for (Ferramenta f : ferramentas) {
            porNome.put(f.nome(), f);
        }
        log.info("Ferramentas disponíveis: {}", porNome.keySet());
    }

    /** Lista no formato "tools" da Messages API. */
    public ArrayNode definicoes() {
        return definicoes(null);
    }

    /** Ferramentas oferecidas para o canal, mantendo a lista estável dentro do turno. */
    public ArrayNode definicoes(Canal canal) {
        ArrayNode lista = json.createArrayNode();
        for (Ferramenta f : porNome.values()) {
            if (canal == Canal.TELEGRAM && "ouvir_resposta".equals(f.nome())) continue;
            ObjectNode def = lista.addObject();
            def.put("name", f.nome());
            def.put("description", f.descricao());
            def.set("input_schema", f.esquemaEntrada());
        }
        return lista;
    }

    /** Recebe um bloco "tool_use" do modelo e devolve o bloco "tool_result" correspondente. */
    public ObjectNode executar(JsonNode blocoToolUse) {
        return executar(blocoToolUse, new dev.jarvis.conversa.OuvinteProgresso() { }, saida -> { });
    }

    public ObjectNode executar(JsonNode blocoToolUse, dev.jarvis.conversa.OuvinteProgresso progresso,
            java.util.function.Consumer<ResultadoFerramenta> receber) {
        String nome = Json.texto(blocoToolUse.path("name"));
        JsonNode entrada = blocoToolUse.path("input");

        ObjectNode resultado = json.createObjectNode();
        resultado.put("type", "tool_result");
        resultado.put("tool_use_id", Json.texto(blocoToolUse.path("id")));

        Ferramenta ferramenta = porNome.get(nome);
        if (ferramenta == null) {
            resultado.put("content", "Ferramenta desconhecida: " + nome);
            resultado.put("is_error", true);
            return resultado;
        }

        log.info("Executando ferramenta {}", nome);
        String descricao = ferramenta.progresso();
        if (descricao != null && !descricao.isBlank()) {
            dev.jarvis.conversa.OuvinteProgresso.avisar(() -> progresso.ferramenta(nome, descricao, null, null));
        }
        try {
            dev.jarvis.conversa.OuvinteProgresso contexto = new dev.jarvis.conversa.OuvinteProgresso() {
                @Override public String idPedido() { return progresso.idPedido(); }
                @Override public long usuarioAutenticado() { return progresso.usuarioAutenticado(); }
                @Override public String pedidoOriginal() { return progresso.pedidoOriginal(); }
                @Override public boolean memoriaEsquecida() { return progresso.memoriaEsquecida(); }
                @Override public void marcarMemoriaEsquecida() { progresso.marcarMemoriaEsquecida(); }
                @Override public void marcarMemoriaEsquecida(String id, long revisao) {
                    progresso.marcarMemoriaEsquecida(id, revisao);
                }
                @Override public String idMemoriaEsquecida() { return progresso.idMemoriaEsquecida(); }
                @Override public long revisaoMemoriaEsquecida() { return progresso.revisaoMemoriaEsquecida(); }
                @Override public boolean consumirContexto(int caracteres) { return progresso.consumirContexto(caracteres); }
                @Override public boolean evidenciaVerificada(String conteudo) { return progresso.evidenciaVerificada(conteudo); }
                @Override public String origemDaEvidencia(String conteudo) {
                    return progresso.origemDaEvidencia(conteudo);
                }
                @Override public String canal() { return progresso.canal(); }
                @Override public boolean pesquisaFeita() { return progresso.pesquisaFeita(); }
                @Override public String idFerramenta() { return Json.texto(blocoToolUse.path("id")); }
                @Override public void ferramenta(String nome, String descricao, Integer passo, Integer total) {
                    progresso.ferramenta(nome, descricao, passo, total);
                }
            };
            ResultadoFerramenta detalhado = ferramenta.executarDetalhado(entrada, contexto);
            // Ponto único: nenhuma ferramenta precisa lembrar de publicar, e o card chega à tela
            // já agora, enquanto o modelo ainda está escrevendo a resposta deste turno.
            publicarNaTela(detalhado);
            receber.accept(detalhado);
            String saida = detalhado.texto();
            resultado.put("content", saida == null || saida.isBlank() ? "(sem resultado)" : saida);
        } catch (Exception e) {
            log.warn("Ferramenta {} falhou: {}", nome, e.getMessage());
            resultado.put("content", "Erro ao executar " + nome + ": " + e.getMessage());
            resultado.put("is_error", true);
        }
        return resultado;
    }

    /** Uma HUD que caiu não pode derrubar o pedido, por isso cada aviso vai dentro do avisar(). */
    private void publicarNaTela(ResultadoFerramenta resultado) {
        for (CardTela card : resultado.cards()) {
            dev.jarvis.conversa.OuvinteProgresso.avisar(
                    () -> eventos.publishEvent(TelaEvento.deCard(card)));
        }
        for (ComandoTela comando : resultado.comandos()) {
            dev.jarvis.conversa.OuvinteProgresso.avisar(
                    () -> eventos.publishEvent(TelaEvento.deComando(comando)));
        }
    }
}
