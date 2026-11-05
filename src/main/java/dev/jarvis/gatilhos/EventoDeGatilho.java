package dev.jarvis.gatilhos;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Um fato que aconteceu e que uma regra pode estar esperando. Evento de domínio, como o
 * {@code PcVoltou}: quem publica é o serviço que sabe do fato, quem escuta é o
 * {@link MotorDeGatilhos}.
 *
 * <p>Os {@code dados} são valores reais (nome do site, motivo da checagem, nome do arquivo novo) e
 * vêm de fora do Jarvis: o texto de erro de um site ou o nome de um arquivo podem conter qualquer
 * coisa. Por isso eles chegam ao modelo só como DADO, pelo {@link DadoDoEvento}, nunca colados na
 * instrução.
 */
public record EventoDeGatilho(FonteDeGatilho fonte, Instant em, Map<String, String> dados) {

    public EventoDeGatilho {
        Objects.requireNonNull(fonte);
        Objects.requireNonNull(em);
        // Ordem estável: o bloco de dado sai sempre igual para o mesmo evento.
        var copia = new LinkedHashMap<String, String>();
        if (dados != null) dados.forEach((k, v) -> { if (k != null && v != null) copia.put(k, v); });
        dados = java.util.Collections.unmodifiableMap(copia);
    }

    public String dado(String chave) {
        return dados.getOrDefault(chave, "");
    }

    /** Atalho para quem publica: pares chave, valor. */
    public static EventoDeGatilho de(FonteDeGatilho fonte, Instant em, String... pares) {
        var mapa = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < pares.length; i += 2) mapa.put(pares[i], pares[i + 1]);
        return new EventoDeGatilho(fonte, em, mapa);
    }
}
