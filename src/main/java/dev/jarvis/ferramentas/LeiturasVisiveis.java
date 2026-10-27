package dev.jarvis.ferramentas;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Guarda somente os intervalos de texto que chegaram ao modelo para uma reescrita total. */
@Component
public class LeiturasVisiveis {
    private static final Duration VALIDADE = Duration.ofMinutes(10);
    private static final int MAX_ARQUIVOS = 256;

    private record Chave(long usuario, String id, String versao) { }
    private record Intervalo(int inicio, int fim) { }
    private static final class Leitura {
        int total;
        Instant vista;
        List<Intervalo> intervalos = new ArrayList<>();
        Leitura(int total, Instant vista) { this.total = total; this.vista = vista; }
    }
    private final Map<Chave, Leitura> porArquivo = new LinkedHashMap<>();

    /** O texto recebido aqui já é o {@code tool_result.content} limitado pela Ferramentas. */
    public synchronized void registrar(long usuario, String textoParaModelo, JsonMapper json) {
        if (usuario <= 0 || textoParaModelo == null) return;
        JsonNode resultado;
        try { resultado = json.readTree(textoParaModelo); }
        catch (RuntimeException invalido) { return; }
        if (!resultado.isObject()) return;
        String formato = resultado.path("formato").asText("");
        if (!formato.equals("txt") && !formato.equals("md")) return;
        String id = resultado.path("id").asText("");
        String versao = resultado.path("versaoEsperada").asText("");
        JsonNode conteudo = resultado.path("conteudo");
        JsonNode inicioNo = resultado.path("inicio");
        JsonNode totalNo = resultado.path("totalCaracteres");
        if (!id.matches("[0-9a-fA-F-]{36}") || !versao.matches("[0-9a-f]{64}")
                || !conteudo.isString() || !inicioNo.isNumber() || !totalNo.isNumber()) return;
        int inicio = inicioNo.intValue(), total = totalNo.intValue();
        int fim = inicio + conteudo.stringValue().length();
        if (inicio < 0 || total < 0 || fim < inicio || fim > total) return;

        Instant agora = Instant.now();
        expurgar(agora);
        Chave chave = new Chave(usuario, id, versao);
        Leitura leitura = porArquivo.get(chave);
        if (leitura == null || leitura.total != total) {
            leitura = new Leitura(total, agora);
            porArquivo.put(chave, leitura);
        }
        leitura.vista = agora;
        leitura.intervalos.add(new Intervalo(inicio, fim));
        leitura.intervalos.sort(Comparator.comparingInt(Intervalo::inicio));
        List<Intervalo> unidos = new ArrayList<>();
        for (Intervalo atual : leitura.intervalos) {
            if (unidos.isEmpty() || atual.inicio() > unidos.getLast().fim()) {
                unidos.add(atual);
            } else {
                Intervalo ultimo = unidos.removeLast();
                unidos.add(new Intervalo(ultimo.inicio(), Math.max(ultimo.fim(), atual.fim())));
            }
        }
        leitura.intervalos = unidos;
        while (porArquivo.size() > MAX_ARQUIVOS) porArquivo.remove(porArquivo.keySet().iterator().next());
    }

    public synchronized boolean integral(long usuario, String id, String versao) {
        expurgar(Instant.now());
        Leitura leitura = porArquivo.get(new Chave(usuario, id, versao));
        return leitura != null && leitura.intervalos.size() == 1
                && leitura.intervalos.getFirst().inicio() == 0
                && leitura.intervalos.getFirst().fim() == leitura.total;
    }

    private void expurgar(Instant agora) {
        Iterator<Leitura> leituras = porArquivo.values().iterator();
        while (leituras.hasNext()) {
            if (leituras.next().vista.plus(VALIDADE).isBefore(agora)) leituras.remove();
        }
    }
}
