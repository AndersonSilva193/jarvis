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

        /**
         * @param hora        no formato HH:mm, já no fuso configurado.
         * @param recorrencia como a série se repete ("todos os dias", "dias úteis"...), vazio em
         *                    lembrete de uma só vez. Campo novo e opcional: a HUD que não o conhece
         *                    simplesmente o ignora.
         */
        public record Item(String hora, String texto, String recorrencia) {

            public Item(String hora, String texto) {
                this(hora, texto, "");
            }
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

    /**
     * O que o Jarvis fez sozinho desde o último briefing, contado por tipo.
     *
     * Só a contagem, nunca a lista: a seção existe para o dono saber que houve, e o detalhe é da
     * ferramenta {@code acoes_do_jarvis}. Oito linhas de "editei tal arquivo" num briefing falado
     * seriam o inverso de um resumo.
     *
     * @param itens do mais frequente para o menos. Nunca vazio — sem ação a seção não aparece.
     */
    record Acoes(List<Item> itens) implements DadosSecao {

        /** @param tipo o rótulo falado ("edições de arquivo"), não a constante Java. */
        public record Item(String tipo, int quantidade) {
        }
    }
}
