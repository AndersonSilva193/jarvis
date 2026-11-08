package dev.jarvis.ferramentas;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.conversa.ContextoArquivo;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

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
        ContextoArquivo contexto = progresso.contextoArquivo();
        // Com a seleção ambígua neste pedido, consultar continua valendo — refinar a listagem e ler
        // cada candidato é como se acha o arquivo certo, inclusive pelo conteúdo. O que a ambiguidade
        // trava é a edição (EditarArquivoFerramenta) e a escolha do alvo: nada consultado aqui vira
        // o "esse arquivo" da mensagem seguinte, senão seria o último lido, escolhido em silêncio.
        if (contexto != null && contexto.aguardaEsclarecimento(progresso.idPedido())) {
            if ("ler_arquivo".equals(acao) && (entrada == null || !entrada.isObject() || !entrada.has("id")))
                return "Há mais de um arquivo possível neste pedido. Leia pelo id que listar_arquivos "
                        + "devolveu; não escolha um resultado silenciosamente.";
            Resposta resposta = consultar(pc, acao, entrada, progresso);
            return resposta.temDados() ? resposta.dados().toString() : resposta.recado();
        }
        if (contexto != null && "listar_arquivos".equals(acao) && entrada != null
                && !Json.texto(entrada.path("nome")).isBlank()) contexto.limpar();
        if (contexto != null && "ler_arquivo".equals(acao) && entrada != null && entrada.isObject()) {
            var alvo = contexto.estado();
            if (alvo != null && !alvo.operacaoPendente().isEmpty())
                return "Consulte status_operacao_pc com id=" + alvo.operacaoPendente()
                        + " antes de reler ou editar: o resultado da gravação ainda é incerto.";
            ObjectNode pedido = (ObjectNode) entrada.deepCopy();
            if (!pedido.has("id")) {
                String erro = resolverPendente(pc, progresso, pedido);
                if (!erro.isEmpty()) return erro;
                alvo = contexto.estado();
                if (alvo == null || alvo.id().isEmpty())
                    return "Nenhum arquivo selecionado. Use listar_arquivos para localizar o arquivo.";
                pedido.put("id", alvo.id());
            } else if (alvo != null && pedido.path("id").isString()
                    && !alvo.id().equalsIgnoreCase(pedido.path("id").asText())) {
                contexto.limpar();
            }
            Resposta resposta = consultar(pc, acao, pedido, progresso);
            if (!resposta.temDados()) {
                contexto.exigirReleitura();
                return resposta.recado();
            }
            if (!Json.texto(resposta.dados().path("id")).equalsIgnoreCase(Json.texto(pedido.path("id")))) {
                contexto.exigirReleitura();
                return "O PC devolveu outro ID na leitura; não selecionei esse resultado.";
            }
            if (!contexto.registrar(resposta.dados(), true)
                    && java.util.Set.of("txt", "md", "json", "xlsx")
                            .contains(Json.texto(resposta.dados().path("formato")))) {
                contexto.exigirReleitura();
                return "Leitura sem ID, nome ou versão válidos; não há versão verificada para editar.";
            }
            return resposta.dados().toString();
        }
        Resposta resposta = consultar(pc, acao, entrada, progresso);
        if (contexto != null && "listar_arquivos".equals(acao) && resposta.temDados()) {
            JsonNode dados = resposta.dados();
            JsonNode arquivos = dados.path("arquivos");
            if (dados.path("total").asInt(-1) == 1 && arquivos.isArray() && arquivos.size() == 1
                    && !dados.path("temMais").asBoolean(false)) {
                if (!contexto.registrar(arquivos.get(0), false)) contexto.limpar();
            } else if (dados.path("total").asInt(0) > 1) contexto.selecaoAmbigua(progresso.idPedido());
            else contexto.limpar();
        }
        return resposta.temDados() ? resposta.dados().toString() : resposta.recado();
    }

    /** A criação só devolve o nome; a identidade permanente é recuperada do catálogo real. */
    static String resolverPendente(PcService pc, OuvinteProgresso progresso, ObjectNode modelo) throws Exception {
        ContextoArquivo contexto = progresso.contextoArquivo();
        var alvo = contexto == null ? null : contexto.estado();
        if (alvo == null || !alvo.id().isEmpty()) return "";
        for (int pagina = 1; pagina <= 10; pagina++) {
            var pedido = modelo.deepCopy().removeAll().put("nome", alvo.nome()).put("pagina", pagina)
                    .put("tamanhoPagina", 50);
            Resposta resposta = consultar(pc, "listar_arquivos", pedido, progresso);
            if (!resposta.temDados()) return resposta.recado();
            for (JsonNode arquivo : resposta.dados().path("arquivos"))
                if (alvo.nome().equals(Json.texto(arquivo.path("nome"))) && contexto.registrar(arquivo, false))
                    return "";
            if (!resposta.dados().path("temMais").asBoolean(false)) break;
        }
        return "O arquivo criado ainda não foi localizado pelo nome exato no catálogo. "
                + "Consulte listar_arquivos; não use um arquivo de nome semelhante.";
    }

    static Resposta consultar(PcService pc, String acao, JsonNode entrada,
            OuvinteProgresso progresso) throws Exception {
        if (!pc.online()) {
            dev.jarvis.tarefas.ContextoDaTarefa.esperarPcSeEmTarefa();
            return falta("O PC está offline; os arquivos originais locais estão indisponíveis.");
        }
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
