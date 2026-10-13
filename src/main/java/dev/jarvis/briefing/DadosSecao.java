package dev.jarvis.briefing;

import java.util.List;

/**
 * Os dados de uma seção do briefing em formato estruturado, para a HUD desenhar cards.
 *
 * A prosa de cada seção continua sendo a fonte da fala (e do Telegram); isto aqui é a mesma
 * informação sem formatação, para a tela mostrar número grande, imagem e lista. Cada seção tem a
 * sua forma, então quem consome faz pattern matching — a interface é selada justamente para o
 * compilador cobrar um caso novo quando uma seção nova aparecer.
 *
 * Nada de java.time aqui: o JsonMapper do BeansConfig é cru, então hora já vai formatada em texto.
 */
public sealed interface DadosSecao {

    /** Temperaturas em graus inteiros, iguais às da prosa, para o card não divergir da fala. */
    record Clima(
            String cidade,
            int temperaturaAtual,
            int minima,
            int maxima,
            int chanceChuvaPct,
            String descricao) implements DadosSecao {
    }

    /** Os lembretes de hoje, na ordem do dia. Lista vazia = nada marcado. */
    record Lembretes(List<Item> itens) implements DadosSecao {

        /** @param hora no formato HH:mm, já no fuso configurado. */
        public record Item(String hora, String texto) {
        }
    }

    /** As manchetes do briefing. O link e a imagem só existem aqui, não na prosa. */
    record Noticias(List<Item> itens) implements DadosSecao {

        /** @param imagemUrl vazio quando o feed não trouxe imagem: a HUD cai num painel sem foto. */
        public record Item(
                String titulo,
                String tema,
                String fonte,
                String link,
                String imagemUrl) {
        }
    }

    /** Os sites monitorados e uma linha de saúde do servidor. */
    record Sistemas(List<Site> sites, String servidor) implements DadosSecao {

        public record Site(String nome, String resultado) {
        }
    }
}
