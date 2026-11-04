package dev.jarvis.tarefas;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;
import dev.jarvis.agente.Freio;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.autonomia.Concessoes;
import dev.jarvis.autonomia.Nivel;
import dev.jarvis.autonomia.PoliticaDeAutonomia;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.*;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.OrigemDaAcao;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.ferramentas.*;
import dev.jarvis.ia.*;
import dev.jarvis.ia.provedor.Mensagem;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.proativo.AvisosProativos;
import dev.jarvis.telegram.TelegramClient;
import dev.jarvis.tela.PresencaDaHud;
import tools.jackson.databind.json.JsonMapper;
import static dev.jarvis.tarefas.TarefaDeFundo.Status.*;

/** Fila independente da conversa. Nunca chama Assistente nem toma seu lock. */
@Service
public class TarefasDeFundo {
    public static final String COMECO = "Começando. Te aviso quando terminar.";
    private final Object trava = new Object();
    private final LinkedHashMap<String, TarefaDeFundo> tarefas = new LinkedHashMap<>();
    private final CicloDeFerramentas ciclo;
    private final JarvisProperties props;
    private final Freio freio;
    private final PoliticaDeAutonomia politica;
    private final Concessoes concessoes;
    private final Aprovacoes aprovacoes;
    private final DiarioDeAcoes diario;
    private final TelegramClient telegram;
    private final RegistroConversas conversas;
    private final AnexosService anexos;
    private final PresencaDaHud hud;
    private final AvisosProativos avisos;
    private final ResultadosTardios tardios;
    private final EsperasDaTarefa esperas;
    private final Clock relogio;
    private final TarefasEmArquivo arquivo;
    private final ExecutorService executor;
    private long revisao;
    private Instant ultimoCheckpoint = Instant.MIN;
    private volatile boolean fechando;
    private final java.util.concurrent.atomic.AtomicBoolean varrendo = new java.util.concurrent.atomic.AtomicBoolean();

    @Autowired
    public TarefasDeFundo(ProvedorDeModelo modelo, Ferramentas ferramentas, SistemaDoJarvis sistema,
            BuscaWebServidor busca, JsonMapper json, JarvisProperties props, Freio freio,
            PoliticaDeAutonomia politica, Concessoes concessoes, Aprovacoes aprovacoes,
            DiarioDeAcoes diario, TelegramClient telegram, RegistroConversas conversas,
            AnexosService anexos, PresencaDaHud hud, AvisosProativos avisos,
            ResultadosTardios tardios, EsperasDaTarefa esperas) {
        this(new CicloDeFerramentas(modelo, ferramentas, sistema, busca, json), props, freio,
                politica, concessoes, aprovacoes, diario, telegram, conversas, anexos, hud,
                avisos, tardios, esperas, json, Clock.systemUTC(), props.pastaDoDiario().resolve("tarefas.json"));
    }

    public TarefasDeFundo(CicloDeFerramentas ciclo, JarvisProperties props, Freio freio,
            PoliticaDeAutonomia politica, Concessoes concessoes, Aprovacoes aprovacoes,
            DiarioDeAcoes diario, TelegramClient telegram, RegistroConversas conversas,
            AnexosService anexos, PresencaDaHud hud, AvisosProativos avisos, ResultadosTardios tardios,
            EsperasDaTarefa esperas, JsonMapper json, Clock relogio, Path caminho) {
        this.ciclo = ciclo; this.props = props; this.freio = freio; this.politica = politica;
        this.concessoes = concessoes; this.aprovacoes = aprovacoes; this.diario = diario;
        this.telegram = telegram; this.conversas = conversas; this.anexos = anexos;
        this.hud = hud; this.avisos = avisos; this.tardios = tardios; this.esperas = esperas;
        this.relogio = relogio; arquivo = new TarefasEmArquivo(caminho, json);
        executor = Executors.newFixedThreadPool(props.tarefasSimultaneas(),
                Thread.ofVirtual().name("tarefa-de-fundo-", 0).factory());
        for (var t : arquivo.carregar()) {
            if (!t.encerrada()) {
                t.retomada = true;
                if (t.status == RODANDO) t.status = NA_FILA;
                // Suppliers não sobrevivem ao processo; uma pergunta será reconstruída, nunca aprovada.
                if (t.status == ESPERANDO_VOCE || t.esperaTipo.equals("APROVACAO")) {
                    t.esperaTipo = "";
                    if (t.status != PAUSADA) t.status = NA_FILA;
                }
            }
            tarefas.put(t.id, t);
        }
        freio.aoPausar(this::pausarTodas);
        freio.aoRetomar(this::varrer);
    }

