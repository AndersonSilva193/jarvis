package dev.jarvis.ia;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import dev.jarvis.conversa.*;
import dev.jarvis.custo.Origem;
import dev.jarvis.ferramentas.ResultadoFerramenta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.jarvis.ferramentas.Ferramentas;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.aprovacao.ClassificadorDeResposta;
import dev.jarvis.aprovacao.ClassificadorDeResposta.Decisao;
import dev.jarvis.memoria.AnotadorDeMemoria;
import dev.jarvis.memoria.EsquecimentosDeMemoria;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O "cérebro": recebe o pedido, conversa com o Claude e executa as ferramentas que ele pedir,
 * repetindo até o modelo dar a resposta final (o chamado "loop de agente").
 */
@Service
public class Assistente {

    /** Limite de idas e vindas com ferramentas por pedido, para nunca entrar em loop infinito. */
    private static final int MAX_PASSOS = 8;
    /** A partir desta chamada idêntica no mesmo pedido, a ferramenta é recusada sem executar. */
    private static final int LIMITE_REPETICAO = 3;
    /**
     * O limite folgado. Consulta de estado ("já terminou?") e ação em passos (volume, avançar) são
     * repetidas de propósito: aí o modelo não está em círculo, está contando.
     */
    private static final int LIMITE_REPETICAO_FOLGADO = 5;
    /** As de limite folgado que o prefixo {@code status_} não pega: as duas de ação em passos. */
    private static final List<String> REPETIVEIS = List.of("controlar_tela", "controlar_pc");
    /** Recusas por repetição que encerram o turno: insistir depois da primeira é andar em círculo. */
    private static final int BLOQUEIOS_PARA_PARAR = 2;
    /**
     * Quantas trocas (pedido + resposta) o Jarvis lembra por conversa. Também é o teto do que volta
     * do disco na partida: o arquivo pode ter sido gravado por uma versão que lembrava mais.
     */
    private static final int MAX_TURNOS_HISTORICO = 10;
    /** A resposta de um turno cortado pelo usuário. Curta de propósito: ele pediu para parar. */
    private static final String PAREI = "Parei, senhor.";

    private static final Logger log = LoggerFactory.getLogger(Assistente.class);

    private final ClaudeClient claude;
    private final Ferramentas ferramentas;
    private final SistemaDoJarvis sistema;
    private final JsonMapper json;
    private final RegistroConversas conversas;
    private final AnexosService anexos;
    private final BuscaWebServidor buscaWeb;
    private final Aprovacoes aprovacoes;
    private final ClassificadorDeResposta classificador;
    private final HistoricoEmArquivo arquivo;
    /** Quem encolhe os turnos antigos: sem ele o histórico reenviaria toda ferramenta de novo. */
    private final CompactadorDeHistorico compactador;
    /** Opcional: nulo nos construtores enxutos, como o resto das peças de borda desta classe. */
    private final AnotadorDeMemoria anotador;
    /** O recado de que o {@code esquecer_memoria} aprovado apagou algo fora deste turno. */
    private final EsquecimentosDeMemoria esquecimentos;
    private final ReentrantLock execucao = new ReentrantLock(true);
    /**
     * O pedido que está sendo atendido agora, ou nulo. Serve a duas perguntas de quem está na borda
     * de entrada: "tem pedido em andamento?" e "marque-o como interrompido". Um campo basta porque
     * o {@code execucao} já garante um pedido por vez.
     */
    private final java.util.concurrent.atomic.AtomicReference<EmAndamento> emAndamento =
            new java.util.concurrent.atomic.AtomicReference<>();

    private record EmAndamento(long chatId, long usuario,
            java.util.concurrent.atomic.AtomicBoolean parado) { }

    /**
     * O corte do turno. Sem pilha nem mensagem supressa: ela não é um erro, é o usuário pedindo
     * para parar, e nunca sai desta classe.
     */
    private static final class PedidoInterrompido extends RuntimeException {
        PedidoInterrompido() {
            super(PAREI, null, false, false);
        }
    }

    /** Há pedido sendo atendido agora. */
    public boolean ocupado() {
        return emAndamento.get() != null;
    }

    /**
     * Marca o pedido em andamento como interrompido: o {@code executarLoop} o encerra no próximo
     * passo, sem executar mais nenhuma ferramenta.
     *
     * <p>Devolve {@code false} quando não há nada a interromper — e é esse {@code false} que faz a
     * borda de entrada tratar "para" como mensagem comum, que pode ser a resposta a uma aprovação
     * pendente. Usuário autenticado diferente do dono do pedido também não interrompe nada.
     */
    public boolean interromperPedidoAtual(long usuario) {
        EmAndamento atual = emAndamento.get();
        if (atual == null) return false;
        if (usuario > 0 && atual.usuario() > 0 && atual.usuario() != usuario) return false;
        atual.parado().set(true);
        log.info("Pedido interrompido a pedido do usuário (chat {})", atual.chatId());
        return true;
    }

    /** Cada turno é a lista de mensagens de um pedido (texto do usuário, chamadas de ferramenta e resposta). */
    private final Map<Long, Deque<List<ObjectNode>>> historicos = new HashMap<>();
    private final Map<Long, Long> usuarioPorChat = new HashMap<>();

    public Assistente(ClaudeClient claude, Ferramentas ferramentas, SistemaDoJarvis sistema, JsonMapper json) {
        this(claude, ferramentas, sistema, json, new RegistroConversas(evento -> { }), new AnexosService(),
                BuscaWebServidor.desligada(), new Aprovacoes());
    }

    public Assistente(ClaudeClient claude, Ferramentas ferramentas, SistemaDoJarvis sistema, JsonMapper json,
            RegistroConversas conversas, AnexosService anexos, BuscaWebServidor buscaWeb) {
        this(claude, ferramentas, sistema, json, conversas, anexos, buscaWeb, new Aprovacoes());
    }

