package dev.jarvis.diario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jarvis.util.ArquivoAtomico;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A pasta onde o que o Jarvis fez sobrevive ao restart: um {@code AAAA-MM-DD.json} por dia, pela
 * troca atômica do {@link ArquivoAtomico} — o mesmo desenho do {@code CustoDiarioEmArquivo}.
 *
 * <p>Ao lado dos dias mora o {@link #MARCADOR}, que guarda até onde a seção do briefing já contou.
 * Ele <b>não</b> casa o {@link #NOME_DO_DIA}, então o expurgo passa por ele sem o apagar.
 *
 * <p>Dia ilegível cai no {@code .bak} com WARN; os dois ruins devolvem um dia vazio. O Jarvis
 * perder o diário de um dia é ruim; o Jarvis não subir por causa dele seria pior.
 */
class DiarioEmArquivo {

    private static final Logger log = LoggerFactory.getLogger(DiarioEmArquivo.class);

    /**
     * Teto de sanidade da leitura, folgado sobre o pior dia possível: {@link DiaDoDiario#MAX_ACOES}
     * linhas de {@link AcaoRegistrada#MAX_DESCRICAO} caracteres não chegam a 100 KB.
     */
    private static final long MAX_BYTES = 512_000;

    static final Pattern NOME_DO_DIA = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\.json");

    /** Até onde o briefing já contou. Nome sem data de propósito: o expurgo não o reconhece. */
    static final String MARCADOR = "ultimo-briefing.json";

    private final Path pasta;
    private final JsonMapper json;
    private final ZoneId zona;

    DiarioEmArquivo(Path pasta, JsonMapper json, ZoneId zona) {
        this.pasta = pasta.toAbsolutePath().normalize();
        this.json = json;
        this.zona = zona;
    }

    Path pasta() {
        return pasta;
    }

    Path arquivoDe(LocalDate dia) {
        return pasta.resolve(dia + ".json");
    }

    boolean existe(LocalDate dia) {
        return Files.exists(arquivoDe(dia));
    }

    /** O dia salvo, ou um dia vazio. Nunca lança: quem chama está no caminho de uma resposta. */
    DiaDoDiario carregar(LocalDate dia) {
        Path arquivo = arquivoDe(dia);
        try {
            if (Files.exists(arquivo)) {
                return ler(dia, arquivo);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Diário de {} ilegível ({}); tentando o backup .bak", dia, e.getMessage());
        }
        Path backup = ArquivoAtomico.backupDe(arquivo);
        try {
            if (Files.exists(backup)) {
                DiaDoDiario doBackup = ler(dia, backup);
                log.warn("Diário de {} recuperado do backup .bak", dia);
                return doBackup;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Backup do diário de {} também ilegível ({}); começando o dia do zero",
                    dia, e.getMessage());
        }
        return new DiaDoDiario(dia);
    }

    private DiaDoDiario ler(LocalDate dia, Path deOnde) throws IOException {
        JsonNode raiz = json.readTree(ArquivoAtomico.ler(deOnde, MAX_BYTES));
        if (!raiz.isObject()) {
            throw new IOException("raiz do diário não é um objeto JSON");
        }
        String fusoSalvo = Json.texto(raiz.path("fuso"));
        if (!fusoSalvo.isBlank() && !fusoSalvo.equals(zona.getId())) {
            // O dia foi fechado noutro fuso. As ações continuam válidas; o que não dá é reagrupá-las
            // calado, porque a fronteira da meia-noite mudou de lugar.
            log.warn("Diário de {} foi gravado no fuso {} e agora o Jarvis roda em {}; as ações do "
                    + "dia podem cobrir um intervalo diferente", dia, fusoSalvo, zona.getId());
        }

        DiaDoDiario estado = new DiaDoDiario(dia);
        JsonNode acoes = raiz.path("acoes");
        for (int i = 0; i < acoes.size(); i++) {
            AcaoRegistrada acao = lerAcao(dia, acoes.get(i));
            if (acao != null) {
                estado.somar(acao);
            }
        }
        // O contador volta do JSON, para o dia truncado continuar se declarando truncado.
        for (long i = 0; i < Json.numero(raiz.path("descartadas")); i++) {
            estado.contarDescartada();
        }
        return estado;
    }

    /** Linha sem instante, sem tipo conhecido ou com origem estranha é descartada com WARN. */
    private AcaoRegistrada lerAcao(LocalDate dia, JsonNode no) {
        TipoDeAcao tipo = TipoDeAcao.porChave(Json.texto(no.path("tipo")));
        if (tipo == null) {
            log.warn("Diário de {}: tipo desconhecido '{}' ignorado", dia, Json.texto(no.path("tipo")));
            return null;
        }
        Instant quando;
        try {
            quando = Instant.parse(Json.texto(no.path("instante")));
        } catch (RuntimeException naoEhInstante) {
            log.warn("Diário de {}: instante ilegível '{}' ignorado", dia,
                    Json.texto(no.path("instante")));
            return null;
        }
        OrigemDaAcao origem = OrigemDaAcao.porChave(Json.texto(no.path("origem")));
        return new AcaoRegistrada(quando, tipo, Json.texto(no.path("descricao")),
                origem == null ? OrigemDaAcao.DESCONHECIDA : origem);
    }

    /** Grava o dia inteiro. Lança {@link IOException} para quem chamou decidir o que logar. */
    void salvar(DiaDoDiario estado) throws IOException {
        ObjectNode raiz = json.createObjectNode();
        raiz.put("dia", estado.dia().toString());
        raiz.put("fuso", zona.getId());
        raiz.put("gravadoEm", Instant.now().toString());
        raiz.put("descartadas", estado.descartadas());

        ArrayNode acoes = raiz.putArray("acoes");
        for (AcaoRegistrada acao : estado.acoes()) {
            ObjectNode no = acoes.addObject();
            no.put("instante", acao.instante().toString());
            no.put("tipo", acao.tipo().chave());
            no.put("descricao", acao.descricao());
            no.put("origem", acao.origem().chave());
        }

        ArquivoAtomico.gravar(arquivoDe(estado.dia()), json.writeValueAsString(raiz));
    }

    // ---------------------------------------------------------------- marcador do briefing

    /** Até onde o briefing já contou, ou vazio na primeira vez. Nunca lança. */
    Optional<Instant> marcador() {
        Path arquivo = pasta.resolve(MARCADOR);
        try {
            if (!Files.exists(arquivo)) {
                return Optional.empty();
            }
            JsonNode raiz = json.readTree(ArquivoAtomico.ler(arquivo, MAX_BYTES));
            return Optional.of(Instant.parse(Json.texto(raiz.path("contadoAte"))));
        } catch (IOException | RuntimeException e) {
            log.warn("Marcador do briefing ilegível ({}); a seção conta a partir de hoje",
                    e.getMessage());
            return Optional.empty();
        }
    }

    void salvarMarcador(Instant contadoAte) throws IOException {
        ObjectNode raiz = json.createObjectNode();
        raiz.put("contadoAte", contadoAte.toString());
        raiz.put("gravadoEm", Instant.now().toString());
        ArquivoAtomico.gravar(pasta.resolve(MARCADOR), json.writeValueAsString(raiz));
    }

    // ---------------------------------------------------------------- expurgo

    /**
     * Apaga os dias mais velhos que o prazo e devolve quantos saíram.
     *
     * Apaga pelo {@link ArquivoAtomico#apagar}, que leva o {@code .bak} junto. Nome que não casa o
     * padrão é <b>ignorado, nunca apagado</b> — arquivo alheio num volume não é nosso, e é por isso
     * que o {@link #MARCADOR} pode morar na mesma pasta.
     */
    int expurgar(LocalDate limite) {
        if (!Files.isDirectory(pasta)) {
            return 0;
        }
        List<Path> velhos = new ArrayList<>();
        try (var arquivos = Files.list(pasta)) {
            arquivos.forEach(arquivo -> {
                Matcher nome = NOME_DO_DIA.matcher(arquivo.getFileName().toString());
                if (!nome.matches()) {
                    return;
                }
                try {
                    if (LocalDate.parse(nome.group(1)).isBefore(limite)) {
                        velhos.add(arquivo);
                    }
                } catch (DateTimeParseException naoEhDia) {
                    // 2026-13-45.json casa o padrão e não é data: fica onde está.
                }
            });
        } catch (IOException | RuntimeException e) {
            log.warn("Não consegui varrer a pasta do diário para o expurgo: {}", e.getMessage());
            return 0;
        }
        int apagados = 0;
        for (Path velho : velhos) {
            try {
                ArquivoAtomico.apagar(velho);
                apagados++;
            } catch (IOException | RuntimeException e) {
                log.warn("Não consegui apagar o diário antigo {}: {}", velho.getFileName(),
                        e.getMessage());
            }
        }
        return apagados;
    }
}