    public ResultadoFerramenta iniciar(String objetivo, List<String> plano,
            List<TarefaDeFundo.AcaoPrevista> acoes, long usuario, Canal canal, long chat,
            TarefaDeFundo.Origem origem) {
        if (fechando || usuario <= 0 || chat <= 0 || (canal != Canal.HUD && canal != Canal.TELEGRAM))
            return ResultadoFerramenta.imediata("Pedido autenticado de conversa necessário.", null);
        String problema = objetivo == null || objetivo.isBlank() ? "objetivo vazio"
                : objetivo.length() > 4000 ? "objetivo acima de 4000 caracteres"
                : plano == null || plano.isEmpty() ? "plano vazio (mande uma lista de passos)"
                : plano.size() > 8 ? "plano com mais de 8 passos"
                : plano.stream().anyMatch(p -> p == null || p.isBlank()) ? "passo do plano vazio"
                : plano.stream().anyMatch(p -> p.length() > 300) ? "passo do plano acima de 300 caracteres"
                : acoes == null || acoes.size() > 20 ? "mais de 20 ações previstas" : null;
        if (problema != null)
            return ResultadoFerramenta.imediata("Nada foi iniciado: " + problema + ". Corrija e chame de novo.", null);
        for (var a : acoes) if (politica.nivel(a.acao(), a.alvo()) == Nivel.PROIBIDO)
            return ResultadoFerramenta.imediata("Não posso " + a.acao().oQue(a.alvo())
                    + ": a configuração proíbe. Não iniciei a tarefa.", null);
        TarefaDeFundo t;
        synchronized (trava) {
            String id;
            do { id = UUID.randomUUID().toString().substring(0, 8); } while (tarefas.containsKey(id));
            t = new TarefaDeFundo(id, usuario, canal, chat, objetivo.strip(), plano, acoes, origem, relogio.instant());
            t.planoAprovado = acoes.stream().noneMatch(a -> politica.nivel(a.acao(), a.alvo()) == Nivel.PERGUNTA);
            t.status = t.planoAprovado ? NA_FILA : AGUARDANDO_APROVACAO;
            tarefas.put(id, t);
        }
        try { salvar(); }
        catch (RuntimeException e) {
            synchronized (trava) { tarefas.remove(t.id); }
            return ResultadoFerramenta.imediata("Não consegui gravar a tarefa; nada foi iniciado.", null);
        }
        if (!t.planoAprovado) {
            var dentroDoPedido = new java.util.concurrent.atomic.AtomicBoolean(true);
            prepararAprovacao(t, texto -> { if (!dentroDoPedido.get()) notificar(t, texto, List.of()); });
            aprovacoes.processarFilaDeTarefas();
            dentroDoPedido.set(false);
            var confirmacao = t.aprovacaoInicial.confirmacao();
            return ResultadoFerramenta.imediata(confirmacao == null
                    ? "A tarefa está aguardando a vez para pedir sua aprovação." : confirmacao.pergunta(), confirmacao);
        }
        varrer();
        return ResultadoFerramenta.imediata(COMECO, null);
    }

    private void prepararAprovacao(TarefaDeFundo t, java.util.function.Consumer<String> apresentar) {
        String acoes = String.join(" e ", t.acoes.stream().map(a -> a.acao().oQue(a.alvo())).toList());
        String pergunta = "Vou executar em segundo plano: " + t.objetivo + ". Inclui " + acoes + ". Posso?";
        boolean critica = t.acoes.stream().anyMatch(a -> a.chave().equals("pc.desligar") || a.chave().equals("pc.reiniciar"));
        t.aprovacaoInicial = aprovacoes.adiarParaTarefa(t.id, t.usuario, "plano_de_fundo", t.objetivo,
                pergunta, critica ? Aprovacoes.Risco.CRITICO : Aprovacoes.Risco.COMUM,
                "tarefa", null, null, Aprovacoes.frase(() -> {
                    try {
                        for (var a : t.acoes) {
                            if (politica.nivel(a.acao(), a.alvo()) == Nivel.PROIBIDO)
                                throw new ControleDaTarefa("A configuração passou a proibir " + a.acao().oQue(a.alvo()));
                        }
                        for (var a : t.acoes) concessoes.concederPlano(a.acao(), a.alvo(), t.id,
                                pergunta, t.usuario, "tarefa");
                        synchronized (trava) { t.planoAprovado = true; }
                        salvar();
                        return COMECO;
                    } catch (IOException e) { throw new IllegalStateException("Não consegui gravar as concessões do plano", e); }
                }), apresentar);
    }