    public Assistente(ClaudeClient claude, Ferramentas ferramentas, SistemaDoJarvis sistema, JsonMapper json,
            RegistroConversas conversas, AnexosService anexos, BuscaWebServidor buscaWeb,
            Aprovacoes aprovacoes) {
        this(claude, ferramentas, sistema, json, conversas, anexos, buscaWeb, aprovacoes,
                HistoricoEmArquivo.desligado());
    }

    public Assistente(ClaudeClient claude, Ferramentas ferramentas, SistemaDoJarvis sistema, JsonMapper json,
            RegistroConversas conversas, AnexosService anexos, BuscaWebServidor buscaWeb,
            Aprovacoes aprovacoes, HistoricoEmArquivo arquivo) {
        this(claude, ferramentas, sistema, json, conversas, anexos, buscaWeb, aprovacoes, arquivo,
                ClassificadorDeResposta.semModelo());
    }

    public Assistente(ClaudeClient claude, Ferramentas ferramentas, SistemaDoJarvis sistema, JsonMapper json,
            RegistroConversas conversas, AnexosService anexos, BuscaWebServidor buscaWeb,
            Aprovacoes aprovacoes, HistoricoEmArquivo arquivo, ClassificadorDeResposta classificador) {
        this(claude, ferramentas, sistema, json, conversas, anexos, buscaWeb, aprovacoes, arquivo,
                classificador, null);
    }

    public Assistente(ClaudeClient claude, Ferramentas ferramentas, SistemaDoJarvis sistema, JsonMapper json,
            RegistroConversas conversas, AnexosService anexos, BuscaWebServidor buscaWeb,
            Aprovacoes aprovacoes, HistoricoEmArquivo arquivo, ClassificadorDeResposta classificador,
            AnotadorDeMemoria anotador) {
        this(claude, ferramentas, sistema, json, conversas, anexos, buscaWeb, aprovacoes, arquivo,
                classificador, anotador, new EsquecimentosDeMemoria());
    }

    /**
     * A aridade de antes do histórico compacto: quem não informa o compactador recebe o padrão
     * (um turno inteiro no fim, resposta cortada em 1500 caracteres). Mesmo idioma do
     * {@code HistoricoEmArquivo.desligado()} logo acima, e pelo mesmo motivo — são dezenas de
     * testes montando esta classe à mão.
     */
    public Assistente(ClaudeClient claude, Ferramentas ferramentas, SistemaDoJarvis sistema, JsonMapper json,
            RegistroConversas conversas, AnexosService anexos, BuscaWebServidor buscaWeb,
            Aprovacoes aprovacoes, HistoricoEmArquivo arquivo, ClassificadorDeResposta classificador,
            AnotadorDeMemoria anotador, EsquecimentosDeMemoria esquecimentos) {
        this(claude, ferramentas, sistema, json, conversas, anexos, buscaWeb, aprovacoes, arquivo,
                classificador, anotador, esquecimentos, CompactadorDeHistorico.padrao(json));
    }

    @Autowired
    public Assistente(ClaudeClient claude, Ferramentas ferramentas, SistemaDoJarvis sistema, JsonMapper json,
            RegistroConversas conversas, AnexosService anexos, BuscaWebServidor buscaWeb,
            Aprovacoes aprovacoes, HistoricoEmArquivo arquivo, ClassificadorDeResposta classificador,
            AnotadorDeMemoria anotador, EsquecimentosDeMemoria esquecimentos,
            CompactadorDeHistorico compactador) {
        this.compactador = compactador;
        this.esquecimentos = esquecimentos;
        this.anotador = anotador;
        this.classificador = classificador;
        this.arquivo = arquivo;
        this.claude = claude;
        this.ferramentas = ferramentas;
        this.sistema = sistema;
        this.json = json;
        this.conversas = conversas;
        this.anexos = anexos;
        this.buscaWeb = buscaWeb;
        this.aprovacoes = aprovacoes;
    }

    /**
     * Devolve à memória o histórico que estava em disco. Roda antes de a primeira mensagem chegar,
     * porque a HUD pode pedir o histórico sem ninguém ter falado nada ainda.
     *
     * Os turnos voltam ao {@code Deque} do chat e as trocas públicas ao {@link RegistroConversas};
     * o {@code usuarioPorChat} volta junto, e é ele que faz "esquecer a memória" depois de um
     * restart ainda alcançar todos os chats daquele usuário.
     */
    @jakarta.annotation.PostConstruct
    public void restaurarHistorico() {
        if (!arquivo.ligado()) return;
        int chats = 0;
        for (HistoricoEmArquivo.Salvo salvo : arquivo.carregarTudo()) {
            if (!salvo.turnos().isEmpty()) {
                Deque<List<ObjectNode>> historico = new ArrayDeque<>(salvo.turnos());
                while (historico.size() > MAX_TURNOS_HISTORICO) historico.removeFirst();
                // Vale a mesma regra de sempre, e é isso que compacta o arquivo gravado por uma
                // versão que guardava o turno inteiro: o último volta completo, os anteriores não.
                compactador.aplicar(historico);
                historicos.put(salvo.chatId(), historico);
            }
            if (salvo.usuario() > 0) usuarioPorChat.put(salvo.chatId(), salvo.usuario());
            conversas.restaurar(salvo.chatId(), salvo.trocas());
            chats++;
        }
        if (chats > 0) log.info("Histórico restaurado de {} conversa(s) em {}", chats, arquivo.pasta());
    }

    /** Espera a última gravação sair antes de o processo morrer. */
    @jakarta.annotation.PreDestroy
    public void fechar() {
        arquivo.fechar();
    }

