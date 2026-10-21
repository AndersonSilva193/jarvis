package dev.jarvis.pc;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.ferramentas.ResultadoFerramenta;
import dev.jarvis.telegram.TelegramClient;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Usa o E2 para preparar e aplicar edições, sem aceitar caminhos enviados pelo modelo. */
@Service
public class FluxoEdicaoPc {
    private final OperacoesPcService operacoes;
    private final ArquivosPcService arquivos;
    private final TelegramClient telegram;
    private final JsonMapper json;
    private record UltimaEdicao(String editId, String comandoId) { }
    private final ConcurrentHashMap<Long, UltimaEdicao> ultimasEdicoes = new ConcurrentHashMap<>();

    public FluxoEdicaoPc(OperacoesPcService operacoes, ArquivosPcService arquivos,
            TelegramClient telegram, JsonMapper json) {
        this.operacoes = operacoes; this.arquivos = arquivos;
        this.telegram = telegram; this.json = json;
    }

    public ResultadoPc preparar(String acao, JsonNode dados, OuvinteProgresso contexto) throws Exception {
        if (!operacoes.online()) throw new IllegalStateException("Agente do PC offline.");
        ComandoPc comando = novo(acao, dados);
        try {
            return operacoes.enfileirar(comando, Duration.ofSeconds(30), Duration.ofMinutes(2),
                    contexto.canal(), contexto.usuarioAutenticado()).get(55, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Preparação sem resposta; consulte o status da operação no PC.");
        }
    }

    /**
     * Aplica a proposta já preparada e espera o resultado. O comando leva só o {@code editId}:
     * caminho e conteúdo ficam no agente, que revalida o hash antes do move atômico.
     *
     * Espera em vez de enfileirar e sair porque a frase que volta afirma o que foi feito —
     * responder "atualizei" antes do PC confirmar deixaria o Jarvis prometendo o que ainda pode
     * falhar. É também o que deixa a edição já {@code concluído} para um "desfaz" na sequência.
     *
     * @param sucesso monta o texto a partir do nome que o agente devolveu.
     * @param original só a sobrescrita do original vira alvo de {@link #ultimaEdicaoConcluida}:
     *                 cópia é arquivo novo e não tem versão anterior para onde voltar.
     */
    public ResultadoFerramenta aplicar(String acao, String editId, OuvinteProgresso contexto,
            UnaryOperator<String> sucesso, boolean original) throws Exception {
        if (!operacoes.online())
            return ResultadoFerramenta.texto("Agente do PC offline; edição não iniciada.");
        ComandoPc comando = novo(acao, json.createObjectNode().put("editId", editId));
        ResultadoPc resultado;
        try {
            var futuro = operacoes.enfileirar(comando, Duration.ofSeconds(30),
                    Duration.ofMinutes(2), contexto.canal(), contexto.usuarioAutenticado());
            if ("aplicar_edicao".equals(acao) && original)
                ultimasEdicoes.put(contexto.usuarioAutenticado(),
                        new UltimaEdicao(editId, comando.id()));
            resultado = futuro.get(55, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            operacoes.pedirAviso(comando.id());
            return ResultadoFerramenta.texto("Ainda em andamento; avisarei o resultado.");
        } catch (RuntimeException e) {
            return ResultadoFerramenta.texto("Não apliquei: " + e.getMessage());
        }
        if (!resultado.ok())
            return ResultadoFerramenta.texto("Não apliquei: " + resultado.mensagem());
        String nome = resultado.mensagem().replaceFirst("^salvo, entrega falhou: ", "");
        if (!resultado.arquivo()) return ResultadoFerramenta.texto(sucesso.apply(nome)
                + " Salvo, mas a entrega do arquivo falhou.");
        var arquivo = arquivos.obter(comando.id());
        if (arquivo.isEmpty()) return ResultadoFerramenta.texto(sucesso.apply(nome)
                + " Salvo, mas a entrega do arquivo falhou.");
        String aviso = "";
        if ("telegram".equals(contexto.canal()) && contexto.usuarioAutenticado() > 0)
            try { telegram.enviarDocumento(contexto.usuarioAutenticado(),
                    Files.readAllBytes(arquivo.get()), nome); }
            catch (Exception e) { aviso = " Entrega pelo Telegram falhou; consulte o anexo na HUD."; }
        return new ResultadoFerramenta(sucesso.apply(nome) + aviso,
                List.of(new ResultadoFerramenta.Artefato(arquivo.get(), nome, mime(nome))),
                List.of(), null, List.of(), List.of());
    }

    public Optional<String> ultimaEdicaoConcluida(long usuario) {
        UltimaEdicao ultima = ultimasEdicoes.get(usuario);
        if (ultima == null) return Optional.empty();
        var estado = operacoes.estado(ultima.comandoId()).orElse(null);
        return estado != null && "concluído".equals(estado.estado())
                ? Optional.of(ultima.editId()) : Optional.empty();
    }

    private ComandoPc novo(String acao, JsonNode dados) {
        return new ComandoPc(UUID.randomUUID().toString(), acao, "", Instant.now().toString(),
                dados == null ? json.createObjectNode() : dados.deepCopy());
    }
    private static String mime(String nome) {
        if (nome.endsWith(".json")) return "application/json";
        if (nome.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return nome.endsWith(".md") ? "text/markdown" : "text/plain";
    }
}
