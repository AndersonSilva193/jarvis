package dev.jarvis.ferramentas;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.ResultadoPc;
import tools.jackson.databind.JsonNode;

/** Chamada comum às duas consultas de arquivos, preservando o timeout da fila. */
final class ConsultaArquivosPc {
    private ConsultaArquivosPc() { }
    static String executar(PcService pc, String acao, JsonNode entrada) throws Exception {
        if (!pc.online()) return "O PC está offline; os arquivos originais locais estão indisponíveis.";
        if (entrada == null || !entrada.isObject() || entrada.toString().length() > 2000)
            return "Parâmetros de consulta inválidos.";
        ComandoPc comando = new ComandoPc(UUID.randomUUID().toString(), acao, "",
                Instant.now().toString(), entrada.deepCopy());
        ResultadoPc resultado;
        try { resultado = pc.enfileirar(comando).get(55, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException e) {
            pc.cancelar(comando.id()); return "O PC não respondeu à consulta a tempo.";
        } catch (java.util.concurrent.ExecutionException e) {
            pc.cancelar(comando.id());
            return "A conexão com o PC falhou; os arquivos originais locais estão indisponíveis.";
        } catch (InterruptedException e) {
            pc.cancelar(comando.id()); Thread.currentThread().interrupt(); throw e;
        }
        if (!resultado.ok()) return "Consulta de arquivos: " + resultado.mensagem();
        if (resultado.dados() == null) return "O PC não devolveu dados estruturados.";
        return resultado.dados().toString();
    }
}