    /** Responde a uma mensagem vinda do Telegram. */
    public String responder(long chatId, String texto) throws Exception {
        return responder(chatId, texto, Canal.TELEGRAM);
    }

    /** Entrada autenticada pelo poller; update_id é estável em uma repetição da entrega. */
    public String responderTelegram(long chatId, long usuario, long updateId, String texto) throws Exception {
        return responder(chatId, texto, Canal.TELEGRAM, new OuvinteProgresso() {
            @Override public String idPedido() { return "telegram-" + updateId; }
            // Em grupo o remetente pode ser autorizado, mas a resposta seria pública.
            @Override public long usuarioAutenticado() { return chatId == usuario ? usuario : 0; }
        }).texto();
    }

    /** Responde a uma mensagem da conversa, usando e atualizando o histórico daquele chat. */
    public String responder(long chatId, String texto, Canal canal) throws Exception {
        return responder(chatId, texto, canal, null).texto();
    }

    public RespostaConversa responder(long chatId, String texto, Canal canal, OuvinteProgresso ouvinte) throws Exception {
        OuvinteProgresso origem = ouvinte == null ? new OuvinteProgresso() { } : ouvinte;
        java.util.concurrent.atomic.AtomicBoolean apagou = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> apagadoId = new java.util.concurrent.atomic.AtomicReference<>("");
        java.util.concurrent.atomic.AtomicLong apagadoRevisao = new java.util.concurrent.atomic.AtomicLong();
        OuvinteProgresso progresso = new OuvinteProgresso() {
            @Override public String idPedido() { return origem.idPedido(); }
            @Override public long usuarioAutenticado() { return origem.usuarioAutenticado(); }
            @Override public boolean memoriaEsquecida() { return apagou.get(); }
            @Override public void marcarMemoriaEsquecida() { apagou.set(true); }
            @Override public void marcarMemoriaEsquecida(String id, long revisao) {
                apagou.set(true); apagadoId.set(id); apagadoRevisao.set(revisao);
            }
            @Override public String idMemoriaEsquecida() { return apagadoId.get(); }
            @Override public long revisaoMemoriaEsquecida() { return apagadoRevisao.get(); }
            @Override public void iniciado() { origem.iniciado(); }
            @Override public void ferramenta(String nome, String descricao, Integer passo, Integer total) {
                origem.ferramenta(nome, descricao, passo, total);
            }
        };
        String id = progresso.idPedido() == null ? UUID.randomUUID().toString() : progresso.idPedido();
        execucao.lockInterruptibly();
        EmAndamento marca = new EmAndamento(chatId, progresso.usuarioAutenticado(),
                new java.util.concurrent.atomic.AtomicBoolean());
        emAndamento.set(marca);
        try {
            if (canal == Canal.HUD && progresso.idPedido() != null && !conversas.pendente(chatId, id))
                throw new IllegalStateException("Pedido encerrado pela limpeza do histórico");
            OuvinteProgresso.avisar(progresso::iniciado);
            if (progresso.usuarioAutenticado() > 0) usuarioPorChat.put(chatId, progresso.usuarioAutenticado());
            String ultimoAnexo = progresso.usuarioAutenticado() > 0
                    ? ultimoAnexoDeArquivo(conversas.historico(chatId), canal, Instant.now()) : "";
            Deque<List<ObjectNode>> historico = historicos.computeIfAbsent(chatId, chave -> new ArrayDeque<>());
            List<ObjectNode> turno = new ArrayList<>();
            turno.add(mensagemDoUsuario(texto));

            List<ResultadoFerramenta> resultados = new ArrayList<>();
            String resposta;
            boolean interrompido = false;
            var decisaoPc = aprovacoes.tratar(texto, progresso.usuarioAutenticado(), id, canal.nome());
            // Só quando o texto não é um código nem uma frase fixa é que vale classificar: a
            // pergunta pendente é a única coisa que a mensagem pode estar respondendo, e sem
            // pendência em aberto nada disso roda — nem a lista, nem a chamada curta ao modelo.
            String emAberto = decisaoPc.isPresent() ? ""
                    : aprovacoes.pendenciaDe(progresso.usuarioAutenticado(), id).orElse("");
            Decisao decisao = emAberto.isEmpty() ? Decisao.OUTRO_ASSUNTO
                    : classificador.classificar(emAberto, texto);
            String notaDeAjuste = "";
            if (!emAberto.isEmpty() && decisao != Decisao.OUTRO_ASSUNTO) {
                long usuario = progresso.usuarioAutenticado();
                switch (decisao) {
                    case SIM -> decisaoPc = aprovacoes.executarPendente(usuario, id, canal.nome());
                    case NAO -> {
                        aprovacoes.cancelarPendente(usuario, id);
                        decisaoPc = java.util.Optional.of("Certo, não fiz.");
                    }
                    case AJUSTE -> {
                        aprovacoes.cancelarPendente(usuario, id);
                        // A nota vai no texto do usuário, e não numa mensagem de assistente: duas
                        // do assistente seguidas quebram a alternância e a API recusa com 400.
                        notaDeAjuste = "\n\n(nota do servidor: a proposta anterior — " + emAberto
                                + " — foi cancelada porque você pediu outra coisa; trate esta"
                                + " mensagem como um pedido novo.)";
                    }
                    // A pergunta repetida é montada aqui, de dado real: deixá-la ao modelo seria
                    // deixá-lo escolher o que o próximo "sim" vai significar.
                    default -> decisaoPc = java.util.Optional.of("Só para confirmar, senhor: "
                            + emAberto);
                }
            }
            if (!notaDeAjuste.isEmpty()) turno.set(0, mensagemDoUsuario(texto + notaDeAjuste));
            // A aprovação consumida pode ter sido a de esquecer memória, e quem apagou foi o
            // Supplier — que roda com o contexto do turno em que a pendência nasceu. O recado
            // chega por aqui para o apagamento do histórico deste turno acontecer logo abaixo.
            if (decisaoPc.isPresent() && progresso.usuarioAutenticado() > 0) {
                esquecimentos.drenar(progresso.usuarioAutenticado()).ifPresent(
                        e -> progresso.marcarMemoriaEsquecida(e.id(), e.revisao()));
            }
            if (decisaoPc.isPresent()) {
                resposta = decisaoPc.get();
                turno.add(json.createObjectNode().put("role", "assistant").put("content", resposta));
            } else if (ComandoDeRepouso.reconhece(texto)) {
                ObjectNode chamada = json.createObjectNode().put("type", "tool_use")
                        .put("id", "repouso_" + id).put("name", "controlar_pc");
                chamada.putObject("input").put("acao", "modo_repouso");
                ObjectNode resultado = ferramentas.executar(chamada, progresso, resultados::add);
                resposta = Json.texto(resultado.path("content"));
                turno.add(json.createObjectNode().put("role", "assistant").put("content", resposta));
            } else {
                OuvinteProgresso comAnexo = new OuvinteProgresso() {
                    @Override public String idPedido() { return progresso.idPedido(); }
                    @Override public long usuarioAutenticado() { return progresso.usuarioAutenticado(); }
                    @Override public String ultimoAnexoDeArquivo() { return ultimoAnexo; }
                    @Override public void ferramenta(String nome, String descricao, Integer passo, Integer total) {
                        progresso.ferramenta(nome, descricao, passo, total);
                    }
                    @Override public void marcarMemoriaEsquecida() {
                        progresso.marcarMemoriaEsquecida();
                    }
                    @Override public void marcarMemoriaEsquecida(String memoriaId, long revisao) {
                        progresso.marcarMemoriaEsquecida(memoriaId, revisao);
                    }
                    @Override public boolean memoriaEsquecida() { return progresso.memoriaEsquecida(); }
                    @Override public String idMemoriaEsquecida() { return progresso.idMemoriaEsquecida(); }
                    @Override public long revisaoMemoriaEsquecida() { return progresso.revisaoMemoriaEsquecida(); }
                };
                try {
                    resposta = executarLoop(historico, turno, canal, comAnexo, resultados,
                            marca.parado()::get);
                } catch (PedidoInterrompido e) {
                    resposta = PAREI;
                    interrompido = true;
                }
            }
            List<Anexo> publicados = new ArrayList<>();
            Confirmacao confirmacao = null;
            EscutaResposta escuta = null;
            for (ResultadoFerramenta resultado : resultados) {
                for (var artefato : resultado.artefatos()) {
                    try { publicados.add(anexos.registrar(artefato)); }
                    catch (java.io.IOException e) { resposta += "\nNão consegui disponibilizar um dos anexos."; }
                }
                for (var referencia : resultado.referencias())
                    anexos.referencia(referencia.nome(), referencia.url()).ifPresent(publicados::add);
                if (resultado.confirmacao() != null) confirmacao = resultado.confirmacao();
                if (resultado.escuta() != null) escuta = resultado.escuta();
            }
            Instant agora = Instant.now();
            // Nada a sobrescrever aqui: o esquecimento não é mais disparado por um turno do modelo.
            // Quem apaga é o Supplier do Aprovacoes, e a frase que volta ("Esqueci: cidade.") já é
            // escrita em Java — reescrevê-la com id e revisão só devolveria texto de máquina a quem
            // pediu para esquecer falando normalmente.
            if (canal != Canal.HUD) escuta = null;
            else if (confirmacao != null) escuta = escutaDaConfirmacao(confirmacao, agora);
            // O aviso do anotador entra só nesta string: a mensagem do assistente dentro de `turno`
            // fica intacta, então ele nunca chega ao histórico do modelo nem ao prefixo de cache.
            // `fala` guarda a resposta sem o aviso — ele é para ler na tela, não para ouvir.
            String fala = null;
            // Turno cortado não drena o aviso: ele fica na fila para a resposta seguinte, em vez de
            // sair pendurado numa frase de duas palavras.
            if (anotador != null && !progresso.memoriaEsquecida() && !interrompido) {
                String aviso = anotador.drenarAviso(chatId);
                if (!aviso.isEmpty()) {
                    fala = resposta;
                    resposta = resposta + "\n" + aviso;
                }
            }
            RespostaConversa publica = new RespostaConversa(id, canal.nome(), resposta, publicados,
                    confirmacao, agora.toString(), null, escuta, fala);

            // Só guardamos o turno se ele terminou bem, assim o histórico nunca fica "pela metade".
            if (progresso.memoriaEsquecida()) {
                for (var entrada : new ArrayList<>(usuarioPorChat.entrySet())) {
                    if (entrada.getValue() == progresso.usuarioAutenticado()) {
                        historicos.remove(entrada.getKey());
                        conversas.limpar(entrada.getKey());
                        // Apagar do disco aqui, e não num agendamento: o Jarvis só diz "esqueci"
                        // depois de o arquivo ter saído do volume.
                        arquivo.apagar(entrada.getKey());
                    }
                }
                historicos.remove(chatId);
                arquivo.apagar(chatId);
                conversas.concluirSemHistorico(chatId, publica);
            } else if (interrompido) {
                // Mesma regra do turno com erro: nada pela metade no histórico. O pedido público é
                // encerrado para a HUD não ficar esperando, mas o turno não entra no Deque, não vai
                // ao disco e não chega ao anotador.
                conversas.concluirSemHistorico(chatId, publica);
            } else {
                historico.addLast(semPensamentos(turno));
                while (historico.size() > MAX_TURNOS_HISTORICO) historico.removeFirst();
                // Antes de ir ao disco, de propósito: o arquivo guarda o que o modelo vai receber,
                // e não há motivo para gravar um resultado de ferramenta que nunca mais será lido.
                compactador.aplicar(historico);
                conversas.concluir(chatId, texto, publica);
                // Turno concluído, e só agora: nada do que deu erro no meio chega ao disco. A
                // gravação em si sai numa thread virtual, fora do caminho da resposta.
                arquivo.agendar(chatId, usuarioPorChat.getOrDefault(chatId, 0L),
                        new ArrayList<>(historico), conversas.trocas(chatId));
                // O anotador recebe o texto do usuário, nunca a resposta nem saída de ferramenta.
                if (anotador != null) {
                    anotador.registrarTurno(chatId, progresso.usuarioAutenticado(), canal.nome(), texto);
                }
            }
            return publica;
        } finally { emAndamento.compareAndSet(marca, null); execucao.unlock(); }
    }

