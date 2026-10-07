package dev.jarvis.ferramentas;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Mostra a saúde do servidor onde o Jarvis roda: carga de CPU, memória, disco e tempo ligado. */
@Component
public class StatusServidorFerramenta implements Ferramenta {

    private final JsonMapper json;

    public StatusServidorFerramenta(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String nome() {
        return "status_servidor";
    }

    @Override
    public String descricao() {
        return "Mostra o estado do servidor (VPS) onde o Jarvis está rodando: carga de CPU, memória, "
                + "espaço em disco e há quanto tempo está ligado.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        esquema.putObject("properties");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) {
        var so = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        StringBuilder sb = new StringBuilder();

        sb.append("CPUs: ").append(so.getAvailableProcessors());
        double carga = so.getSystemLoadAverage();
        if (carga >= 0) {
            sb.append(String.format(" | carga média (1 min): %.2f", carga));
        }
        double usoCpu = so.getCpuLoad();
        if (usoCpu >= 0) {
            sb.append(String.format(" | uso de CPU agora: %.0f%%", usoCpu * 100));
        }

        long memTotal = so.getTotalMemorySize();
        long memLivre = so.getFreeMemorySize();
        sb.append("\nMemória: ").append(gb(memTotal - memLivre)).append(" usados de ").append(gb(memTotal));

        File raiz = new File("/");
        long discoTotal = raiz.getTotalSpace();
        long discoLivre = raiz.getUsableSpace();
        sb.append("\nDisco (/): ").append(gb(discoTotal - discoLivre)).append(" usados de ").append(gb(discoTotal))
                .append(String.format(" (%.0f%% livre)", discoTotal == 0 ? 0 : 100.0 * discoLivre / discoTotal));

        sb.append("\nLigado há: ").append(tempoLigado());
        return sb.toString();
    }

    private static String gb(long bytes) {
        return String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024);
    }

    /** Lê /proc/uptime (Linux). Dentro do Docker isso mostra o tempo do servidor, não do container. */
    private static String tempoLigado() {
        try {
            String conteudo = Files.readString(Path.of("/proc/uptime")).trim();
            Duration d = Duration.ofSeconds((long) Double.parseDouble(conteudo.split("\\s+")[0]));
            return d.toDays() + " dias, " + d.toHoursPart() + " h e " + d.toMinutesPart() + " min";
        } catch (Exception e) {
            return "indisponível neste sistema";
        }
    }
}
