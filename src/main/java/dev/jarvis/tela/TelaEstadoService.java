package dev.jarvis.tela;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

/**
 * Guarda o último retrato da tela mandado pela HUD — só o último, em memória.
 *
 * Duas pontas consomem isto: o {@code SistemaDoJarvis}, que põe uma linha por janela no bloco
 * dinâmico do system para o modelo saber o que está aberto, e o {@code controlar_tela}, que
 * confere se o alvo pedido existe de verdade antes de mandar qualquer ordem.
 */
@Service
public class TelaEstadoService {

    /** A HUD reposta a cada mudança; passado disso, o retrato é velho demais para o prompt. */
    private static final Duration VALIDADE = Duration.ofMinutes(10);
    /** Teto de linhas no system: tela cheia não pode comer o contexto do pedido. */
    static final int MAX_LINHAS = 12;
    private static final int MAX_TITULO = 60;

    /** Tipos cujo nome é feminino, para a linha sair "fixada" e não "fixado". */
    private static final Set<String> FEMININOS =
            Set.of(CardTela.PESQUISA, CardTela.MUSICA, CardTela.PAGINA, CardTela.NOTICIA, "resposta");

    private volatile EstadoDaTela ultimo;

    public void guardar(EstadoDaTela estado) {
        this.ultimo = estado;
    }

    /** O último retrato, se houver um e ainda estiver fresco. */
    public Optional<EstadoDaTela> ultimo() {
        EstadoDaTela guardado = ultimo;
        return guardado != null && guardado.fresco(Instant.now(), VALIDADE)
                ? Optional.of(guardado)
                : Optional.empty();
    }

    /** A janela de um id, se ela está no último retrato. É a validação de alvo do controlar_tela. */
    public Optional<EstadoDaTela.Janela> janela(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return ultimo()
                .map(EstadoDaTela::janelas)
                .flatMap(janelas -> janelas.stream()
                        .filter(janela -> id.trim().equals(janela.id()))
                        .findFirst());
    }

    /** As linhas do bloco dinâmico do system, já com o teto aplicado. */
    public List<String> linhas() {
        return ultimo().map(estado -> linhas(estado, MAX_LINHAS)).orElseGet(List::of);
    }

    /**
     * O que devolver ao modelo quando ele pediu um alvo que não existe: a lista do que está
     * aberto, para ele acertar na segunda tentativa em vez de insistir no id errado.
     */
    public String listaParaOModelo() {
        List<String> abertas = linhas();
        return abertas.isEmpty()
                ? "Não há nada aberto na tela agora."
                : "Esse id não está na tela. Abertos: " + String.join("; ", abertas);
    }

    /**
     * Uma linha por janela, no formato {@code [c7k2] vídeo 'Lo-fi para estudar' (tocando)}.
     *
     * <p>Estática e pura para o teste conferir o formato sem Spring. O histórico e o briefing
     * rendem uma linha cada, quando abertos, e entram no mesmo teto.
     */
    public static List<String> linhas(EstadoDaTela estado, int maximo) {
        if (estado == null || maximo <= 0) {
            return List.of();
        }
        List<String> linhas = new ArrayList<>();
        for (EstadoDaTela.Janela janela : estado.janelas()) {
            if (linhas.size() >= maximo) {
                return List.copyOf(linhas);
            }
            linhas.add(linhaDe(janela));
        }
        if (estado.historicoAberto() && linhas.size() < maximo) {
            linhas.add("o histórico da conversa está aberto");
        }
        if (estado.briefingNaTela() && linhas.size() < maximo) {
            linhas.add("o briefing do dia está na tela");
        }
        return List.copyOf(linhas);
    }

    private static String linhaDe(EstadoDaTela.Janela janela) {
        StringBuilder linha = new StringBuilder()
                .append('[').append(janela.id()).append("] ")
                .append(nomeDoTipo(janela.tipo()))
                .append(" '").append(cortar(janela.titulo())).append('\'');
        List<String> adjetivos = adjetivos(janela);
        if (!adjetivos.isEmpty()) {
            linha.append(" (").append(String.join(", ", adjetivos)).append(')');
        }
        return linha.toString();
    }

    private static List<String> adjetivos(EstadoDaTela.Janela janela) {
        boolean feminino = FEMININOS.contains(janela.tipo());
        List<String> adjetivos = new ArrayList<>();
        if ("minimizado".equals(janela.estado())) {
            adjetivos.add(feminino ? "minimizada" : "minimizado");
        } else if ("maximizado".equals(janela.estado())) {
            adjetivos.add(feminino ? "maximizada" : "maximizado");
        }
        if (janela.fixado()) {
            adjetivos.add(feminino ? "fixada" : "fixado");
        }
        if (janela.emFoco()) {
            adjetivos.add("em foco");
        }
        if (janela.tocando()) {
            adjetivos.add("tocando");
        }
        return adjetivos;
    }

    /** O nome do tipo como se fala, com acento — a linha também é lida pelo modelo em voz alta. */
    private static String nomeDoTipo(String tipo) {
        return switch (tipo == null ? "" : tipo) {
            case CardTela.VIDEO -> "vídeo";
            case CardTela.MUSICA -> "música";
            case CardTela.PAGINA -> "página";
            case CardTela.NOTICIA -> "notícia";
            case CardTela.PESQUISA -> "pesquisa";
            case "briefing" -> "briefing";
            case "resposta" -> "resposta";
            default -> tipo == null || tipo.isBlank() ? "janela" : tipo;
        };
    }

    private static String cortar(String titulo) {
        if (titulo == null || titulo.isBlank()) {
            return "sem título";
        }
        String limpo = titulo.strip().replace('\'', '"').replaceAll("\\s+", " ");
        return limpo.length() <= MAX_TITULO ? limpo : limpo.substring(0, MAX_TITULO - 1).strip() + "…";
    }
}
