package dev.jarvis.gatilhos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jarvis.lembretes.Recorrencia;
import dev.jarvis.lembretes.Repeticao;
import dev.jarvis.util.ArquivoAtomico;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * O {@code gatilhos.json} em {@code DIARIO_PASTA}, com a troca atômica e o {@code .bak} do
 * {@link ArquivoAtomico} — o desenho do {@code TarefasEmArquivo}: leitura defensiva, regra que não
 * se lê é descartada com WARN e as outras continuam valendo.
 *
 * <p>Além das regras guarda duas memórias: os pares do {@code SITES_ACOES} já migrados (para a regra
 * apagada pelo dono não voltar na partida seguinte) e os IDs da pasta {@code entrada} já vistos.
 */
final class GatilhosEmArquivo {

    private static final Logger log = LoggerFactory.getLogger(GatilhosEmArquivo.class);
    /** Quantos IDs da pasta entrada são lembrados; os mais antigos saem primeiro. */
    static final int MAX_VISTOS = 2_000;

    record Estado(List<RegraDeGatilho> regras, Set<String> migrados, boolean entradaInicializada,
            List<String> vistos) {
        static Estado vazio() {
            return new Estado(List.of(), Set.of(), false, List.of());
        }
    }

    private final Path arquivo;
    private final JsonMapper json;

    GatilhosEmArquivo(Path arquivo, JsonMapper json) {
        this.arquivo = arquivo;
        this.json = json;
    }

    synchronized void salvar(Estado estado) throws IOException {
        ObjectNode raiz = json.createObjectNode().put("versao", 1);
        var regras = raiz.putArray("regras");
        for (RegraDeGatilho r : estado.regras()) {
            ObjectNode n = regras.addObject().put("id", r.id()).put("usuario", r.usuario())
                    .put("fonte", r.fonte().chave()).put("instrucao", r.instrucao())
                    .put("evidencia", r.evidencia()).put("ativa", r.ativa())
                    .put("criadaEm", r.criadaEm().toString()).put("origem", r.origem())
                    .put("pedido", r.pedido());
            if (r.ultimoDisparo() != null) n.put("ultimoDisparo", r.ultimoDisparo().toString());
            if (r.proximo() != null) n.put("proximo", r.proximo().toString());
            ObjectNode f = n.putObject("filtro");
            Filtro filtro = r.filtro();
            if (filtro.site() != null) f.put("site", filtro.site());
            if (filtro.nome() != null) f.put("nome", filtro.nome());
            if (filtro.formato() != null) f.put("formato", filtro.formato());
            if (filtro.tipo() != null) f.put("tipo", filtro.tipo());
            if (filtro.quando() != null) {
                f.put("quando", filtro.quando().toString());
                f.put("recorrencia", filtro.repeticao().tipo().chave());
                if (!filtro.repeticao().diasDaSemana().isEmpty()) {
                    var dias = f.putArray("diasDaSemana");
                    filtro.repeticao().diasOrdenados().forEach(d -> dias.add(d.name()));
                }
                if (filtro.repeticao().diaDoMes() > 0) f.put("diaDoMes", filtro.repeticao().diaDoMes());
            }
        }
        var migrados = raiz.putArray("migrados");
        estado.migrados().forEach(migrados::add);
        ObjectNode entrada = raiz.putObject("entrada").put("inicializada", estado.entradaInicializada());
        var vistos = entrada.putArray("vistos");
        List<String> lista = estado.vistos();
        lista.subList(Math.max(0, lista.size() - MAX_VISTOS), lista.size()).forEach(vistos::add);
        ArquivoAtomico.gravar(arquivo, json.writeValueAsString(raiz));
    }

    Estado carregar() {
        for (Path candidato : List.of(arquivo, ArquivoAtomico.backupDe(arquivo))) {
            if (!Files.exists(candidato)) continue;
            try {
                JsonNode raiz = json.readTree(ArquivoAtomico.ler(candidato, 4_000_000));
                if (!raiz.path("regras").isArray()) throw new IOException("lista de regras ausente");
                List<RegraDeGatilho> regras = new ArrayList<>();
                for (JsonNode n : raiz.path("regras")) {
                    try {
                        regras.add(ler(n));
                    } catch (RuntimeException e) {
                        log.warn("Regra de gatilho inválida descartada: {}", e.getMessage());
                    }
                }
                Set<String> migrados = new LinkedHashSet<>();
                raiz.path("migrados").forEach(m -> migrados.add(Json.texto(m)));
                List<String> vistos = new ArrayList<>();
                raiz.path("entrada").path("vistos").forEach(v -> vistos.add(Json.texto(v)));
                return new Estado(regras, migrados, raiz.path("entrada").path("inicializada").asBoolean(), vistos);
            } catch (IOException | RuntimeException e) {
                log.warn("Gatilhos ilegíveis em {}; tentando o backup: {}", candidato.getFileName(), e.getMessage());
            }
        }
        return Estado.vazio();
    }

    private static RegraDeGatilho ler(JsonNode n) {
        String id = Json.texto(n.path("id"));
        long usuario = Json.numero(n.path("usuario"));
        if (!id.matches("[a-f0-9]{8}") || usuario <= 0) throw new IllegalArgumentException("identidade inválida");
        FonteDeGatilho fonte = FonteDeGatilho.porChave(Json.texto(n.path("fonte")));
        if (fonte == null) throw new IllegalArgumentException("fonte desconhecida");
        JsonNode f = n.path("filtro");
        LocalDateTime quando = null;
        Repeticao repeticao = Repeticao.NENHUMA;
        String quandoTexto = Json.texto(f.path("quando"));
        if (!quandoTexto.isBlank()) {
            quando = LocalDateTime.parse(quandoTexto);
            Set<DayOfWeek> dias = EnumSet.noneOf(DayOfWeek.class);
            f.path("diasDaSemana").forEach(d -> dias.add(DayOfWeek.valueOf(Json.texto(d))));
            repeticao = new Repeticao(Recorrencia.porChave(Json.texto(f.path("recorrencia"))), dias,
                    (int) Json.numero(f.path("diaDoMes")));
        }
        if (fonte == FonteDeGatilho.HORARIO && quando == null) throw new IllegalArgumentException("horário sem quando");
        Filtro filtro = new Filtro(Json.texto(f.path("site")), Json.texto(f.path("nome")),
                Json.texto(f.path("formato")), Json.texto(f.path("tipo")), quando, repeticao);
        return new RegraDeGatilho(id, usuario, fonte, filtro, Json.texto(n.path("instrucao")),
                Json.texto(n.path("evidencia")), n.path("ativa").asBoolean(true),
                Instant.parse(Json.texto(n.path("criadaEm"))), instante(n, "ultimoDisparo"),
                instante(n, "proximo"), Json.texto(n.path("origem")), Json.texto(n.path("pedido")));
    }

    private static Instant instante(JsonNode n, String campo) {
        String s = Json.texto(n.path(campo));
        return s.isBlank() ? null : Instant.parse(s);
    }
}
