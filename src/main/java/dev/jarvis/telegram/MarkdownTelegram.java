package dev.jarvis.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converte o markdown simples que o Claude escreve para o HTML que o Telegram entende
 * (<a href="https://core.telegram.org/bots/api#html-style">parse_mode HTML</a>).
 *
 * O Telegram aceita poucas tags — {@code <b> <i> <u> <s> <code> <pre> <a> <blockquote>} — e
 * <b>não tem tag de lista</b>, então "- item" vira um marcador em texto puro. Qualquer
 * {@code < > &} do resto do texto é escapado, inclusive dentro de código: é isso que impede um
 * trecho de Java com generics de derrubar a mensagem inteira.
 */
public final class MarkdownTelegram {

    /** Marcador temporário dos trechos de código. O caractere nulo é removido da entrada antes. */
    private static final char MARCA = '\u0000';

    private static final Pattern CERCADO =
            Pattern.compile("```[ \\t]*([A-Za-z0-9_+#.-]*)[ \\t]*\\r?\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern CERCA_SOLTA = Pattern.compile("```[ \\t]*([A-Za-z0-9_+#.-]*)[ \\t]*\\r?\\n?");
    private static final Pattern EMBUTIDO = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern NEGRITO = Pattern.compile("\\*\\*(\\S(?:[^\\n]*?\\S)?)\\*\\*");
    private static final Pattern ITEM_DE_LISTA = Pattern.compile("(?m)^([ \\t]*)[-*][ \\t]+");

    private MarkdownTelegram() {
    }

    /** Converte o markdown em HTML do Telegram. Entrada vazia devolve string vazia. */
    public static String paraHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        List<String> codigos = new ArrayList<>();
        String texto = markdown.replace(MARCA, ' ');

        texto = guardarCercados(texto, codigos);
        texto = guardarEmbutidos(texto, codigos);

        texto = escapar(texto);
        texto = ITEM_DE_LISTA.matcher(texto).replaceAll("$1• ");
        texto = NEGRITO.matcher(texto).replaceAll("<b>$1</b>");

        return devolverCodigos(texto, codigos);
    }

    /**
     * Quebra o markdown em pedaços cujo HTML cabe no limite, cortando só entre linhas — assim
     * nenhuma tag é partida ao meio. Quando o corte cai dentro de um bloco ```, a cerca é fechada
     * no fim do pedaço e reaberta no começo do seguinte.
     */
    public static List<String> dividir(String markdown, int limiteHtml) {
        List<String> partes = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return partes;
        }

        StringBuilder atual = new StringBuilder();
        boolean dentroDeCerca = false;

        for (String linha : linhasQueCabem(markdown, limiteHtml)) {
            boolean estadoDepois = ehCerca(linha) != dentroDeCerca;
            String tentativa = atual.isEmpty() ? linha : atual + "\n" + linha;

            if (atual.isEmpty() || cabe(tentativa, estadoDepois, limiteHtml)) {
                atual.setLength(0);
                atual.append(tentativa);
                dentroDeCerca = estadoDepois;
                continue;
            }

            partes.add(fechando(atual.toString(), dentroDeCerca));
            atual.setLength(0);
            if (dentroDeCerca && ehCerca(linha)) {
                dentroDeCerca = false;
            } else if (dentroDeCerca) {
                atual.append("```\n").append(linha);
            } else {
                atual.append(linha);
                dentroDeCerca = ehCerca(linha);
            }
        }

        if (!atual.toString().isBlank()) {
            partes.add(fechando(atual.toString(), dentroDeCerca));
        }
        return partes;
    }

    private static boolean cabe(String markdown, boolean cercaAberta, int limiteHtml) {
        return paraHtml(fechando(markdown, cercaAberta)).length() <= limiteHtml;
    }

    private static String fechando(String markdown, boolean cercaAberta) {
        return cercaAberta ? markdown + "\n```" : markdown;
    }

    private static boolean ehCerca(String linha) {
        return linha.stripLeading().startsWith("```");
    }

    /** Pré-quebra linhas que sozinhas já estourariam o limite, preferindo cortar em espaço. */
    private static List<String> linhasQueCabem(String markdown, int limiteHtml) {
        // Folga para a cerca que pode ser reaberta e fechada em volta do pedaço.
        int limite = Math.max(16, limiteHtml - 16);
        List<String> linhas = new ArrayList<>();
        for (String linha : markdown.split("\n", -1)) {
            if (paraHtml(linha).length() <= limite) {
                linhas.add(linha);
                continue;
            }
            StringBuilder pedaco = new StringBuilder();
            for (String palavra : linha.split(" ")) {
                String tentativa = pedaco.isEmpty() ? palavra : pedaco + " " + palavra;
                if (!pedaco.isEmpty() && paraHtml(tentativa).length() > limite) {
                    linhas.add(pedaco.toString());
                    pedaco.setLength(0);
                    tentativa = palavra;
                }
                pedaco.setLength(0);
                pedaco.append(tentativa);
                // Uma "palavra" sozinha maior que o limite (uma URL enorme, por exemplo).
                while (paraHtml(pedaco.toString()).length() > limite) {
                    int corte = pedaco.length();
                    while (corte > 1 && paraHtml(pedaco.substring(0, corte)).length() > limite) {
                        corte = corte / 2;
                    }
                    linhas.add(pedaco.substring(0, corte));
                    pedaco.delete(0, corte);
                }
            }
            if (!pedaco.isEmpty()) {
                linhas.add(pedaco.toString());
            }
        }
        return linhas;
    }

    private static String guardarCercados(String texto, List<String> codigos) {
        Matcher m = CERCADO.matcher(texto);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(guardar(codigos, bloco(m.group(1), m.group(2)))));
        }
        m.appendTail(sb);

        // Cerca aberta e nunca fechada: o resto do texto vira código, em vez de vazar ``` na tela.
        Matcher solta = CERCA_SOLTA.matcher(sb.toString());
        if (solta.find()) {
            String antes = sb.substring(0, solta.start());
            String corpo = sb.substring(solta.end());
            return antes + guardar(codigos, bloco(solta.group(1), corpo));
        }
        return sb.toString();
    }

    private static String guardarEmbutidos(String texto, List<String> codigos) {
        Matcher m = EMBUTIDO.matcher(texto);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String html = "<code>" + escapar(m.group(1)) + "</code>";
            m.appendReplacement(sb, Matcher.quoteReplacement(guardar(codigos, html)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String bloco(String linguagem, String corpo) {
        String abertura = linguagem == null || linguagem.isBlank()
                ? "<pre><code>"
                : "<pre><code class=\"language-" + escapar(linguagem) + "\">";
        return abertura + escapar(corpo.stripTrailing()) + "</code></pre>";
    }

    private static String guardar(List<String> codigos, String html) {
        codigos.add(html);
        return MARCA + String.valueOf(codigos.size() - 1) + MARCA;
    }

    private static String devolverCodigos(String texto, List<String> codigos) {
        for (int i = 0; i < codigos.size(); i++) {
            texto = texto.replace(MARCA + String.valueOf(i) + MARCA, codigos.get(i));
        }
        return texto;
    }

    private static String escapar(String texto) {
        return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
