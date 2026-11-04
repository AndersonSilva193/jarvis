package dev.jarvis.tarefas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;
import dev.jarvis.ia.Canal;
import dev.jarvis.util.ArquivoAtomico;
import dev.jarvis.util.Json;
import tools.jackson.databind.json.JsonMapper;

/** Troca atômica, backup e leitura defensiva; nenhum bloco de modelo é serializado. */
final class TarefasEmArquivo {
    private final Path arquivo;
    private final JsonMapper json;
    private long ultimaRevisao = -1;
    TarefasEmArquivo(Path arquivo, JsonMapper json) { this.arquivo = arquivo; this.json = json; }

    synchronized void salvar(long revisao, List<TarefaDeFundo.Retrato> tarefas) throws IOException {
        if (revisao < ultimaRevisao) return;
        var raiz = json.createObjectNode().put("versao", 1);
        var lista = raiz.putArray("tarefas");
        for (var t : tarefas) {
            var n = lista.addObject().put("id", t.id()).put("usuario", t.usuario())
                    .put("canal", t.canal()).put("chat", t.chat()).put("objetivo", t.objetivo())
                    .put("status", t.status().name()).put("resultado", t.resultado())
                    .put("criadaEm", t.criadaEm().toString()).put("atualizadaEm", t.atualizadaEm().toString())
                    .put("passos", t.passos()).put("tempoAtivoMillis", t.tempoAtivoMillis())
                    .put("custoUsd", t.custoUsd()).put("origem", t.origem().name())
                    .put("retomada", t.retomada()).put("planoAprovado", t.planoAprovado())
                    .put("esperaTipo", t.esperaTipo()).put("esperaAlvo", t.esperaAlvo());
            if (t.iniciadaEm() != null) n.put("iniciadaEm", t.iniciadaEm().toString());
            if (t.concluidaEm() != null) n.put("concluidaEm", t.concluidaEm().toString());
            if (t.retomarEm() != null) n.put("retomarEm", t.retomarEm().toString());
            if (t.esperaDesde() != null) n.put("esperaDesde", t.esperaDesde().toString());
            if (!t.pausa().isBlank()) n.put("pausa", t.pausa());
            var plano = n.putArray("plano"); t.plano().forEach(plano::add);
            var resumo = n.putArray("resumo"); t.resumo().forEach(resumo::add);
            var acoes = n.putArray("acoesPrevistas");
            var comandos = n.putObject("comandos"); t.comandos().forEach(comandos::put);
            for (var a : t.acoesPrevistas()) {
                var acao = acoes.addObject().put("chave", a.chave());
                if (a.alvo() != null) acao.put("alvo", a.alvo());
            }
        }
        ArquivoAtomico.gravar(arquivo, json.writeValueAsString(raiz));
        ultimaRevisao = revisao;
    }

    List<TarefaDeFundo> carregar() {
        for (Path candidato : List.of(arquivo, ArquivoAtomico.backupDe(arquivo))) {
            if (!Files.exists(candidato)) continue;
            try {
                var raiz = json.readTree(ArquivoAtomico.ler(candidato, 16_000_000));
                if (!raiz.path("tarefas").isArray()) throw new IOException("lista de tarefas ausente");
                List<TarefaDeFundo> tarefas = new ArrayList<>();
                for (var n : raiz.path("tarefas")) {
                    try {
                        String id = Json.texto(n.path("id")); long usuario = Json.numero(n.path("usuario"));
                        if (!id.matches("[a-f0-9]{8}") || usuario <= 0) throw new IllegalArgumentException("identidade inválida");
                        List<String> plano = new ArrayList<>(), resumo = new ArrayList<>();
                        n.path("plano").forEach(p -> plano.add(p.asText()));
                        n.path("resumo").forEach(p -> resumo.add(p.asText()));
                        List<TarefaDeFundo.AcaoPrevista> acoes = new ArrayList<>();
                        n.path("acoesPrevistas").forEach(a -> acoes.add(new TarefaDeFundo.AcaoPrevista(
                                Json.texto(a.path("chave")), Json.texto(a.path("alvo")))));
                        var t = new TarefaDeFundo(id, usuario, Canal.valueOf(Json.texto(n.path("canal")).toUpperCase(java.util.Locale.ROOT)),
                                Json.numero(n.path("chat")), Json.texto(n.path("objetivo")), plano, acoes,
                                TarefaDeFundo.Origem.valueOf(Json.texto(n.path("origem"))), instante(n, "criadaEm"));
                        t.status = TarefaDeFundo.Status.valueOf(Json.texto(n.path("status")));
                        t.resumo.addAll(resumo); t.resultado = Json.texto(n.path("resultado"));
                        t.iniciadaEm = instante(n, "iniciadaEm");
                        t.atualizadaEm = java.util.Objects.requireNonNullElse(instante(n, "atualizadaEm"), t.criadaEm);
                        t.concluidaEm = instante(n, "concluidaEm"); t.retomarEm = instante(n, "retomarEm");
                        t.esperaDesde = instante(n, "esperaDesde");
                        String pausa = Json.texto(n.path("pausa"));
                        t.pausa = List.of(TarefaDeFundo.PAUSA_DONO, TarefaDeFundo.PAUSA_SEM_RESPOSTA).contains(pausa) ? pausa : "";
                        t.passos = Math.max(0, n.path("passos").asInt()); t.tempoAtivoMillis = Math.max(0, n.path("tempoAtivoMillis").asLong());
                        t.custoUsd = Math.max(0, n.path("custoUsd").asDouble());
                        t.retomada = n.path("retomada").asBoolean(); t.planoAprovado = n.path("planoAprovado").asBoolean();
                        t.esperaTipo = Json.texto(n.path("esperaTipo")); t.esperaAlvo = Json.texto(n.path("esperaAlvo"));
                        if (n.path("comandos").isObject()) n.path("comandos").properties()
                                .forEach(e -> t.comandos.put(e.getKey(), e.getValue().asText()));
                        tarefas.add(t);
                    } catch (RuntimeException e) {
                        LoggerFactory.getLogger(getClass()).warn("Tarefa inválida descartada: {}", e.getMessage());
                    }
                }
                return tarefas;
            } catch (IOException | RuntimeException e) {
                LoggerFactory.getLogger(getClass()).warn("Tarefas ilegíveis; tentando backup: {}", e.getMessage());
            }
        }
        return List.of();
    }
    private static Instant instante(tools.jackson.databind.JsonNode n, String campo) {
        String s = Json.texto(n.path(campo)); return s.isBlank() ? null : Instant.parse(s);
    }
}