    static String ultimoAnexoDeArquivo(RegistroConversas.Historico historico, Canal canal, Instant agora) {
        if (historico.trocas().isEmpty()) return "";
        RespostaConversa resposta = historico.trocas().getLast().resposta();
        if (!canal.nome().equals(resposta.canal())) return "";
        try {
            Instant concluido = Instant.parse(resposta.concluidoEm());
            Duration idade = Duration.between(concluido, agora);
            if (idade.isNegative() || idade.compareTo(Duration.ofMinutes(10)) > 0) return "";
        } catch (RuntimeException e) { return ""; }
        List<Anexo> elegiveis = resposta.anexos().stream().filter(a -> "arquivo".equals(a.tipo())
                && a.nome() != null && a.nome().toLowerCase(java.util.Locale.ROOT)
                        .matches(".*\\.(txt|md)")).toList();
        return elegiveis.size() == 1 ? elegiveis.getFirst().nome() : "";
    }

    /**
     * A confirmação determina a janela de escuta, inclusive quando já expirou.
     *
     * <p>Sem opções: a resposta à aprovação é interpretada pelo {@code ClassificadorDeResposta},
     * então qualquer fala vale e restringir a escuta a "confirmo"/"cancelar" só devolveria a
     * palavra exata que a pergunta natural acabou de dispensar.
     */
    static EscutaResposta escutaDaConfirmacao(Confirmacao confirmacao, Instant agora) {
        int segundos = 0;
        if (confirmacao.expiraEm() != null) {
            try {
                segundos = (int) Math.clamp(Duration.between(agora,
                        Instant.parse(confirmacao.expiraEm())).getSeconds(), 0L, 20L);
            } catch (DateTimeParseException ignorada) {
                // Prazo inválido não pode abrir uma janela de confirmação sem validade.
            }
        }
        return new EscutaResposta(segundos, List.of(), confirmacao.pergunta());
    }

