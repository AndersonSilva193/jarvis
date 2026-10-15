package dev.jarvis.tela;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Set;

/**
 * Uma janela que o Jarvis abre na HUD.
 *
 * O {@code id} é gerado aqui, no servidor, e a HUD cria a janela com ele e o devolve no
 * POST /api/tela/estado — é por esse id que o {@code controlar_tela} acha o alvo depois.
 *
 * @param origem de onde veio o pedido que abriu o card: telegram, hud, briefing ou tarefa. Um
 *               card pedido pelo celular abre na tela do mesmo jeito; saber de onde veio é o que
 *               permite à HUD tratar os dois casos sem confundir.
 * @param criadoEm data em texto ISO-8601 de propósito: o JsonMapper do projeto é cru e não
 *                 serializaria um Instant.
 */
public record CardTela(String id, String tipo, String titulo, DadosDoCard dados,
        String origem, String criadoEm) {

    public static final String PESQUISA = "pesquisa";
    public static final String VIDEO = "video";
    public static final String MUSICA = "musica";
    public static final String PAGINA = "pagina";
    public static final String NOTICIA = "noticia";

    /** Os cinco tipos que a HUD sabe desenhar. */
    public static final Set<String> TIPOS = Set.of(PESQUISA, VIDEO, MUSICA, PAGINA, NOTICIA);

    private static final SecureRandom SORTEIO = new SecureRandom();
    private static final String ALFABETO = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final int TAMANHO_ID = 4;

    public CardTela {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("card sem id");
        }
        if (!TIPOS.contains(tipo)) {
            throw new IllegalArgumentException("tipo de card desconhecido: " + tipo);
        }
        if (titulo == null || titulo.isBlank()) {
            throw new IllegalArgumentException("card sem título");
        }
        origem = origem == null || origem.isBlank() ? "desconhecido" : origem;
    }

    /**
     * Um id novo: "c" mais quatro caracteres sorteados ("c7k2").
     *
     * Sorteado, e não sequencial, porque o container reinicia com a HUD aberta: um contador
     * voltaria a c1 e colidiria com uma janela que ainda está na tela. Curto porque ele entra no
     * bloco dinâmico do system a cada pedido, e porque o usuário pode dizê-lo em voz alta.
     */
    public static String novoId() {
        StringBuilder id = new StringBuilder(TAMANHO_ID + 1).append('c');
        for (int i = 0; i < TAMANHO_ID; i++) {
            id.append(ALFABETO.charAt(SORTEIO.nextInt(ALFABETO.length())));
        }
        return id.toString();
    }

    /** Um card recém-montado: id sorteado e o relógio do servidor. */
    public static CardTela agora(String tipo, String titulo, DadosDoCard dados, String origem) {
        return new CardTela(novoId(), tipo, titulo, dados, origem, Instant.now().toString());
    }
}
