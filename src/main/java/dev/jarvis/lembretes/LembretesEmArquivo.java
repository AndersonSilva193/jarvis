package dev.jarvis.lembretes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jarvis.lembretes.LembreteService.Lembrete;
import dev.jarvis.util.ArquivoAtomico;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O arquivo JSON onde os lembretes sobrevivem ao restart do container.
 *
 * Grava num temporário na mesma pasta e troca com {@code ATOMIC_MOVE}: um desligamento no meio da
 * escrita deixa o arquivo antigo inteiro, nunca meio JSON. A versão anterior fica num {@code .bak}
 * ao lado, que é de onde a partida lê quando o principal não abre — perder a agenda do dono por um
 * byte torto seria pior do que voltar um pouco no tempo.
 *
 * Os instantes vão em ISO-8601 com fuso ({@code ZonedDateTime#toString}), não em epoch: o lembrete
 * é "oito da manhã", e um horário de verão no meio do caminho não pode virar sete.
 */
class LembretesEmArquivo {

    private static final Logger log = LoggerFactory.getLogger(LembretesEmArquivo.class);

    /** Teto de sanidade: a agenda de uma pessoa não chega perto disso, arquivo maior é defeito. */
    private static final long MAX_BYTES = 2_000_000;

    /** O que estava salvo: os lembretes e o contador de IDs. */
    record Estado(List<Lembrete> lembretes, int proximoId) {

        static Estado vazio() {
            return new Estado(List.of(), 1);
        }
    }

    private final Path arquivo;
    private final Path backup;
    private final JsonMapper json;

    LembretesEmArquivo(Path arquivo, JsonMapper json) {
        this.arquivo = arquivo.toAbsolutePath().normalize();
        this.backup = ArquivoAtomico.backupDe(this.arquivo);
        this.json = json;
    }

    Path caminho() {
        return arquivo;
    }

    /**
     * Lê o estado salvo. Principal corrompido cai no {@code .bak} com WARN; os dois ruins (ou
     * nenhum dos dois existindo) devolvem estado vazio — o Jarvis sobe sem agenda em vez de não
     * subir.
     */
    Estado carregar() {
        try {
            if (Files.exists(arquivo)) {
                return ler(arquivo);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Arquivo de lembretes ilegível ({}); tentando o backup .bak", e.getMessage());
        }
        try {
            if (Files.exists(backup)) {
                Estado doBackup = ler(backup);
                log.warn("Lembretes recuperados do backup .bak: {} lembrete(s)", doBackup.lembretes().size());
                return doBackup;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Backup de lembretes também ilegível ({}); começando sem lembretes", e.getMessage());
        }
        return Estado.vazio();
    }

    private Estado ler(Path origem) throws IOException {
        JsonNode raiz = json.readTree(ArquivoAtomico.ler(origem, MAX_BYTES));
        if (!raiz.isObject()) {
            throw new IOException("raiz do arquivo de lembretes não é um objeto JSON");
        }
        List<Lembrete> lembretes = new ArrayList<>();
        int maiorId = 0;
        for (JsonNode no : raiz.path("lembretes")) {
            Lembrete lembrete = lerLembrete(no);
            if (lembrete == null) {
                continue;
            }
            lembretes.add(lembrete);
            maiorId = Math.max(maiorId, lembrete.id());
        }
        // Os IDs nunca voltam a 1: o dono fala "cancela o 7", e um número reaproveitado cancelaria
        // o lembrete errado. Por isso o maior ID visto também serve de piso para o contador.
        int proximoId = Math.max((int) Json.numero(raiz.path("proximoId")), maiorId + 1);
        return new Estado(lembretes, Math.max(proximoId, 1));
    }

    /** Linha inválida é descartada com WARN: um lembrete torto não leva os outros embora. */
    private Lembrete lerLembrete(JsonNode no) {
        try {
            int id = (int) Json.numero(no.path("id"));
            String texto = Json.texto(no.path("texto"));
            ZonedDateTime quando = ZonedDateTime.parse(Json.texto(no.path("quando")));
            if (id <= 0 || texto.isBlank()) {
                throw new IllegalArgumentException("id ou texto ausente");
            }
            return new Lembrete(id, texto, quando, lerRepeticao(no));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            log.warn("Lembrete salvo ignorado por estar inválido: {}", e.getMessage());
            return null;
        }
    }

    private Repeticao lerRepeticao(JsonNode no) {
        // Sem o campo (arquivo gravado por uma versão antiga) o lembrete é único, como era antes.
        Recorrencia tipo = Recorrencia.porChave(Json.texto(no.path("recorrencia")));
        if (tipo == Recorrencia.NENHUMA) {
            return Repeticao.NENHUMA;
        }
        Set<DayOfWeek> dias = EnumSet.noneOf(DayOfWeek.class);
        for (JsonNode dia : no.path("diasDaSemana")) {
            dias.add(DayOfWeek.valueOf(Json.texto(dia)));
        }
        return new Repeticao(tipo, dias, (int) Json.numero(no.path("diaDoMes")));
    }

    /** Grava o estado inteiro. Lança {@link IOException} para quem chamou decidir o que logar. */
    void salvar(List<Lembrete> lembretes, int proximoId) throws IOException {
        ObjectNode raiz = json.createObjectNode();
        raiz.put("proximoId", proximoId);
        ArrayNode lista = raiz.putArray("lembretes");
        for (Lembrete l : lembretes) {
            ObjectNode no = lista.addObject();
            no.put("id", l.id());
            no.put("texto", l.texto());
            no.put("quando", l.quando().toString());
            no.put("recorrencia", l.repeticao().tipo().chave());
            if (l.repeticao().tipo() == Recorrencia.SEMANAL) {
                ArrayNode dias = no.putArray("diasDaSemana");
                l.repeticao().diasOrdenados().forEach(dia -> dias.add(dia.name()));
            }
            if (l.repeticao().tipo() == Recorrencia.MENSAL) {
                no.put("diaDoMes", l.repeticao().diaDoMes());
            }
        }

        ArquivoAtomico.gravar(arquivo, json.writeValueAsString(raiz));
    }
}
