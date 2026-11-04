package dev.jarvis.ferramentas;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.tarefas.TarefaDeFundo;
import dev.jarvis.tarefas.TarefaFalada;
import dev.jarvis.tarefas.TarefasDeFundo;
import tools.jackson.databind.json.JsonMapper;

/** Tira a pausa; a pergunta que expirou volta a ser feita, com código e prazo novos. */
@Component
public class RetomarTarefaFerramenta extends FerramentaDeTarefa {
    public RetomarTarefaFerramenta(ObjectProvider<TarefasDeFundo> tarefas, JsonMapper json, JarvisProperties props) {
        super(tarefas, json, props);
    }
    @Override public String nome() { return "retomar_tarefa"; }
    @Override public String descricao() {
        return "Retoma uma tarefa de fundo pausada, inclusive a que parou esperando sua resposta (a pergunta volta). "
                + "Com uma só ativa, sem tarefa vale ela.";
    }
    @Override protected String responder(String referencia, long usuario) {
        var escolha = escolher(referencia, usuario);
        if (!escolha.unica()) return semEscolha(escolha, referencia, usuario);
        var t = escolha.tarefa();
        boolean pergunta = TarefaDeFundo.PAUSA_SEM_RESPOSTA.equals(t.pausa());
        return switch (tarefas.getObject().retomar(t.id(), usuario)) {
            case FEITA -> pergunta ? "Retomei: " + t.objetivo() + ". Vou refazer a pergunta que ficou sem resposta."
                    : "Retomei: " + t.objetivo() + ".";
            case FREIO -> "Tirei a pausa de \"" + t.objetivo() + "\", mas o trabalho autônomo está pausado: "
                    + "ela volta quando você liberar.";
            case JA_ESTAVA -> "Não estava pausada: " + t.objetivo() + " está "
                    + TarefaFalada.status(t, tarefas.getObject().agora(), props.zona()) + ".";
            case ENCERRADA -> "A tarefa \"" + t.objetivo() + "\" já tinha terminado.";
            case NAO_ENCONTRADA -> "Não achei essa tarefa.";
        };
    }
}
