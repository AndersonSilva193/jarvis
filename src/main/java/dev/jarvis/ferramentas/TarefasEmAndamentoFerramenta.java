package dev.jarvis.ferramentas;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.tarefas.ResolvedorDeTarefa;
import dev.jarvis.tarefas.TarefasDeFundo;
import tools.jackson.databind.json.JsonMapper;

/** "O que você está fazendo?", "como está aquela tarefa?". Leitura: não muda nada. */
@Component
public class TarefasEmAndamentoFerramenta extends FerramentaDeTarefa {
    public TarefasEmAndamentoFerramenta(ObjectProvider<TarefasDeFundo> tarefas, JsonMapper json, JarvisProperties props) {
        super(tarefas, json, props);
    }
    @Override public String nome() { return "tarefas_em_andamento"; }
    @Override public boolean simples() { return true; }
    @Override public String descricao() {
        return "Lista as tarefas de fundo do dono: objetivo, status, passo atual e há quanto tempo; as encerradas "
                + "nas últimas 24 h vêm no fim. Use para \"o que você está fazendo?\" ou \"como está aquela tarefa?\". "
                + "Com tarefa, mostra só a que casar.";
    }
    @Override protected String responder(String referencia, long usuario) {
        var ativas = ativas(usuario);
        var encerradas = encerradasRecentes(usuario);
        if (!referencia.isBlank()) {
            var todas = new ArrayList<>(ativas); todas.addAll(encerradas);
            var escolha = ResolvedorDeTarefa.resolver(referencia, todas);
            if (escolha.unica()) return linha(escolha.tarefa());
            if (!escolha.candidatas().isEmpty() && escolha.candidatas().size() < todas.size())
                return String.join("\n", escolha.candidatas().stream().map(this::linha).toList());
        }
        if (ativas.isEmpty() && encerradas.isEmpty()) return "Nenhuma tarefa de fundo nas últimas 24 h.";
        List<String> partes = new ArrayList<>();
        partes.add(ativas.isEmpty() ? "Nenhuma tarefa ativa agora."
                : "Ativas:\n" + String.join("\n", ativas.stream().map(this::linha).toList()));
        if (!encerradas.isEmpty())
            partes.add("Encerradas nas últimas 24 h:\n" + String.join("\n", encerradas.stream().map(this::linha).toList()));
        return String.join("\n", partes);
    }
}
