package dev.jarvis.tela;

import java.util.List;
import java.util.Set;

/**
 * Uma ordem para a HUD sobre o que já está na tela.
 *
 * @param alvo  o id de uma janela do último estado recebido. Vazio nas ações que valem para a
 *              tela inteira (limpar, fechar_tudo, abrir_historico...).
 * @param valor o complemento da ação, sempre em texto: "30" para os segundos de um avançar, ou o
 *              título procurado em abrir_noticia. Texto único porque o schema fica simples e o
 *              JsonMapper do projeto é cru; quem precisa de número converte na hora.
 */
public record ComandoTela(String acao, String alvo, String valor) {

    /** As ações que a HUD entende. O schema da ferramenta expõe exatamente esta lista. */
    public static final List<String> ACOES = List.of(
            "limpar", "fechar", "fechar_tudo", "minimizar", "maximizar", "restaurar", "focar",
            "fixar", "desafixar", "abrir_historico", "fechar_historico", "mostrar_briefing",
            "repetir_briefing", "abrir_noticia", "pausar", "continuar", "volume_mais",
            "volume_menos", "avancar", "voltar");

    /** As ações que precisam de uma janela em {@code alvo}; as outras valem para a tela toda. */
    public static final Set<String> COM_ALVO = Set.of(
            "fechar", "minimizar", "maximizar", "restaurar", "focar", "fixar", "desafixar",
            "pausar", "continuar", "volume_mais", "volume_menos", "avancar", "voltar");

    public ComandoTela {
        if (!ACOES.contains(acao)) {
            throw new IllegalArgumentException("ação de tela desconhecida: " + acao);
        }
        alvo = alvo == null ? "" : alvo.trim();
        valor = valor == null ? "" : valor.trim();
    }

    public static boolean acaoValida(String acao) {
        return ACOES.contains(acao);
    }

    /** Se esta ação exige um alvo que exista no último estado da tela. */
    public static boolean exigeAlvo(String acao) {
        return COM_ALVO.contains(acao);
    }
}