    @Scheduled(fixedDelay = 250)
    public void varrer() {
        if (fechando || !varrendo.compareAndSet(false, true)) return;
        try {
            aprovacoes.processarFilaDeTarefas();
            boolean liberado = freio.podeAgirSozinho("tarefa");
            List<TarefaDeFundo> lista;
            synchronized (trava) { lista = List.copyOf(tarefas.values()); }
            for (var t : lista) {
                if (t.encerrada() || t.agendada) {
                    Thread executora = t.executora;
                    if (executora != null && t.tempoAtivo(relogio.instant()) >= prazoMillis()) {
                        t.falhaOrcamento = "Limite de " + props.tarefaMaxMinutos() + " minutos ativos atingido.";
                        executora.interrupt();
                    }
                    continue;
                }
                if (t.cancelar) { finalizar(t, CANCELADA, "Cancelada pelo usuário."); continue; }
                if (!liberado) { mudar(t, PAUSADA); continue; }
                t.pausar = false;
                if (!t.planoAprovado) {
                    if (t.aprovacaoInicial == null) prepararAprovacao(t, texto -> notificar(t, texto, List.of()));
                    if (!t.aprovacaoInicial.resolvida()) { mudar(t, AGUARDANDO_APROVACAO); continue; }
                    if (!t.aprovacaoInicial.aprovada()) { finalizar(t, CANCELADA, "Aprovação recusada, cancelada ou expirada."); continue; }
                }
                if (t.espera == null && !t.esperaTipo.isBlank()) {
                    try { t.espera = esperas.criar(SuspensaoDaTarefa.Tipo.valueOf(t.esperaTipo), t.esperaAlvo, t.retomarEm, t.usuario); }
                    catch (IllegalArgumentException e) {
                        if (!t.esperaTipo.equals("OPERACAO_PC")) { finalizar(t, FALHOU, e.getMessage()); continue; }
                        synchronized (trava) {
                            t.resumo.add("Operação " + t.esperaAlvo + " sem rastreio; confira o estado antes de agir.");
                            t.esperaTipo = ""; t.esperaAlvo = "";
                        }
                    }
                }
                if (t.retomada && !t.comandos.isEmpty()) {
                    boolean esperandoComando = false;
                    for (var comando : java.util.Map.copyOf(t.comandos).entrySet()) {
                        String resultado = esperas.reconciliar(comando.getKey());
                        if ("".equals(resultado)) { esperandoComando = true; continue; }
                        synchronized (trava) {
                            t.resumo.add("Operação " + comando.getKey() + " (" + comando.getValue() + "): "
                                    + (resultado == null ? "sem rastreio após reinício; confira o estado antes de agir" : resultado));
                            t.comandos.remove(comando.getKey());
                        }
                        salvar();
                    }
                    if (esperandoComando) { mudar(t, ESPERANDO_PC); continue; }
                }
                if (t.espera != null && !t.espera.pronta()) {
                    if (t.status == PAUSADA) mudar(t, t.espera.tipo() == SuspensaoDaTarefa.Tipo.APROVACAO
                            ? ESPERANDO_VOCE : List.of(SuspensaoDaTarefa.Tipo.PC, SuspensaoDaTarefa.Tipo.OPERACAO_PC,
                                    SuspensaoDaTarefa.Tipo.COMPUTER_USE).contains(t.espera.tipo()) ? ESPERANDO_PC : NA_FILA);
                    continue;
                }
                agendar(t);
            }
            if (!relogio.instant().isBefore(ultimoCheckpoint.plusSeconds(1))
                    && lista.stream().anyMatch(t -> t.status == RODANDO)) {
                ultimoCheckpoint = relogio.instant(); salvar();
            }
            boolean expurgou;
            synchronized (trava) {
                var encerradas = tarefas.values().stream().filter(TarefaDeFundo::encerrada).toList();
                Instant limite = relogio.instant().minus(Duration.ofDays(30));
                int excedentes = Math.max(0, encerradas.size() - 100);
                var saem = new java.util.HashSet<String>();
                for (int i = 0; i < encerradas.size(); i++) {
                    var t = encerradas.get(i);
                    if (i < excedentes || t.atualizadaEm.isBefore(limite)) saem.add(t.id);
                }
                expurgou = tarefas.keySet().removeAll(saem);
            }
            if (expurgou) salvar();
        } catch (RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("Fila de tarefas: {}", e.getMessage()); }
        finally { varrendo.set(false); }
    }