    /** Executa uma instrução avulsa sem misturar com o histórico da conversa. */
    public String executarTarefa(String instrucao) throws Exception {
        return executarTarefa(instrucao, Canal.TAREFA);
    }

    /**
     * Executa uma instrução avulsa (ex.: tarefas agendadas, briefing falado, HUD) sem misturar com o
     * histórico da conversa. O canal decide só o formato do texto que volta.
     */
    public String executarTarefa(String instrucao, Canal canal) throws Exception {
        return executarTarefa(instrucao, canal, new OuvinteProgresso() { });
    }

    /** Tarefa de planilha com autorização já registrada pelo servidor. */
    public String executarTarefa(String instrucao, Canal canal, OuvinteProgresso contexto) throws Exception {
        execucao.lockInterruptibly();
        // A tarefa avulsa também publica a marca: senão o "tem pedido em andamento?" da borda de
        // entrada mentiria justamente durante o pedido mais longo.
        EmAndamento marca = new EmAndamento(0, contexto.usuarioAutenticado(),
                new java.util.concurrent.atomic.AtomicBoolean());
        emAndamento.set(marca);
        try {
            List<ObjectNode> turno = new ArrayList<>();
            turno.add(mensagemDoUsuario(instrucao));
            return executarLoop(new ArrayDeque<>(), turno, canal, contexto, new ArrayList<>(),
                    marca.parado()::get);
        } catch (PedidoInterrompido e) {
            return PAREI;
        } finally { emAndamento.compareAndSet(marca, null); execucao.unlock(); }
    }

    public void limparHistorico(long chatId) {
        execucao.lock();
        try {
            historicos.remove(chatId);
            conversas.limpar(chatId);
            arquivo.apagar(chatId);
        } finally { execucao.unlock(); }
    }

