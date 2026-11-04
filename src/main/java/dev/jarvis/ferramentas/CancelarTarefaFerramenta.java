package dev.jarvis.ferramentas;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.tarefas.TarefasDeFundo;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cancelar é seguro, então não pede aprovação. O que já foi feito não volta sozinho: fica no
 * diário, e o que tiver desfazer é citado com o id para o {@code desfazer_acao}.
 */
@Component
public class CancelarTarefaFerramenta extends FerramentaDeTarefa {
    public CancelarTarefaFerramenta(ObjectProvider<TarefasDeFundo> tarefas, JsonMapper json, JarvisProperties props) {
        super(tarefas, json, props);
    }
    @Override public String nome() { return "cancelar_tarefa"; }
    @Override public String descricao() {
        return "Cancela uma tarefa de fundo, sem pedir aprovação (\"cancela a organização da pasta\", \"cancela isso\"). "
                + "Com uma só ativa, sem tarefa vale ela. Nada mais roda; o que já foi feito continua no diário, "
                + "e a resposta diz o que dá para desfazer.";
    }
    @Override protected String responder(String referencia, long usuario) {
        var escolha = escolher(referencia, usuario);
        if (!escolha.unica()) return semEscolha(escolha, referencia, usuario);
        var t = escolha.tarefa();
        if (!tarefas.getObject().cancelar(t.id(), usuario, false))
            return "A tarefa \"" + t.objetivo() + "\" já tinha terminado.";
        var feito = tarefas.getObject().feitoNoDiario(t.id(), usuario).stream()
                .filter(a -> a.tipo() != TipoDeAcao.PASSO_DE_TAREFA || a.reversivel()).toList();
        List<String> linhas = new ArrayList<>();
        linhas.add("Cancelei: " + t.objetivo() + ". Nada mais vai rodar.");
        if (feito.isEmpty()) linhas.add("Nenhuma ação com efeito tinha sido feita.");
        else {
            linhas.add("Já tinha feito (está no diário):");
            for (var a : feito.subList(Math.max(0, feito.size() - 8), feito.size()))
                linhas.add("- " + a.descricao() + (a.reversivel() && "disponivel".equals(a.estadoDesfazer())
                        ? " (dá para desfazer: desfazer_acao id=" + a.id() + ")" : ""));
        }
        return String.join("\n", linhas);
    }
}
