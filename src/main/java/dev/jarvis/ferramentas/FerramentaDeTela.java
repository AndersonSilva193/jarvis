package dev.jarvis.ferramentas;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.tela.PresencaDaHud;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O que as quatro ferramentas de tela têm em comum: a guarda de "tem HUD aberta?" e o jeito de
 * descobrir de onde veio o pedido.
 *
 * Nenhuma delas faz sentido sem tela: abrir um card para ninguém gastaria uma ida de ferramenta
 * e deixaria o modelo prometendo algo que não aconteceu. Então a resposta, quando não há HUD
 * conectada, é dizer isso ao modelo e não publicar nada.
 */
abstract class FerramentaDeTela implements Ferramenta {

    static final String SEM_TELA = "A tela não está aberta, senhor. Responda só em voz ou texto, "
            + "sem prometer nenhum card.";

    protected final PresencaDaHud hud;
    protected final JsonMapper json;

    protected FerramentaDeTela(PresencaDaHud hud, JsonMapper json) {
        this.hud = hud;
        this.json = json;
    }

    protected boolean telaFechada() {
        return !hud.conectada();
    }

    /** O canal do pedido, que vira a origem do card: telegram, hud, briefing ou tarefa. */
    protected static String origem(OuvinteProgresso progresso) {
        String canal = progresso == null ? "" : progresso.canal();
        return canal == null || canal.isBlank() ? "desconhecido" : canal;
    }

    /**
     * Chamada sem ouvinte de progresso. Só o {@code executarDetalhado} abre card, porque é lá que
     * dá para saber o canal e devolver as listas de cards e comandos.
     */
    @Override
    public String executar(JsonNode entrada) {
        return SEM_TELA;
    }
}
