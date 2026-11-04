package dev.jarvis.ferramentas;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.tarefas.TarefasDeFundo;
import tools.jackson.databind.json.JsonMapper;

/** Pausa uma tarefa até o dono pedir para retomar; nada é desfeito nem cancelado. */
@Component
public class PausarTarefaFerramenta extends FerramentaDeTarefa {
    public PausarTarefaFerramenta(ObjectProvider<TarefasDeFundo> tarefas, JsonMapper json, JarvisProperties props) {
        super(tarefas, json, props);
    }
    @Override public String nome() { return "pausar_tarefa"; }
    @Override public String descricao() {
        return "Pausa uma tarefa de fundo até o dono pedir para retomar. A chamada ao modelo em curso termina; "
                + "nada é desfeito. Com uma só ativa, sem tarefa vale ela.";
    }
    @Override protected String responder(String referencia, long usuario) {
        var escolha = escolher(referencia, usuario);
        if (!escolha.unica()) return semEscolha(escolha, referencia, usuario);
        var t = escolha.tarefa();
        return switch (tarefas.getObject().pausar(t.id(), usuario)) {
            case FEITA -> "Pausei: " + t.objetivo() + ". Fica parada até você pedir para retomar.";
            case JA_ESTAVA -> "Já estava pausada: " + t.objetivo() + ".";
            case ENCERRADA -> "A tarefa \"" + t.objetivo() + "\" já tinha terminado.";
            case NAO_ENCONTRADA, FREIO -> "Não achei essa tarefa.";
        };
    }
}
