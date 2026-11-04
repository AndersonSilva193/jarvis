package dev.jarvis.tarefas;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleConsumer;

/** Identidade interna, instalada pelo executor e nunca escolhida pelo modelo. */
public final class ContextoDaTarefa implements AutoCloseable {
    private static final ThreadLocal<ContextoDaTarefa> ATUAL = new ThreadLocal<>();
    private final ContextoDaTarefa anterior;
    private final String id;
    private final long usuario;
    private final DoubleConsumer custo;
    private final java.util.function.Consumer<String> aviso;
    private final AtomicInteger registros = new AtomicInteger();
    /** Mantido pela tarefa entre suspensões; nunca compartilhado com a conversa. */
    public static final class Dados {
        public final AtomicInteger apagados = new AtomicInteger();
        public final java.util.Set<String> urls = java.util.concurrent.ConcurrentHashMap.newKeySet();
    }
    private final Dados dados;
    private java.util.function.BiConsumer<String, String> aoComando = (id, acao) -> { };
    private java.util.function.BiConsumer<String, String> aoResultado = (id, resultado) -> { };
    public ContextoDaTarefa observarOperacoes(java.util.function.BiConsumer<String, String> comando,
            java.util.function.BiConsumer<String, String> resultado) {
        aoComando = comando; aoResultado = resultado; return this;
    }
    public void registrarComando(String id, String acao) { aoComando.accept(id, acao); }
    public void concluirComando(String id, String resultado) { aoResultado.accept(id, resultado); }

    private ContextoDaTarefa(String id, long usuario, DoubleConsumer custo, java.util.function.Consumer<String> aviso, Dados dados) {
        this.id = id; this.usuario = usuario; this.custo = custo;
        this.aviso = aviso;
        this.dados = dados;
        anterior = ATUAL.get(); ATUAL.set(this);
    }

    public static ContextoDaTarefa entrar(String id, long usuario, DoubleConsumer custo) {
        return entrar(id, usuario, custo, texto -> { });
    }
    public static ContextoDaTarefa entrar(String id, long usuario, DoubleConsumer custo,
            java.util.function.Consumer<String> aviso) { return entrar(id, usuario, custo, aviso, new Dados()); }
    public static ContextoDaTarefa entrar(String id, long usuario, DoubleConsumer custo,
            java.util.function.Consumer<String> aviso, Dados dados) {
        return new ContextoDaTarefa(id, usuario, custo, aviso, dados);
    }
    public Dados dados() { return dados; }
    public void avisar(String texto) { aviso.accept(texto); }
    public static ContextoDaTarefa atual() { return ATUAL.get(); }
    public static String isolamento() { return atual() == null ? "conversa" : "tarefa:" + atual().id; }
    public String id() { return id; }
    public long usuario() { return usuario; }
    public void somarCusto(double valor) { custo.accept(valor); }
    public int registros() { return registros.get(); }
    public void registrou() { registros.incrementAndGet(); }
    @Override public void close() {
        if (anterior == null) ATUAL.remove(); else ATUAL.set(anterior);
    }
}