    private void agendar(TarefaDeFundo t) {
        synchronized (trava) { if (fechando || t.agendada || t.encerrada()) return; t.agendada = true; }
        executor.submit(() -> executar(t));
    }

    private void executar(TarefaDeFundo t) {
        t.executora = Thread.currentThread();
        try (var contexto = ContextoDaTarefa.entrar(t.id, t.usuario, valor -> {
            synchronized (trava) { t.custoUsd += valor; } salvar();
        }, texto -> notificar(t, texto, List.of()), t.dados).observarOperacoes((id, acao) -> {
            synchronized (trava) { t.comandos.put(id, acao); } salvar();
        }, (id, resultado) -> {
            synchronized (trava) {
                t.comandos.remove(id);
                t.resumo.add("Operação " + id + ": " + resultado.substring(0, Math.min(400, resultado.length())));
            }
            salvar();
        })) {
            verificar(t);
            mudar(t, RODANDO);
            if (t.aprovacaoInicial != null && !t.planoAprovado) t.aprovacaoInicial.executar();
            verificar(t);
            if (t.ciclo == null) {
                if (t.passos >= props.tarefaMaxPassos()) throw new IllegalStateException("Limite de passos atingido.");
                t.contextoArquivo = new ContextoArquivo();
                List<Mensagem> turno = new ArrayList<>();
                String resumo;
                synchronized (trava) { resumo = String.join("\n", t.resumo); }
                String instrucao = (t.retomada ? "Retomada após reinício; confira o estado antes de repetir qualquer ação.\n" : "")
                        + "Objetivo: " + t.objetivo + "\nPlano: " + String.join("; ", t.plano)
                        + "\nPassos já feitos (registro do servidor):\n" + resumo;
                turno.add(Mensagem.texto(Mensagem.Papel.USUARIO, instrucao));
                t.ciclo = ciclo.novaExecucao(new ArrayDeque<>(), turno, Canal.TAREFA, ouvinte(t),
                        t.detalhados, () -> t.cancelar, new CicloDeFerramentas.Limites(props.tarefaMaxPassos() - t.passos));
            }
            t.espera = null; t.esperaTipo = ""; t.esperaAlvo = ""; t.retomarEm = null;
            if (t.resultadoProvisorio) {
                synchronized (trava) { t.ciclo.conferirResultados(List.copyOf(t.resumo)); }
                t.resultadoProvisorio = false;
            }
            String resultado = t.ciclo.continuar();
            verificar(t);
            var pendentes = java.util.Map.copyOf(t.comandos);
            if (!pendentes.isEmpty()) {
                t.resultadoProvisorio = true;
                throw new SuspensaoDaTarefa(SuspensaoDaTarefa.Tipo.OPERACAO_PC,
                        pendentes.keySet().iterator().next(), null, t.comandos::isEmpty,
                        () -> ResultadoFerramenta.texto("Operações externas terminadas."));
            }
            finalizar(t, t.ciclo.semAvanco() ? FALHOU : CONCLUIDA, resultado);
        } catch (SuspensaoDaTarefa espera) {
            synchronized (trava) {
                t.espera = espera; t.esperaTipo = espera.tipo().name(); t.esperaAlvo = espera.alvo(); t.retomarEm = espera.ate();
            }
            mudar(t, t.pausar ? PAUSADA : espera.tipo() == SuspensaoDaTarefa.Tipo.APROVACAO ? ESPERANDO_VOCE
                    : List.of(SuspensaoDaTarefa.Tipo.PC, SuspensaoDaTarefa.Tipo.OPERACAO_PC, SuspensaoDaTarefa.Tipo.COMPUTER_USE)
                            .contains(espera.tipo()) ? ESPERANDO_PC : NA_FILA);
        } catch (Exception e) {
            if (t.cancelar) finalizar(t, CANCELADA, "Cancelada pelo usuário.");
            else if (fechando || t.pausar || !freio.podeAgirSozinho("tarefa")) mudar(t, PAUSADA);
            else finalizar(t, t.cancelar || e instanceof ControleDaTarefa ? CANCELADA : FALHOU,
                    !t.falhaOrcamento.isBlank() ? t.falhaOrcamento : e.getMessage() == null ? "Execução interrompida." : e.getMessage());
        } finally {
            Thread.interrupted();
            synchronized (trava) { t.executora = null; t.agendada = false; }
        }
    }

