package dev.jarvis.briefing.fontes;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.stereotype.Service;

/** Lê a saúde do servidor onde o Jarvis roda: CPU, memória, disco e tempo ligado. */
@Service
public class ServidorService {

    /** Uma foto do servidor. As duas formas de ler estão em detalhado() e curto(). */
    public record EstadoServidor(
            int cpus,
            double cargaMedia,
            double usoCpu,
            long memoriaUsada,
            long memoriaTotal,
            long discoUsado,
            long discoTotal,
            long discoLivre,
            String tempoLigado) {

        /** Texto completo, em várias linhas: é o que a ferramenta status_servidor devolve. */
        public String detalhado() {
            StringBuilder sb = new StringBuilder();
            sb.append("CPUs: ").append(cpus);
            if (cargaMedia >= 0) {
                sb.append(String.format(" | carga média (1 min): %.2f", cargaMedia));
            }
            if (usoCpu >= 0) {
                sb.append(String.format(" | uso de CPU agora: %.0f%%", usoCpu * 100));
            }
            sb.append("\nMemória: ").append(gb(memoriaUsada)).append(" usados de ").append(gb(memoriaTotal));
            sb.append("\nDisco (/): ").append(gb(discoUsado)).append(" usados de ").append(gb(discoTotal))
                    .append(String.format(" (%.0f%% livre)", discoTotal == 0 ? 0 : 100.0 * discoLivre / discoTotal));
            sb.append("\nLigado há: ").append(tempoLigado);
            return sb.toString();
        }

        /** Uma linha só, para caber no briefing sem ocupar meia tela. */
        public String curto() {
            StringBuilder sb = new StringBuilder("Servidor: ");
            if (usoCpu >= 0) {
                sb.append(String.format("CPU %.0f%%", usoCpu * 100));
            } else {
                sb.append(cpus).append(" CPUs");
            }
            sb.append(String.format(", memória %s/%s", gb(memoriaUsada), gb(memoriaTotal)));
            if (discoTotal > 0) {
                sb.append(String.format(", disco %.0f%% livre", 100.0 * discoLivre / discoTotal));
            }
            return sb.toString();
        }

        private static String gb(long bytes) {
            return String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024);
        }
    }

    public EstadoServidor estado() {
        var so = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long memTotal = so.getTotalMemorySize();
        long memLivre = so.getFreeMemorySize();

        File raiz = new File("/");
        long discoTotal = raiz.getTotalSpace();
        long discoLivre = raiz.getUsableSpace();

        return new EstadoServidor(
                so.getAvailableProcessors(),
                so.getSystemLoadAverage(),
                so.getCpuLoad(),
                memTotal - memLivre,
                memTotal,
                discoTotal - discoLivre,
                discoTotal,
                discoLivre,
                tempoLigado());
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
