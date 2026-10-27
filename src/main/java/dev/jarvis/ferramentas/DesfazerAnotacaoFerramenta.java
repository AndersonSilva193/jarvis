package dev.jarvis.ferramentas;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.memoria.AnotadorDeMemoria;
import dev.jarvis.memoria.MemoriaService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tira o que o Jarvis anotou sozinho agora há pouco, quando o dono diz que não era para anotar.
 *
 * <p><b>Sem {@code Aprovacoes}, de propósito.</b> Quem gravou aquilo foi o anotador, há minutos,
 * sem ninguém pedir — desfazer isso é reversão, não perda, e é o mesmo raciocínio do
 * {@code desfazer_edicao}. Perguntar "Posso tirar o que eu mesmo acabei de guardar?" seria atrito
 * sem contrapartida. Apagar registro antigo continua passando pelo {@code esquecer_memoria}, que
 * pergunta.
 *
 * <p>A janela de dez minutos vive no {@link AnotadorDeMemoria}: fora dela a ferramenta <b>não apaga
 * nada</b> e manda o modelo pelo caminho com aprovação. Sem isso, "apaga isso que você anotou" dito
 * no dia seguinte tiraria um registro que o dono nem tem mais em mente.
 *
 * <p>Não marca {@code memoriaEsquecida}: o histórico da conversa fica. O dono está no meio de uma
 * conversa e recusou uma anotação — perder a conversa por isso seria um susto, não privacidade.
 */
@Component
public class DesfazerAnotacaoFerramenta extends FerramentaMemoria {

    private final AnotadorDeMemoria anotador;

    public DesfazerAnotacaoFerramenta(MemoriaService memoria, JsonMapper json,
            AnotadorDeMemoria anotador) {
        super(memoria, json);
        this.anotador = anotador;
    }

    @Override public String nome() { return "desfazer_anotacao"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.MEMORIA; }
    @Override public boolean simples() { return true; }

    @Override public String descricao() {
        return """
                Tira o que você anotou sozinho agora há pouco, quando o usuário recusa aquilo: \
                "não anota isso", "apaga isso que você anotou", "não era para guardar". Use quando \
                a recusa é sobre a linha "Anotei: ..." da sua resposta anterior.

                Não pede confirmação e não leva argumento. Depois de chamar, responda apenas \
                "Tirei." — sem repetir o que era nem mencionar id.

                Se o usuário quiser apagar uma lembrança antiga, que não é desta conversa, use \
                buscar_memoria e esquecer_memoria: aquele caminho pergunta antes.\
                """;
    }

    @Override public ObjectNode esquemaEntrada() {
        ObjectNode s = base();
        s.putObject("properties");
        return s;
    }

    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso)
            throws Exception {
        long u = usuario(progresso);
        var recente = anotador.consumirDesfazivel(u);
        if (recente.isEmpty())
            return resposta("Não há anotação recente minha para tirar. Se o usuário quer apagar uma "
                    + "lembrança antiga, use buscar_memoria e depois esquecer_memoria, que pede "
                    + "autorização. Nada foi apagado.");

        List<String> tirados = new ArrayList<>();
        List<String> revertidos = new ArrayList<>();
        for (String id : recente.get().ids()) {
            MemoriaService.Desfeito desfeito = memoria.desfazerUltimaEscrita(u, id);
            if (desfeito == null) continue;
            (desfeito.revertido() ? revertidos : tirados).add(desfeito.assunto());
        }
        if (tirados.isEmpty() && revertidos.isEmpty())
            return resposta("Aquela anotação já não estava na memória; nada a fazer.");

        StringBuilder sb = new StringBuilder("Tirei.");
        if (!tirados.isEmpty())
            sb.append(" Apaguei: ").append(String.join(", ", tirados))
                    .append(" (não vou anotar esse assunto de novo).");
        if (!revertidos.isEmpty())
            sb.append(" Voltei ao que estava antes em: ").append(String.join(", ", revertidos))
                    .append('.');
        sb.append(" Responda ao usuário apenas \"Tirei.\".");
        return resposta(sb.toString());
    }
}