    private OuvinteProgresso ouvinte(TarefaDeFundo t) {
        return new OuvinteProgresso() {
            @Override public String idPedido() { return "tarefa:" + t.id; }
            @Override public String idTarefaDeFundo() { return t.id; }
            @Override public long usuarioAutenticado() { return t.usuario; }
            @Override public long chatDeOrigem() { return t.chat; }
            @Override public String canal() { return "tarefa"; }
            @Override public String pedidoOriginal() { return t.objetivo; }
            @Override public ContextoArquivo contextoArquivo() { return t.contextoArquivo; }
            @Override public void antesDoPasso() {
                verificar(t);
                synchronized (trava) {
                    if (t.passos >= props.tarefaMaxPassos()) throw new IllegalStateException("Limite de passos atingido.");
                    t.passos++;
                }
                salvar();
            }
            @Override public void antesDaFerramenta(String nome) {
                verificar(t);
                if (esperas.usaPc(nome) && !esperas.pcDisponivel()) throw esperas.criar(SuspensaoDaTarefa.Tipo.PC, "", null);
                t.registrosAntes = ContextoDaTarefa.atual().registros();
            }
            @Override public void depoisDaFerramenta(String nome, String resultado, boolean erro) {
                String registro = nome + ": " + (erro ? "falhou: " : "") + resultado.substring(0, Math.min(400, resultado.length()));
                synchronized (trava) { t.resumo.add(registro); }
                if (diario != null && ContextoDaTarefa.atual().registros() == t.registrosAntes)
                    diario.registrar(TipoDeAcao.PASSO_DE_TAREFA, registro, OrigemDaAcao.TAREFA, t.usuario, null);
                salvar();
            }
        };
    }

    private long prazoMillis() { return Duration.ofMinutes(props.tarefaMaxMinutos()).toMillis(); }
    private void verificar(TarefaDeFundo t) {
        if (t.cancelar) throw new ControleDaTarefa("Cancelada pelo usuário.");
        if (t.tempoAtivo(relogio.instant()) >= prazoMillis() || !t.falhaOrcamento.isBlank())
            throw new IllegalStateException("Limite de " + props.tarefaMaxMinutos() + " minutos ativos atingido.");
        if (fechando || t.pausar || !freio.podeAgirSozinho("tarefa")) {
            t.pausar = true; throw new ControleDaTarefa("Trabalho autônomo pausado.");
        }
    }
    private void mudar(TarefaDeFundo t, TarefaDeFundo.Status status) {
        synchronized (trava) {
            if (t.encerrada() || t.status == status) return;
            Instant agora = relogio.instant();
            t.tempoAtivoMillis = t.tempoAtivo(agora); t.ativoDesde = status == RODANDO ? agora : null;
            if (status == RODANDO && t.iniciadaEm == null) t.iniciadaEm = agora;
            t.status = status; t.atualizadaEm = agora;
        }
        salvar();
    }
    private void pausarTodas() {
        List<TarefaDeFundo> lista;
        synchronized (trava) { lista = List.copyOf(tarefas.values()); }
        for (var t : lista) if (!t.encerrada()) {
            t.pausar = true;
            try { mudar(t, PAUSADA); }
            catch (RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("Pausa aplicada sem persistência: {}", e.getMessage()); }
            finally {
                Thread thread = t.executora; if (thread != null) thread.interrupt();
                cancelarOperacoes(t);
            }
        }
    }

    private void cancelarOperacoes(TarefaDeFundo t) {
        for (String comando : java.util.Map.copyOf(t.comandos).keySet()) {
            try { esperas.cancelarOperacao(comando); }
            catch (RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("Não consegui cancelar operação {}: {}", comando, e.getMessage()); }
        }
        var espera = t.espera;
        if (espera != null && espera.tipo() == SuspensaoDaTarefa.Tipo.COMPUTER_USE)
            esperas.cancelarComputer(espera.alvo(), t.usuario);
    }

