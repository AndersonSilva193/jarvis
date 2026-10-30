package dev.jarvis.holograma;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uma ordem para a cena do holograma, como vai no SSE {@code holograma.comando}.
 *
 * <p>Tudo o que é número ou id já vem resolvido aqui: o alvo "os pistões" chega à HUD como a lista
 * de grupos e peças, e o "mais" como o fator final. A HUD nunca interpreta texto do modelo — ela só
 * confere o formato e aplica.
 *
 * @param dados  os complementos da ação ({@code fator}, {@code eixo}, {@code graus}, {@code grupos},
 *               {@code pecas}...), na ordem em que foram postos
 * @param rapido veio do caminho rápido, sem modelo: a HUD toca o som de confirmação em vez de falar
 */
public record ComandoDoHolograma(String acao, Map<String, Object> dados, boolean rapido) {

    public static final String ABRIR = "abrir";
    public static final String FECHAR = "fechar";
    public static final String CARREGAR = "carregar";
    public static final String EXPLODIR = "explodir";
    public static final String GIRAR = "girar";
    public static final String GIRAR_CONTINUO = "girar_continuo";
    public static final String PARAR = "parar";
    public static final String ZOOM = "zoom";
    public static final String VISTA = "vista";
    public static final String DESTACAR = "destacar";
    public static final String ISOLAR = "isolar";
    public static final String ESCONDER = "esconder";
    public static final String MOSTRAR_TUDO = "mostrar_tudo";
    public static final String SELECIONAR = "selecionar";
    public static final String RESETAR = "resetar";
    public static final String CANCELAR_GERACAO = "cancelar_geracao";

    /** O que a HUD sabe aplicar. {@code montar} é um {@code explodir} com fator zero. */
    public static final List<String> ACOES = List.of(ABRIR, FECHAR, CARREGAR, EXPLODIR, GIRAR,
            GIRAR_CONTINUO, PARAR, ZOOM, VISTA, DESTACAR, ISOLAR, ESCONDER, MOSTRAR_TUDO, SELECIONAR,
            RESETAR, CANCELAR_GERACAO);

    public ComandoDoHolograma {
        if (!ACOES.contains(acao)) {
            throw new IllegalArgumentException("ação de holograma desconhecida: " + acao);
        }
        dados = dados == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(dados));
    }

    public static ComandoDoHolograma de(String acao) {
        return new ComandoDoHolograma(acao, Map.of(), false);
    }

    public static ComandoDoHolograma de(String acao, Map<String, Object> dados) {
        return new ComandoDoHolograma(acao, dados, false);
    }

    public ComandoDoHolograma comoRapido() {
        return new ComandoDoHolograma(acao, dados, true);
    }
}
