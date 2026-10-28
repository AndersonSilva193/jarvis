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
import dev.jarvis.ia.provedor.Bloco;
import dev.jarvis.ia.provedor.FormatoNeutro;
import dev.jarvis.ia.provedor.Mensagem;
import dev.jarvis.ia.provedor.ProvedorAnthropic;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.ia.provedor.SeletorDeModelo;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * O "cérebro": recebe o pedido, conversa com o Claude e executa as ferramentas que ele pedir,
 * repetindo até o modelo dar a resposta final (o chamado "loop de agente").
 */
@Service
public class Assistente {

    /**
     * Quantas trocas (pedido + resposta) o Jarvis lembra por conversa. Também é o teto do que volta
     * do disco na partida: o arquivo pode ter sido gravado por uma versão que lembrava mais.
     */
    private static final int MAX_TURNOS_HISTORICO = 10;
    private static final String PAREI = PedidoInterrompido.PAREI;

    private static final Logger log = LoggerFactory.getLogger(Assistente.class);

    /** O laço de ferramentas de um pedido. Montado aqui, com as mesmas peças: não é bean. */
    private final CicloDeFerramentas ciclo;
    private final Ferramentas ferramentas;
    private final JsonMapper json;
    /** Como o turno vai ao disco e volta dele. */
    private final FormatoNeutro formato;
    private final RegistroConversas conversas;
    private final AnexosService anexos;
    private final Aprovacoes aprovacoes;
    private final ClassificadorDeResposta classificador;
    private final HistoricoEmArquivo arquivo;
    /** Quem encolhe os turnos antigos: sem ele o histórico reenviaria toda ferramenta de novo. */
    private final CompactadorDeHistorico compactador;
    /** Opcional: nulo nos construtores enxutos, como o resto das peças de borda desta classe. */
    private final AnotadorDeMemoria anotador;
    /** O recado de que o {@code esquecer_memoria} aprovado apagou algo fora deste turno. */
    private final EsquecimentosDeMemoria esquecimentos;
    /** O que terminou fora de um turno (a tarefa no PC) e precisa entrar no histórico. */
    private final ResultadosTardios tardios;
    /** Quem atende "modo local" e "pergunta pro Claude: ...". Nulo nos construtores enxutos. */
    private final SeletorDeModelo seletor;
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

    /** Há pedido sendo atendido agora. */
    public boolean ocupado() {
        return emAndamento.get() != null;
    }

    /**
     * Marca o pedido em andamento como interrompido: o {@link CicloDeFerramentas} o encerra no próximo
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

    /**
     * Cada turno é a lista de mensagens de um pedido (texto do usuário, chamadas de ferramenta e
     * resposta), no formato neutro: é o que o {@code ProvedorDeModelo} recebe e o que vai ao disco.
     */
    private final Map<Long, Deque<List<Mensagem>>> historicos = new HashMap<>();
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

    public Assistente(ClaudeClient claude, Ferramentas ferramentas, SistemaDoJarvis sistema, JsonMapper json,
            RegistroConversas conversas, AnexosService anexos, BuscaWebServidor buscaWeb,
            Aprovacoes aprovacoes, HistoricoEmArquivo arquivo, ClassificadorDeResposta classificador,
            AnotadorDeMemoria anotador, EsquecimentosDeMemoria esquecimentos,
            CompactadorDeHistorico compactador) {
        this(claude, ferramentas, sistema, json, conversas, anexos, buscaWeb, aprovacoes, arquivo,
                classificador, anotador, esquecimentos, compactador, new ResultadosTardios());
    }

    /**
     * A aridade completa com o {@code ClaudeClient}, que é como os testes montam esta classe: o
     * cliente vira um {@code ProvedorAnthropic} e o resto segue igual.
     */
    public Assistente(ClaudeClient claude, Ferramentas ferramentas, SistemaDoJarvis sistema, JsonMapper json,
            RegistroConversas conversas, AnexosService anexos, BuscaWebServidor buscaWeb,
            Aprovacoes aprovacoes, HistoricoEmArquivo arquivo, ClassificadorDeResposta classificador,
            AnotadorDeMemoria anotador, EsquecimentosDeMemoria esquecimentos,
            CompactadorDeHistorico compactador, ResultadosTardios tardios) {
        this(ProvedorAnthropic.sobre(claude, json), ferramentas, sistema, json, conversas, anexos,
                buscaWeb, aprovacoes, arquivo, classificador, anotador, esquecimentos, compactador,
                tardios);
    }