    private void finalizar(TarefaDeFundo t, TarefaDeFundo.Status status, String resultado) {
        synchronized (trava) {
            if (t.encerrada() || t.finalizando) return;
            t.finalizando = true;
        }
        // O retrato terminal só fica visível quando já não há capacidades utilizáveis.
        aprovacoes.cancelarDaTarefa(t.id); concessoes.encerrarTarefa(t.id);
        if (status != CONCLUIDA) cancelarOperacoes(t);
        synchronized (trava) {
            Instant agora = relogio.instant(); t.tempoAtivoMillis = t.tempoAtivo(agora); t.ativoDesde = null;
            t.status = status; t.concluidaEm = agora; t.atualizadaEm = agora; t.resultado = resultado;
        }
        try { salvar(); }
        catch (RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("Resultado da tarefa não persistido: {}", e.getMessage()); }
        List<Anexo> publicados = new ArrayList<>();
        for (var detalhado : t.detalhados) {
            for (var artefato : detalhado.artefatos()) try { publicados.add(anexos.registrar(artefato)); }
            catch (IOException e) { LoggerFactory.getLogger(getClass()).warn("Anexo de tarefa indisponível: {}", e.getMessage()); }
            for (var referencia : detalhado.referencias()) anexos.referencia(referencia.nome(), referencia.url()).ifPresent(publicados::add);
        }
        String texto = (status == CONCLUIDA ? "Concluída: " : status == FALHOU ? "Falhou: " : "Cancelada: ") + resultado;
        if (t.origem == TarefaDeFundo.Origem.GATILHO) avisos.avisar("tarefa:" + t.id, status.name(), texto, null);
        else {
            notificar(t, texto, publicados);
            if (t.canal == Canal.TELEGRAM || !hud.conectada()) {
                for (var detalhado : t.detalhados) for (var artefato : detalhado.artefatos()) {
                    try { telegram.enviarDocumento(t.chat, java.nio.file.Files.readAllBytes(artefato.arquivo()), artefato.nome()); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    catch (IOException | RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("Entrega de anexo falhou: {}", e.getMessage()); }
                }
            }
        }
        tardios.registrar(t.chat, "a tarefa de fundo " + t.id, texto);
        t.ciclo = null; t.contextoArquivo = null; t.detalhados.clear(); t.espera = null;
        t.dados.urls.clear();
    }

    private void notificar(TarefaDeFundo t, String texto, List<Anexo> publicados) {
        try {
            if (t.canal == Canal.HUD && hud.conectada()) conversas.registrarResultadoTardio(t.chat,
                    t.id + "-" + UUID.randomUUID().toString().substring(0, 8), texto, publicados);
            else telegram.enviarMensagem(t.chat, texto);
        } catch (RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("Aviso da tarefa {} falhou: {}", t.id, e.getMessage()); }
    }
    private void salvar() {
        List<TarefaDeFundo.Retrato> lista; long versao;
        synchronized (trava) {
            Instant agora = relogio.instant();
            lista = tarefas.values().stream().map(t -> t.retrato(agora)).toList(); versao = ++revisao;
        }
        try { arquivo.salvar(versao, lista); }
        catch (IOException e) { throw new IllegalStateException("Não consegui persistir as tarefas", e); }
    }
    public TarefaDeFundo.Retrato estado(String id, long usuario) {
        synchronized (trava) { var t = tarefas.get(id); return t == null || t.usuario != usuario ? null : t.retrato(relogio.instant()); }
    }
    public List<TarefaDeFundo.Retrato> listar(long usuario) {
        synchronized (trava) { return tarefas.values().stream().filter(t -> t.usuario == usuario).map(t -> t.retrato(relogio.instant())).toList(); }
    }
    public boolean cancelar(String id, long usuario) {
        TarefaDeFundo t;
        synchronized (trava) {
            t = tarefas.get(id); if (t == null || t.usuario != usuario || t.encerrada() || t.finalizando) return false;
            t.cancelar = true;
        }
        aprovacoes.cancelarDaTarefa(id);
        cancelarOperacoes(t);
        Thread executora = t.executora;
        if (executora != null) executora.interrupt();
        else finalizar(t, CANCELADA, "Cancelada pelo usuário.");
        return true;
    }
    public boolean cancelarUltima(long usuario) {
        var ultima = listar(usuario).stream().filter(t -> !List.of(CONCLUIDA, FALHOU, CANCELADA).contains(t.status()))
                .max(Comparator.comparing(TarefaDeFundo.Retrato::criadaEm));
        return ultima.map(t -> cancelar(t.id(), usuario)).orElse(false);
    }
    @jakarta.annotation.PreDestroy public void fechar() {
        fechando = true; pausarTodas(); executor.shutdownNow();
        try { executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        salvar();
    }
}