    private String executarLoop(Deque<List<ObjectNode>> historico, List<ObjectNode> turno, Canal canal,
            OuvinteProgresso progresso, List<ResultadoFerramenta> detalhados,
            java.util.function.BooleanSupplier parado)
            throws Exception {
        // Montado UMA vez por pedido, de propósito. O bloco dinâmico tem a hora: remontá-lo a cada
        // ida e vinda de ferramenta mudaria o system no meio do turno, e o modelo rejeita (HTTP 400)
        // os blocos de pensamento que ele devolveu sob o system anterior. É aqui, então, que o
        // resumo da memória é decidido: com o usuário do pedido e a mensagem dele, uma vez só.
        ArrayNode system = sistema.blocos(canal, progresso.usuarioAutenticado(),
                Json.texto(turno.getFirst().path("content")));
        // As ferramentas também: elas são renderizadas antes do system e entram no mesmo prefixo
        // do cache de prompt. A da busca na web é server-side — quem executa é a Anthropic.
        ArrayNode definicoes = ferramentas.definicoes(canal, !progresso.idTarefaPlanilha().isBlank());
        if (buscaWeb.ativa()) {
            definicoes.add(buscaWeb.definicao());
        }
        int buscas = 0;
        java.util.concurrent.atomic.AtomicInteger restante = new java.util.concurrent.atomic.AtomicInteger(4_000);
        java.util.concurrent.atomic.AtomicBoolean esqueceu = new java.util.concurrent.atomic.AtomicBoolean();
        Map<String, String> evidencias = new HashMap<>();
        String ultimoErroDeArquivo = "";
        String ultimaFalhaDeArquivo = "";
        String falhaEdicao = "";
        // A conta das repetições vale pelo turno inteiro, não por passo: o modelo repete a mesma
        // consulta de uma ida e vinda para a outra, que é justamente onde o teto de passos não
        // ajuda — ele só percebe depois de oito chamadas pagas ao Claude.
        Map<String, Integer> chamadasPorAssinatura = new HashMap<>();
        int bloqueios = 0;
        String ultimoResultadoUtil = "";

        for (int passo = 0; passo < MAX_PASSOS; passo++) {
            // A conferência de cada passo: o usuário pediu para parar, e o passo que vem custaria
            // mais uma chamada paga ao modelo para um turno que não vai ser usado.
            if (parado.getAsBoolean()) throw new PedidoInterrompido();
            ArrayNode mensagens = json.createArrayNode();
            int caracteresDoHistorico = 0;
            for (List<ObjectNode> turnoAnterior : historico) {
                for (ObjectNode m : turnoAnterior) {
                    mensagens.add(m);
                    if (passo == 0) caracteresDoHistorico += m.toString().length();
                }
            }
            for (ObjectNode m : turno) {
                mensagens.add(m);
            }
            // Uma vez por pedido, e só com histórico: é o número que se compara antes e depois da
            // compactação. O par dele é o "Claude: entrada=..." do ClaudeClient, que é em tokens —
            // um diz o que foi montado aqui, o outro o que a API cobrou.
            if (passo == 0 && !historico.isEmpty()) {
                log.info("Histórico enviado: {} turno(s), {} caracteres",
                        historico.size(), caracteresDoHistorico);
            }

            JsonNode resposta = claude.criarMensagem(Origem.CONVERSA, system, mensagens, definicoes);
            buscas += (int) Json.numero(resposta.path("usage").path("server_tool_use")
                    .path("web_search_requests"));
            JsonNode conteudo = resposta.path("content");

            ObjectNode doAssistente = json.createObjectNode();
            doAssistente.put("role", "assistant");
            if (conteudo.isEmpty()) {
                // A API não aceita mensagem vazia no histórico.
                doAssistente.put("content", "(sem resposta)");
            } else {
                doAssistente.set("content", conteudo);
            }
            turno.add(doAssistente);

            String motivo = Json.texto(resposta.path("stop_reason"));
            // A API pausa turno longo de busca. Continuar é reenviar a mensagem do assistente
            // inalterada — ela já foi anexada crua ao turno logo acima.
            if ("pause_turn".equals(motivo)) {
                continue;
            }
            if (!"tool_use".equals(motivo)) {
                registrarBuscas(buscas);
                return extrairTexto(conteudo);
            }

            // O modelo pediu uma ou mais ferramentas: executamos e devolvemos os resultados.
            ArrayNode resultados = json.createArrayNode();
            String erroRepetidoDeArquivo = "";
            boolean substitutoRecusado = false;
            boolean pesquisaFeita = buscas > 0;
            for (int i = 0; i < conteudo.size(); i++) {
                JsonNode bloco = conteudo.get(i);
                if ("tool_use".equals(Json.texto(bloco.path("type")))) {
                    String nomeFerramenta = Json.texto(bloco.path("name"));
                    if (!progresso.idTarefaPlanilha().isBlank() && !List.of("listar_arquivos",
                            "ler_arquivo", "editar_arquivo", "verificar_site", "clima")
                            .contains(nomeFerramenta)) {
                        resultados.add(json.createObjectNode().put("type", "tool_result")
                                .put("tool_use_id", Json.texto(bloco.path("id")))
                                .put("content", "Ferramenta indisponível nesta tarefa de planilha.")
                                .put("is_error", true));
                        continue;
                    }
                    if ("criar_arquivo".equals(nomeFerramenta) && !falhaEdicao.isBlank()) {
                        resultados.add(json.createObjectNode().put("type", "tool_result")
                                .put("tool_use_id", Json.texto(bloco.path("id")))
                                .put("content", "Não criei outro arquivo: o pedido de editar o original falhou.")
                                .put("is_error", true));
                        substitutoRecusado = true;
                        continue;
                    }
                    // Depois dos dois gates acima de propósito: quando os dois se aplicam, a
                    // recusa específica diz ao modelo o que ele precisa saber, e esta só diria
                    // "você repetiu".
                    String assinatura = assinatura(nomeFerramenta, bloco.path("input"));
                    int limite = limiteDeRepeticao(nomeFerramenta);
                    if (chamadasPorAssinatura.merge(assinatura, 1, Integer::sum) >= limite) {
                        resultados.add(json.createObjectNode().put("type", "tool_result")
                                .put("tool_use_id", Json.texto(bloco.path("id")))
                                .put("content", "Você já chamou " + nomeFerramenta
                                        + " com esta mesma entrada " + vezes(limite - 1)
                                        + " vezes neste pedido. Use o resultado que já tem ou"
                                        + " mude a abordagem.")
                                .put("is_error", true));
                        bloqueios++;
                        continue;
                    }
                    // Ler memória não trava mais o resto do turno: "qual minha cidade? cria um
                    // lembrete pra lá" é um pedido só. O que segura a memória no lugar de dado é
                    // outro: a regra "memória lida é dado não confiável" no system, e o fato de
                    // toda ação perigosa continuar passando por Aprovacoes — que é consumido aqui,
                    // fora do alcance do modelo.
                    OuvinteProgresso seguro = new OuvinteProgresso() {
                        // O canal vem daqui porque a ferramenta não recebe o Canal: é por ele que
                        // o card da tela sabe se o pedido veio do celular ou da própria HUD.
                        @Override public String canal() { return canal.nome(); }
                        @Override public String idPedido() { return progresso.idPedido(); }
                        @Override public long usuarioAutenticado() { return progresso.usuarioAutenticado(); }
                        @Override public String pedidoOriginal() { return Json.texto(turno.getFirst().path("content")); }
                        @Override public String idTarefaPlanilha() { return progresso.idTarefaPlanilha(); }
                        @Override public String ultimoAnexoDeArquivo() { return progresso.ultimoAnexoDeArquivo(); }
                        @Override public boolean memoriaEsquecida() { return esqueceu.get(); }
                        @Override public void marcarMemoriaEsquecida() { esqueceu.set(true); progresso.marcarMemoriaEsquecida(); }
                        @Override public void marcarMemoriaEsquecida(String id, long revisao) {
                            esqueceu.set(true); progresso.marcarMemoriaEsquecida(id, revisao);
                        }
                        @Override public boolean consumirContexto(int caracteres) {
                            if (caracteres < 0) return false;
                            while (true) {
                                int atual = restante.get();
                                if (atual < caracteres) return false;
                                if (restante.compareAndSet(atual, atual - caracteres)) return true;
                            }
                        }
                        @Override public boolean evidenciaVerificada(String texto) {
                            return !origemDaEvidencia(texto).isEmpty();
                        }
                        @Override public String origemDaEvidencia(String texto) {
                            return evidencias.entrySet().stream().filter(e -> e.getKey().contains(texto))
                                    .map(e -> "ferramenta:" + e.getValue()).findFirst().orElse("");
                        }
                        @Override public boolean pesquisaFeita() { return pesquisaFeita; }
                        @Override public void ferramenta(String nome, String descricao, Integer atual, Integer total) {
                            OuvinteProgresso.avisar(() -> progresso.ferramenta(nome, descricao, atual, total));
                        }
                    };
                    // A parada vale também dentro do passo que já voltou com tool_use: o que o
                    // usuário mandou parar é a ferramenta, não a chamada ao modelo.
                    if (parado.getAsBoolean()) throw new PedidoInterrompido();
                    int anteriores = detalhados.size();
                    ObjectNode resultado = ferramentas.executar(bloco, seguro, detalhados::add);
                    if ("editar_arquivo".equals(nomeFerramenta)) {
                        boolean preparada = detalhados.size() > anteriores
                                && (detalhados.getLast().confirmacao() != null
                                        || !detalhados.getLast().artefatos().isEmpty());
                        falhaEdicao = preparada ? "" : Json.texto(resultado.path("content"));
                    }
                    if (List.of("verificar_site", "status_servidor", "clima").contains(Json.texto(bloco.path("name")))
                            && !resultado.path("is_error").asBoolean(false))
                        evidencias.put(Json.texto(resultado.path("content")), nomeFerramenta);
                    resultados.add(resultado);
                    // O que vai na frase de desistência, se o modelo insistir na repetição.
                    // "Útil" aqui é "executou e não estourou": falha de negócio volta sem
                    // is_error, que a Ferramentas só marca em exceção ou ferramenta desconhecida.
                    if (!resultado.path("is_error").asBoolean(false)) {
                        String util = Json.texto(resultado.path("content"));
                        if (!util.isBlank()) ultimoResultadoUtil = util;
                    }
                    if ("criar_arquivo".equals(nomeFerramenta)
                            || "criar_planilha_google".equals(nomeFerramenta)) {
                        String textoResultado = Json.texto(resultado.path("content"));
                        ultimoErroDeArquivo = textoResultado.startsWith("Arquivo criado:")
                                || textoResultado.startsWith("Planilha criada:") ? ""
                                : textoResultado.substring(0, Math.min(240, textoResultado.length()));
                        if (!ultimoErroDeArquivo.isBlank()) {
                            // A mesma assinatura do portão acima, mais o erro: aqui basta a
                            // segunda tentativa, e o motivo real vale mais que "você repetiu".
                            String chave = assinatura + '\u0000' + ultimoErroDeArquivo;
                            if (chave.equals(ultimaFalhaDeArquivo)) erroRepetidoDeArquivo = ultimoErroDeArquivo;
                            ultimaFalhaDeArquivo = chave;
                        } else ultimaFalhaDeArquivo = "";
                    }
                }
            }
            // Os blocos server_tool_use e web_search_tool_result já vêm resolvidos pelo servidor da
            // Anthropic e não passam pelo registro de ferramentas. Se o turno só tinha desses, não
            // há resultado para devolver — e mensagem de content vazio a API recusa.
            if (resultados.isEmpty()) {
                registrarBuscas(buscas);
                return extrairTexto(conteudo);
            }
            ObjectNode comResultados = json.createObjectNode();
            comResultados.put("role", "user");
            comResultados.set("content", resultados);
            turno.add(comResultados);
            if (esqueceu.get()) return "Memória esquecida.";
            if (substitutoRecusado) return "Não alterei o arquivo original e não criei um substituto. "
                    + falhaEdicao.substring(0, Math.min(240, falhaEdicao.length()));
            // Repetir a mesma entrada recusada desperdiça chamadas do modelo e acabava no erro
            // genérico da HUD. O turno já tem o tool_result, então o histórico continua válido.
            if (!erroRepetidoDeArquivo.isBlank()) return erroRepetidoDeArquivo;
            // Por último: as saídas acima sabem dizer o que deu errado, e esta só sabe que o
            // modelo estava andando em círculo. O turno já tem os tool_result das recusas, então
            // o histórico continua válido.
            if (bloqueios >= BLOQUEIOS_PARA_PARAR) {
                log.warn("Turno encerrado: {} chamada(s) repetida(s) recusada(s)", bloqueios);
                return ("Parei porque estava repetindo a mesma consulta sem avançar. "
                        + ultimoResultadoUtil.substring(0,
                                Math.min(240, ultimoResultadoUtil.length()))).trim();
            }
        }
        registrarBuscas(buscas);
        throw new IllegalStateException("precisei de passos demais para resolver isso. "
                + (ultimoErroDeArquivo.isBlank() ? "Tente um pedido mais simples." : ultimoErroDeArquivo));
    }

