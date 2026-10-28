package dev.jarvis.ia.provedor;

/**
 * Um bloco do prompt de sistema.
 *
 * @param cache {@code null} quando o bloco não é cacheado, ou a validade pedida ({@code 5m},
 *              {@code 1h}). "Manter este prefixo quente" é conceito de qualquer provedor com cache
 *              de prompt; como pedir isso é que é de cada um.
 */
public record BlocoDeSistema(String texto, String cache) {

    public BlocoDeSistema {
        texto = texto == null ? "" : texto;
    }
}
