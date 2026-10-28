package dev.jarvis.ferramentas;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.conversa.OuvinteProgresso;
import tools.jackson.databind.JsonNode;

/** Chamada comum às duas consultas de arquivos, preservando o timeout da fila. */
final class ConsultaArquivosPc {
    private ConsultaArquivosPc() { }

    /**
     * O que voltou do PC: ou o JSON do agente, ou a frase que explica a falta.
     *
     * As duas ferramentas de consulta repassam o JSON cru ao modelo, mas o gráfico de planilha
     * precisa ler as células em Java — e {@code dados().toString()} não serve para isso.
     */
    record Resposta(JsonNode dados, String recado) {
        boolean temDados() { return dados != null; }
    }

    static String executar(PcService pc, String acao, JsonNode entrada) throws Exception {
        return executar(pc, acao, entrada, new OuvinteProgresso() { });
    }

    static String executar(PcService pc, String acao, JsonNode entrada,
            OuvinteProgresso progresso) throws Exception {
        Resposta resposta = consultar(pc, acao, entrada, progresso);
        return resposta.temDados() ? resposta.dados().toString() : resposta.recado();
    }

    static Resposta consultar(PcService pc, String acao, JsonNode entrada,
            OuvinteProgresso progresso) throws Exception {
        if (!pc.online()) return falta("O PC está offline; os arquivos originais locais estão indisponíveis.");
        if (entrada == null || !entrada.isObject() || entrada.toString().length() > 2000)
            return falta("Parâmetros de consulta inválidos.");
        // O agente só aceita o UUID do catálogo, e o modelo às vezes manda o nome do arquivo no
        // lugar dele. Lá a recusa sai genérica; aqui dá para dizer o que corrigir, sem ir ao PC.
        JsonNode id = entrada.path("id");
        if (id.isString() && !ID_DO_CATALOGO.matcher(id.stringValue()).matches())
            return falta("'" + cortar(id.stringValue()) + "' não é um id do catálogo. Use o campo "
                    + "id (36 caracteres) que listar_arquivos devolveu para este arquivo, não o nome.");
        ComandoPc comando = new ComandoPc(UUID.randomUUID().toString(), acao, "",
                Instant.now().toString(), entrada.deepCopy());
        ResultadoPc resultado;
        try { resultado = pc.enfileirar(comando, progresso.canal(), progresso.usuarioAutenticado())
                .get(55, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException e) {
            if (pc.estadoE2(comando.id()).isPresent()) {
                pc.pedirAviso(comando.id());
                return falta("Consulta em andamento no PC: " + comando.id()
                        + ". Consulte status_operacao_pc; o resultado será avisado.");
            }
            pc.cancelar(comando.id()); return falta("O PC não respondeu à consulta a tempo.");
        } catch (java.util.concurrent.ExecutionException e) {
            pc.cancelar(comando.id());
            return falta("A conexão com o PC falhou; os arquivos originais locais estão indisponíveis.");
        } catch (InterruptedException e) {
            if (pc.estadoE2(comando.id()).isPresent()) pc.pedirAviso(comando.id());
            else pc.cancelar(comando.id());
            Thread.currentThread().interrupt(); throw e;
        }
        if (!resultado.ok()) return falta("Consulta de arquivos: " + resultado.mensagem());
        if (resultado.dados() == null) return falta("O PC não devolveu dados estruturados.");
        return new Resposta(resultado.dados(), "");
    }

    private static Resposta falta(String recado) {
        return new Resposta(null, recado);
    }

    /** O mesmo formato que o {@code CatalogoDeArquivos.porId} do agente exige. */
    private static final Pattern ID_DO_CATALOGO = Pattern.compile("[0-9a-fA-F-]{36}");

    private static String cortar(String texto) {
        return texto.length() > 80 ? texto.substring(0, 80) + "…" : texto;
    }
}
