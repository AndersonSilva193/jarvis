package dev.jarvis.proativo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jarvis.util.ArquivoAtomico;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A pasta onde o histórico de disponibilidade sobrevive ao restart: um {@code AAAA-MM-DD.json} por
 * dia, pela troca atômica do {@link ArquivoAtomico} — o mesmo desenho do {@code DiarioEmArquivo}.
 *
 * <p>Dia ilegível cai no {@code .bak} com WARN; os dois ruins devolvem um dia vazio. Perder o
 * histórico de um dia é ruim; o Jarvis não subir por causa dele seria pior.
 */
class DisponibilidadeEmArquivo {

    private static final Logger log = LoggerFactory.getLogger(DisponibilidadeEmArquivo.class);

    /** Teto de sanidade: um dia agregado tem uma linha por site, não uma por checagem. */
    private static final long MAX_BYTES = 128_000;

    static final Pattern NOME_DO_DIA = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\.json");

    private final Path pasta;
    private final JsonMapper json;
    private final ZoneId zona;

    DisponibilidadeEmArquivo(Path pasta, JsonMapper json, ZoneId zona) {
        this.pasta = pasta.toAbsolutePath().normalize();
        this.json = json;
        this.zona = zona;
    }

    Path arquivoDe(LocalDate dia) {
        return pasta.resolve(dia + ".json");
    }

    boolean existe(LocalDate dia) {
        return Files.exists(arquivoDe(dia));
    }

    /** O dia salvo, ou um dia vazio. Nunca lança. */
    DiaDeDisponibilidade carregar(LocalDate dia) {
        Path arquivo = arquivoDe(dia);
        try {
            if (Files.exists(arquivo)) {
                return ler(dia, arquivo);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Disponibilidade de {} ilegível ({}); tentando o backup .bak", dia,
                    e.getMessage());
        }
        Path backup = ArquivoAtomico.backupDe(arquivo);
        try {
            if (Files.exists(backup)) {
                DiaDeDisponibilidade doBackup = ler(dia, backup);
                log.warn("Disponibilidade de {} recuperada do backup .bak", dia);
                return doBackup;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Backup da disponibilidade de {} também ilegível ({}); começando do zero", dia,
                    e.getMessage());
        }
        return new DiaDeDisponibilidade(dia);
    }

    private DiaDeDisponibilidade ler(LocalDate dia, Path deOnde) throws IOException {
        JsonNode raiz = json.readTree(ArquivoAtomico.ler(deOnde, MAX_BYTES));
        if (!raiz.isObject()) {
            throw new IOException("raiz do histórico não é um objeto JSON");
        }
        String fusoSalvo = Json.texto(raiz.path("fuso"));
        if (!fusoSalvo.isBlank() && !fusoSalvo.equals(zona.getId())) {
            // O dia foi fechado noutro fuso: as contagens valem, mas cobrem outro intervalo.
            log.warn("Disponibilidade de {} foi gravada no fuso {} e agora o Jarvis roda em {}; as "
                    + "contagens do dia podem cobrir um intervalo diferente", dia, fusoSalvo,
                    zona.getId());
        }

        DiaDeDisponibilidade estado = new DiaDeDisponibilidade(dia);
        for (java.util.Map.Entry<String, JsonNode> entrada : raiz.path("sites").properties()) {
            String site = entrada.getKey();
            JsonNode no = entrada.getValue();
            estado.porSite().put(site, new DiaDeDisponibilidade.Contagem(
                    (int) Json.numero(no.path("checagens")),
                    (int) Json.numero(no.path("falhas")),
                    (int) Json.numero(no.path("quedas"))));
        }
        return estado;
    }

    /** Grava o dia inteiro. Lança {@link IOException} para quem chamou decidir o que logar. */
    void salvar(DiaDeDisponibilidade estado) throws IOException {
        ObjectNode raiz = json.createObjectNode();
        raiz.put("dia", estado.dia().toString());
        raiz.put("fuso", zona.getId());
        raiz.put("gravadoEm", Instant.now().toString());
        ObjectNode sites = raiz.putObject("sites");
        estado.porSite().forEach((site, contagem) -> {
            ObjectNode no = sites.putObject(site);
            no.put("checagens", contagem.checagens());
            no.put("falhas", contagem.falhas());
            no.put("quedas", contagem.quedas());
        });
        ArquivoAtomico.gravar(arquivoDe(estado.dia()), json.writeValueAsString(raiz));
    }

    /**
     * Apaga os dias mais velhos que o prazo e devolve quantos saíram.
     *
     * Nome que não casa o padrão é <b>ignorado, nunca apagado</b>: arquivo alheio num volume não é
     * nosso.
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
            log.warn("Não consegui varrer a pasta do histórico para o expurgo: {}", e.getMessage());
            return 0;
        }
        int apagados = 0;
        for (Path velho : velhos) {
            try {
                ArquivoAtomico.apagar(velho);
                apagados++;
            } catch (IOException | RuntimeException e) {
                log.warn("Não consegui apagar o histórico antigo {}: {}", velho.getFileName(),
                        e.getMessage());
            }
        }
        return apagados;
    }
}
