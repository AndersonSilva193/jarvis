package dev.jarvis.ia.provedor;

import java.time.Duration;

import dev.jarvis.custo.Origem;
import dev.jarvis.ia.PerfilDeEsforco;

/**
 * Como a chamada deve ser feita, fora o que ela diz.
 *
 * @param origem      quem gasta: vai ao diário de custo e decide o cache da conversa
 * @param esforco     quanto pensar, quando a origem não basta para decidir (a tarefa agendada gasta
 *                    na conversa e pensa diferente); {@code null} deixa a origem decidir
 * @param maxTokens   o teto de saída da chamada curta; 0 é o teto configurado
 * @param prazo       o prazo da chamada curta, que também não repete em erro temporário;
 *                    {@code null} é a chamada normal
 * @param modelo      o modelo desta chamada, quando não é o configurado; {@code null} é o de sempre.
 *                    Só o provedor que escolhe modelo pela VPS o lê — no local, quem decide é o PC
 * @param temperatura a temperatura, ou {@code null} para não mandar nenhuma. A Anthropic a ignora:
 *                    os modelos atuais recusam o campo com HTTP 400
 */
public record OpcoesDoPedido(Origem origem, PerfilDeEsforco esforco, int maxTokens,
        Duration prazo, String modelo, Double temperatura) {

    public OpcoesDoPedido {
        if (origem == null) {
            throw new IllegalArgumentException("toda chamada ao modelo tem uma origem");
        }
        if ((prazo == null) != (maxTokens <= 0)) {
            throw new IllegalArgumentException("chamada curta leva teto de tokens e prazo, os dois");
        }
        if (prazo != null && esforco != null) {
            throw new IllegalArgumentException("a chamada curta pensa pelo perfil da origem");
        }
        if (modelo != null && modelo.isBlank()) {
            modelo = null;
        }
    }

    /** A forma de antes do modelo e da temperatura existirem: os dois ficam no padrão. */
    public OpcoesDoPedido(Origem origem, PerfilDeEsforco esforco, int maxTokens, Duration prazo) {
        this(origem, esforco, maxTokens, prazo, null, null);
    }

    public static OpcoesDoPedido de(Origem origem) {
        return new OpcoesDoPedido(origem, null, 0, null);
    }

    public static OpcoesDoPedido de(Origem origem, PerfilDeEsforco esforco) {
        return new OpcoesDoPedido(origem, esforco, 0, null);
    }

    /** Chamada curta de decisão: teto e prazo próprios, tentativa única. */
    public static OpcoesDoPedido curta(Origem origem, int maxTokens, Duration prazo) {
        return new OpcoesDoPedido(origem, null, maxTokens, prazo);
    }

    public OpcoesDoPedido comModelo(String outro) {
        return new OpcoesDoPedido(origem, esforco, maxTokens, prazo, outro, temperatura);
    }

    public OpcoesDoPedido comTemperatura(Double outra) {
        return new OpcoesDoPedido(origem, esforco, maxTokens, prazo, modelo, outra);
    }

    public boolean curta() {
        return prazo != null;
    }
}
