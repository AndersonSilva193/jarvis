package dev.jarvis.holograma;

import tools.jackson.databind.node.ObjectNode;

/**
 * Uma linha do NDJSON reconhecida pelo {@link HologramaLineParser}.
 *
 * <p>A meta já sai normalizada, porque são três textos. A peça sai <b>compacta</b>, como o modelo
 * a escreveu: quem a confere, expande e limita é o {@link HologramaValidator}, que guarda estado
 * do objeto inteiro (ids já usados, total de peças) — coisa que uma linha sozinha não sabe.
 */
public sealed interface EventoHolograma {

    record Meta(HologramaMeta meta) implements EventoHolograma {
    }

    record Peca(ObjectNode compacta) implements EventoHolograma {
    }

    record Fim() implements EventoHolograma {
    }
}