    /**
     * Quantas buscas na web o pedido gastou. Cada uma é cobrada à parte dos tokens, então bater no
     * teto merece aviso: é o sinal de pedido caro, ou de pergunta que não precisava de busca.
     */
    private void registrarBuscas(int buscas) {
        if (buscas <= 0) {
            return;
        }
        if (buscaWeb.maxUsos() > 0 && buscas >= buscaWeb.maxUsos()) {
            log.warn("Busca na web: {} buscas neste pedido, o teto (BUSCA_WEB_MAX_USOS)", buscas);
        } else {
            log.info("Busca na web: {} busca(s) neste pedido", buscas);
        }
    }

    /**
     * Copia o turno sem os blocos de pensamento ("thinking") do modelo, que é a forma que guardamos
     * no histórico.
     *
     * <p>Cada bloco desses vem assinado e preso ao prefixo exato que o produziu — system, ferramentas
     * e mensagens anteriores. Como o nosso system carrega a hora e o histórico tem janela deslizante
     * (os turnos mais velhos caem fora), esse prefixo muda entre um pedido e outro, e a API recusa o
     * bloco com HTTP 400 ("block is bound to a different conversation"). Dentro do turno em curso eles
     * continuam intactos, que é o que o laço de ferramentas exige; o que sai é sempre tudo que é
     * anterior, nunca um pedaço do meio.
     *
     * <p>Os blocos de ferramenta e de busca ficam, e é o {@link CompactadorDeHistorico} que os tira
     * quando o turno sai da janela de turnos completos. A diferença está em tirar <b>aos pares</b>:
     * mexer no {@code encrypted_content} de um {@code web_search_tool_result}, ou deixar o
     * {@code server_tool_use} sem ele, é HTTP 400; levar os dois juntos não é, porque não sobra
     * nada referenciando nada.
     */
    private List<ObjectNode> semPensamentos(List<ObjectNode> turno) {
        List<ObjectNode> limpo = new ArrayList<>(turno.size());
        for (ObjectNode mensagem : turno) {
            JsonNode conteudo = mensagem.path("content");
            if (!conteudo.isArray()) {
                limpo.add(mensagem);
                continue;
            }
            ArrayNode mantidos = json.createArrayNode();
            for (int i = 0; i < conteudo.size(); i++) {
                JsonNode bloco = conteudo.get(i);
                String tipo = Json.texto(bloco.path("type"));
                // Só o pensamento sai. Os blocos de busca (server_tool_use, web_search_tool_result)
                // ficam: o encrypted_content deles é o que a API decifra nos turnos seguintes, e
                // mexer nele — ou deixá-lo de fora — é HTTP 400.
                if (!"thinking".equals(tipo) && !"redacted_thinking".equals(tipo)) {
                    mantidos.add(bloco);
                }
            }
            ObjectNode copia = mensagem.deepCopy();
            // Mensagem sem conteúdo nenhum a API recusa; sobra o texto de marcação.
            if (mantidos.isEmpty()) {
                copia.put("content", "(sem resposta)");
            } else {
                copia.set("content", mantidos);
            }
            limpo.add(copia);
        }
        return limpo;
    }

