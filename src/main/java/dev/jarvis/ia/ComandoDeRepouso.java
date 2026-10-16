package dev.jarvis.ia;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/** Comandos explícitos fecham a HUD; perguntas e frases maiores continuam no assistente. */
final class ComandoDeRepouso {
    private static final Set<String> FRASES = Set.of("modo repouso", "modo de repouso", "entrar em modo repouso",
            "entre em modo repouso", "fechar hud", "fechar a hud", "fecha hud", "fecha a hud",
            "feche hud", "feche a hud");
    static boolean reconhece(String texto) {
        if (texto == null) return false;
        String normalizado = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", " ").trim()
                .replaceFirst("^jarvis\\s+", "");
        return FRASES.contains(normalizado);
    }
}
