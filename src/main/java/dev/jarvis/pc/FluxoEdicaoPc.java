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
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.TipoDeAcao;
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
    /** Pode ser nulo: os testes antigos montam este serviço sem o diário. */
    private final DiarioDeAcoes diario;
    private record UltimaEdicao(String editId, String comandoId) { }
    private final ConcurrentHashMap<Long, UltimaEdicao> ultimasEdicoes = new ConcurrentHashMap<>();

    public FluxoEdicaoPc(OperacoesPcService operacoes, ArquivosPcService arquivos,
            TelegramClient telegram, JsonMapper json) {
        this(operacoes, arquivos, telegram, json, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FluxoEdicaoPc(OperacoesPcService operacoes, ArquivosPcService arquivos,
            TelegramClient telegram, JsonMapper json, DiarioDeAcoes diario) {
        this.operacoes = operacoes; this.arquivos = arquivos;
        this.telegram = telegram; this.json = json; this.diario = diario;
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
        // Daqui para baixo o arquivo já mudou no disco do PC, e os três retornos abaixo só diferem
        // na entrega do anexo. É aqui, então, que a ação entra no diário — uma vez, e com o nome
        // que o agente devolveu, nunca o que o modelo escreveu.
        anotarNoDiario(acao, nome, contexto);
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

    /**
     * Anota a edição no diário de ações: ela é feita <b>sem perguntar</b> (o agente guarda backup
     * protegido de cada versão), então o registro é o único jeito de o dono saber depois.
     *
     * A cópia entra como edição também — ela cria um arquivo no PC do dono do mesmo jeito.
     */
    private void anotarNoDiario(String acao, String nome, OuvinteProgresso contexto) {
        if (diario == null) return;
        TipoDeAcao tipo = "aplicar_desfazer".equals(acao)
                ? TipoDeAcao.DESFAZER_EDICAO : TipoDeAcao.EDICAO_ARQUIVO;
        String descricao = tipo == TipoDeAcao.DESFAZER_EDICAO
                ? "voltei " + nome + " para a versão anterior" : "editei " + nome;
        diario.registrarDoCanal(tipo, descricao, contexto.canal());
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