    /** Quantas chamadas idênticas a ferramenta aguenta antes de a próxima ser recusada. */
    private static int limiteDeRepeticao(String nome) {
        return nome.startsWith("status_") || REPETIVEIS.contains(nome)
                ? LIMITE_REPETICAO_FOLGADO : LIMITE_REPETICAO;
    }

    /** A mesma chamada é o mesmo nome com a mesma entrada, seja qual for a ordem das chaves. */
    private static String assinatura(String nome, JsonNode entrada) {
        return nome + '\u0000' + Json.canonico(entrada);
    }

    /**
     * O número da recusa por extenso. O texto da recusa é fixo, mas a contagem acompanha o limite
     * da ferramenta: dizer "duas vezes" na quinta chamada de um {@code status_} seria o servidor
     * contando errado para o modelo.
     */
    private static String vezes(int quantas) {
        return switch (quantas) {
            case 2 -> "duas";
            case 4 -> "quatro";
            default -> String.valueOf(quantas);
        };
    }

    private ObjectNode mensagemDoUsuario(String texto) {
        ObjectNode m = json.createObjectNode();
        m.put("role", "user");
        m.put("content", texto);
        return m;
    }

    private String extrairTexto(JsonNode conteudo) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conteudo.size(); i++) {
            JsonNode bloco = conteudo.get(i);
            if ("text".equals(Json.texto(bloco.path("type")))) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(Json.texto(bloco.path("text")));
            }
        }
        return sb.isEmpty() ? "(sem resposta)" : sb.toString().trim();
    }

}
