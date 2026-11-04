package dev.jarvis.tarefas;

/** Controle do executor: não deve virar um erro de ferramenta entregue ao modelo. */
public class ControleDaTarefa extends RuntimeException {
    public ControleDaTarefa(String motivo) { super(motivo, null, false, false); }
}
