package dev.jarvis.ia;

import java.util.Locale;

/**
 * Quanto o modelo pensa antes de responder: o {@code output_config.effort} da Messages API.
 *
 * A constante é Java ({@code MEDIO}); a {@link #chave()} é o que vai no corpo do pedido
 * ({@code medium}). É o mesmo par de {@link dev.jarvis.custo.Origem} e de
 * {@link dev.jarvis.lembretes.Recorrencia}, e existe pelo mesmo motivo: o nome da constante é
 * nosso, o da chave é contrato de fora.
 *
 * <p>A API aceita cinco níveis ({@code low} a {@code max}) nos modelos atuais; aqui moram os três
 * que a configuração expõe. Valor fora desta lista é valor torto no {@code .env} e cai no padrão do
 * perfil com um WARN, como no {@code ttlDeCache()} — e não vira um HTTP 400 a cada pedido.
 */
public enum Esforco {

    BAIXO("low"),
    MEDIO("medium"),
    ALTO("high");

    private final String chave;

    Esforco(String chave) {
        this.chave = chave;
    }

    public String chave() {
        return chave;
    }

    /** O nível daquela chave, ou {@code null} — o que fazer com chave desconhecida é de quem lê. */
    public static Esforco porChave(String chave) {
        if (chave == null || chave.isBlank()) {
            return null;
        }
        String procurada = chave.trim().toLowerCase(Locale.ROOT);
        for (Esforco esforco : values()) {
            if (esforco.chave.equals(procurada)) {
                return esforco;
            }
        }
        return null;
    }
}
