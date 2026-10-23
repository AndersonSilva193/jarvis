package dev.jarvis.custo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * A pasta onde o gasto de cada dia sobrevive ao restart: um {@code AAAA-MM-DD.json} por dia,
 * gravado pela troca atômica do {@link ArquivoAtomico} — o mesmo utilitário dos lembretes.
 *
 * O conteúdo é <b>agregado</b>, nunca um registro por chamada: um dia movimentado passa de mil
 * chamadas ao modelo, e guardar cada uma viraria megabytes por semana para responder uma pergunta
 * que só precisa de somas. Agregado, o dia fica em torno de um kilobyte e os 90 guardados cabem
 * folgados no volume.
 *
 * Principal ilegível cai no {@code .bak} com WARN; os dois ruins devolvem um dia vazio. O Jarvis
 * perder a conta de hoje é ruim; o Jarvis não subir por causa dela seria pior.
 */
class CustoDiarioEmArquivo {

    private static final Logger log = LoggerFactory.getLogger(CustoDiarioEmArquivo.class);

    /** Teto de sanidade: o dia agregado não passa de alguns kilobytes, maior que isso é defeito. */
    private static final long MAX_BYTES = 512_000;

    static final Pattern NOME_DO_DIA = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\.json");

    private final Path pasta;
    private final JsonMapper json;
    private final ZoneId zona;

    CustoDiarioEmArquivo(Path pasta, JsonMapper json, ZoneId zona) {
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
    EstadoDoDia carregar(LocalDate dia) {
        Path arquivo = arquivoDe(dia);
        try {
            if (Files.exists(arquivo)) {
                return ler(dia, arquivo);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Diário de custo de {} ilegível ({}); tentando o backup .bak", dia, e.getMessage());
        }
        Path backup = ArquivoAtomico.backupDe(arquivo);
        try {
            if (Files.exists(backup)) {
                EstadoDoDia doBackup = ler(dia, backup);
                log.warn("Custo de {} recuperado do backup .bak", dia);
                return doBackup;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Backup de custo de {} também ilegível ({}); começando o dia do zero",
                    dia, e.getMessage());
        }
        return new EstadoDoDia(dia);
    }

    private EstadoDoDia ler(LocalDate dia, Path deOnde) throws IOException {
        JsonNode raiz = json.readTree(ArquivoAtomico.ler(deOnde, MAX_BYTES));
        if (!raiz.isObject()) {
            throw new IOException("raiz do diário de custo não é um objeto JSON");
        }
        String fusoSalvo = Json.texto(raiz.path("fuso"));
        if (!fusoSalvo.isBlank() && !fusoSalvo.equals(zona.getId())) {
            // O dia foi fechado noutro fuso. Os números continuam válidos; o que não dá é
            // reagrupá-los calado, porque a fronteira da meia-noite mudou de lugar.
            log.warn("Custo de {} foi gravado no fuso {} e agora o Jarvis roda em {}; os totais do "
                    + "dia podem cobrir um intervalo diferente", dia, fusoSalvo, zona.getId());
        }

        EstadoDoDia estado = new EstadoDoDia(dia);
        for (Map.Entry<String, JsonNode> entrada : raiz.path("porOrigem").properties()) {
            Origem origem = Origem.porChave(entrada.getKey());
            if (origem == null) {
                log.warn("Custo de {}: origem desconhecida '{}' ignorada", dia, entrada.getKey());
                continue;
            }
            estado.porOrigem().put(origem, lerUso(entrada.getValue()));
        }
        for (Map.Entry<String, JsonNode> entrada : raiz.path("porModelo").properties()) {
            if (entrada.getKey().isBlank()) {
                continue;
            }
            estado.porModelo().put(entrada.getKey(), lerUso(entrada.getValue()));
        }

        JsonNode avisos = raiz.path("avisos");
        estado.avisou80 = Json.booleano(avisos.path("oitenta"));
        estado.avisou100 = Json.booleano(avisos.path("cem"));
        estado.tetoGravado = Json.decimal(avisos.path("tetoUsd"));
        return estado;
    }

    private Uso lerUso(JsonNode no) {
        Uso uso = new Uso();
        uso.somar(Json.numero(no.path("entrada")), Json.numero(no.path("saida")),
                Json.numero(no.path("cacheEscrito")), Json.numero(no.path("cacheLido")),
                Json.numero(no.path("buscasWeb")), Json.decimal(no.path("custoUsd")),
                Json.booleano(no.path("semPreco")));
        return uso;
    }

    /** Grava o dia inteiro. Lança {@link IOException} para quem chamou decidir o que logar. */
    void salvar(EstadoDoDia estado) throws IOException {
        ObjectNode raiz = json.createObjectNode();
        raiz.put("dia", estado.dia().toString());
        raiz.put("fuso", zona.getId());
        raiz.put("gravadoEm", Instant.now().toString());
        raiz.put("totalUsd", estado.totalUsd());
        raiz.put("buscasWeb", estado.buscasWeb());

        ObjectNode avisos = raiz.putObject("avisos");
        avisos.put("oitenta", estado.avisou80);
        avisos.put("cem", estado.avisou100);
        avisos.put("tetoUsd", estado.tetoGravado);

        ObjectNode porOrigem = raiz.putObject("porOrigem");
        estado.porOrigem().forEach((origem, uso) -> escrever(porOrigem.putObject(origem.chave()), uso, false));
        ObjectNode porModelo = raiz.putObject("porModelo");
        estado.porModelo().forEach((modelo, uso) -> escrever(porModelo.putObject(modelo), uso, true));

        ArquivoAtomico.gravar(arquivoDe(estado.dia()), json.writeValueAsString(raiz));
    }

    /** {@code semPreco} só sai em porModelo: é do modelo que a falta de preço é propriedade. */
    private void escrever(ObjectNode no, Uso uso, boolean comSemPreco) {
        no.put("entrada", uso.entrada);
        no.put("saida", uso.saida);
        no.put("cacheEscrito", uso.cacheEscrito);
        no.put("cacheLido", uso.cacheLido);
        no.put("buscasWeb", uso.buscasWeb);
        no.put("custoUsd", uso.custoUsd);
        if (comSemPreco) {
            no.put("semPreco", uso.semPreco);
        }
    }

    /**
     * Apaga os dias mais velhos que o prazo e devolve quantos saíram.
     *
     * Apaga pelo {@link ArquivoAtomico#apagar}, que leva o {@code .bak} junto — um
     * {@code Files.delete} deixaria o backup do dia 91 parado no volume para sempre. Nome que não
     * casa o padrão é <b>ignorado, nunca apagado</b>: arquivo alheio num volume não é nosso.
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
            log.warn("Não consegui varrer a pasta de custo para o expurgo: {}", e.getMessage());
            return 0;
        }
        int apagados = 0;
        for (Path velho : velhos) {
            try {
                ArquivoAtomico.apagar(velho);
                apagados++;
            } catch (IOException | RuntimeException e) {
                log.warn("Não consegui apagar o custo antigo {}: {}", velho.getFileName(), e.getMessage());
            }
        }
        return apagados;
    }
}