    /** A aridade de antes do seletor: sem ele, "modo local" é só mais uma mensagem ao modelo. */
    public Assistente(ProvedorDeModelo modelo, Ferramentas ferramentas, SistemaDoJarvis sistema,
            JsonMapper json, RegistroConversas conversas, AnexosService anexos,
            BuscaWebServidor buscaWeb, Aprovacoes aprovacoes, HistoricoEmArquivo arquivo,
            ClassificadorDeResposta classificador, AnotadorDeMemoria anotador,
            EsquecimentosDeMemoria esquecimentos, CompactadorDeHistorico compactador,
            ResultadosTardios tardios) {
        this(modelo, ferramentas, sistema, json, conversas, anexos, buscaWeb, aprovacoes, arquivo,
                classificador, anotador, esquecimentos, compactador, tardios, null);
    }

    @Autowired
    public Assistente(ProvedorDeModelo modelo, Ferramentas ferramentas, SistemaDoJarvis sistema,
            JsonMapper json, RegistroConversas conversas, AnexosService anexos,
            BuscaWebServidor buscaWeb, Aprovacoes aprovacoes, HistoricoEmArquivo arquivo,
            ClassificadorDeResposta classificador, AnotadorDeMemoria anotador,
            EsquecimentosDeMemoria esquecimentos, CompactadorDeHistorico compactador,
            ResultadosTardios tardios, SeletorDeModelo seletor) {
        this.seletor = seletor;
        this.tardios = tardios != null ? tardios : new ResultadosTardios();
        this.compactador = compactador;
        this.esquecimentos = esquecimentos;
        this.anotador = anotador;
        this.classificador = classificador;
        this.arquivo = arquivo;
        this.ciclo = new CicloDeFerramentas(modelo, ferramentas, sistema, buscaWeb, json);
        this.ferramentas = ferramentas;
        this.json = json;
        this.formato = new FormatoNeutro(json);
        this.conversas = conversas;
        this.anexos = anexos;
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
                Deque<List<Mensagem>> historico = new ArrayDeque<>();
                for (List<ObjectNode> salvoDoTurno : salvo.turnos()) {
                    List<Mensagem> turno = deDisco(salvoDoTurno);
                    if (!turno.isEmpty()) historico.addLast(turno);
                }
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

    /**
     * Um turno salvo, no formato neutro ou no de antes dele. Turno ilegível sai inteiro, e só ele:
     * meio turno no histórico deixaria uma chamada de ferramenta sem resultado, e isso a API recusa.
     */
    private List<Mensagem> deDisco(List<ObjectNode> salvo) {
        List<Mensagem> turno = new ArrayList<>(salvo.size());
        try {
            for (ObjectNode mensagem : salvo) turno.add(formato.deJson(mensagem));
            return turno;
        } catch (RuntimeException e) {
            log.warn("Turno salvo ignorado por estar ilegível: {}", e.getMessage());
            return List.of();
        }
    }

    /** O turno como vai ao disco: no formato neutro, que qualquer provedor sabe ler. */
    private List<ObjectNode> paraDisco(List<Mensagem> turno) {
        return turno.stream().map(formato::paraJson).toList();
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
        // "Pergunta pro Claude: ..." — o pedido segue sem o prefixo, e toda escolha de modelo desta
        // thread é o Claude até o turno acabar. O modo gravado não muda.
        java.util.Optional<String> pontual = seletor == null ? java.util.Optional.empty()
                : TrocaDeModelo.pontual(texto);
        if (pontual.isPresent()) {
            try (SeletorDeModelo.Escopo escopo = seletor.claudeNestePedido()) {
                return responder(chatId, pontual.get(), canal, ouvinte);
            }
        }
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
            Deque<List<Mensagem>> historico = historicos.computeIfAbsent(chatId, chave -> new ArrayDeque<>());
            // O que terminou enquanto ninguém conversava — a tarefa no PC manda o aviso direto ao
            // Telegram — entra aqui, antes do pedido novo, que é a ordem em que aconteceu. Sem
            // isso o dono lia o resultado e o modelo não, e "qual o telefone da segunda?" ficava
            // sem resposta. O corte e a gravação em disco são os do fim do turno, como sempre.
            for (ResultadosTardios.Item item : tardios.drenar(chatId)) historico.addLast(turnoTardio(item));
            List<Mensagem> turno = new ArrayList<>();
            turno.add(mensagemDoUsuario(texto));

            List<ResultadoFerramenta> resultados = new ArrayList<>();
            String resposta;
            boolean interrompido = false;
            var decisaoPc = aprovacoes.tratar(texto, progresso.usuarioAutenticado(), id, canal.nome());
            // "Modo local" e "qual modelo você está usando?" são do servidor, e vêm antes do
            // classificador: não gastam modelo nenhum, e a pendência em aberto continua esperando.
            java.util.Optional<TrocaDeModelo.Comando> trocaDeModelo =
                    decisaoPc.isPresent() || seletor == null ? java.util.Optional.empty()
                            : TrocaDeModelo.comando(texto);
            // Só quando o texto não é um código nem uma frase fixa é que vale classificar: a
            // pergunta pendente é a única coisa que a mensagem pode estar respondendo, e sem
            // pendência em aberto nada disso roda — nem a lista, nem a chamada curta ao modelo.
            String emAberto = decisaoPc.isPresent() || trocaDeModelo.isPresent() ? ""
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
                turno.add(Mensagem.texto(Mensagem.Papel.ASSISTENTE, resposta));
            } else if (trocaDeModelo.isPresent()) {
                resposta = TrocaDeModelo.atender(trocaDeModelo.get(), seletor, canal.nome());
                turno.add(Mensagem.texto(Mensagem.Papel.ASSISTENTE, resposta));
            } else if (ComandoDeRepouso.reconhece(texto)) {
                Bloco.Chamada chamada = new Bloco.Chamada("repouso_" + id, "controlar_pc",
                        json.createObjectNode().put("acao", "modo_repouso"));
                resposta = ferramentas.executar(chamada, progresso, resultados::add).conteudo();
                turno.add(Mensagem.texto(Mensagem.Papel.ASSISTENTE, resposta));
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
                    resposta = ciclo.executar(historico, turno, canal, comAnexo, resultados,
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
                        historico.stream().map(this::paraDisco).toList(), conversas.trocas(chatId));
                // O anotador recebe o texto do usuário, nunca a resposta nem saída de ferramenta.
                if (anotador != null) {
                    anotador.registrarTurno(chatId, progresso.usuarioAutenticado(), canal.nome(), texto);
                }
            }
            return publica;
        } finally { emAndamento.compareAndSet(marca, null); execucao.unlock(); }
    }

    /**
     * Um turno {@code user}/{@code assistant} com o resultado tardio. A fala do usuário é uma nota
     * do servidor, montada em Java, para o modelo não tomá-la por pedido; a do assistente é
     * exatamente o que o dono recebeu, porque foi o Jarvis quem disse aquilo.
     */
    private List<Mensagem> turnoTardio(ResultadosTardios.Item item) {
        String origem = item.origem().isBlank() ? "uma tarefa em segundo plano" : item.origem();
        List<Mensagem> turno = new ArrayList<>();
        turno.add(mensagemDoUsuario("(nota do servidor, não é um pedido novo: " + origem
                + " terminou fora da conversa e o usuário recebeu a mensagem a seguir.)"));
        turno.add(Mensagem.texto(Mensagem.Papel.ASSISTENTE, item.texto()));
        return turno;
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
            List<Mensagem> turno = new ArrayList<>();
            turno.add(mensagemDoUsuario(instrucao));
            return ciclo.executar(new ArrayDeque<>(), turno, canal, contexto, new ArrayList<>(),
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
     * {@code server_tool_use} sem resultado é HTTP 400. A busca de ferramentas também preserva
     * seu resultado e as referências às definições encontradas até o turno ser compactado.
     */
    private List<Mensagem> semPensamentos(List<Mensagem> turno) {
        List<Mensagem> limpo = new ArrayList<>(turno.size());
        for (Mensagem mensagem : turno) {
            // Só o que vale dentro do turno sai — o pensamento. Busca web e busca de ferramentas,
            // inclusive resultados e referências, ficam intactas enquanto este turno estiver completo.
            List<Bloco> mantidos = mensagem.blocos().stream()
                    .filter(bloco -> !(bloco instanceof Bloco.Opaco opaco && opaco.soNoTurno()))
                    .toList();
            if (mantidos.size() == mensagem.blocos().size()) {
                limpo.add(mensagem);
            } else if (mantidos.isEmpty()) {
                // Mensagem sem conteúdo nenhum a API recusa; sobra o texto de marcação.
                limpo.add(Mensagem.texto(mensagem.papel(), "(sem resposta)"));
            } else {
                limpo.add(new Mensagem(mensagem.papel(), mantidos));
            }
        }
        return limpo;
    }

    private static Mensagem mensagemDoUsuario(String texto) {
        return Mensagem.texto(Mensagem.Papel.USUARIO, texto);
    }
}
