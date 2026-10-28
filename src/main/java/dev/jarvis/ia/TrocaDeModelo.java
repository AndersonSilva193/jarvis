package dev.jarvis.ia;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.jarvis.ia.provedor.ModoDeIa;
import dev.jarvis.ia.provedor.SeletorDeModelo;
import dev.jarvis.util.Texto;

/**
 * "Modo local", "usa o Claude", "qual modelo você está usando?": a troca de modelo e a pergunta
 * sobre ela, reconhecidas pelo servidor antes de qualquer modelo, no Telegram e na HUD.
 *
 * <p>Estreito como o {@link PedidoDeParada}, e pelo mesmo motivo: até {@link #MAX_PALAVRAS}
 * palavras (sem contar "Jarvis") e nenhuma palavra fora de um conjunto fechado. "Usa o modelo local
 * para resumir o texto de ontem" tem objeto, é pedido — e vai ao modelo.
 *
 * <p>A troca <b>pontual</b> é outra coisa: "pergunta pro Claude: ..." vai ao Claude só naquele
 * pedido, sem mexer no modo. O prefixo sai do texto, e o resto é o pedido.
 *
 * <p>As respostas são montadas aqui, em Java, de dado real (o modo gravado, o efetivo do seletor,
 * o nome do modelo): é o servidor afirmando o que ele mesmo fez.
 */
public final class TrocaDeModelo {

    private static final int MAX_PALAVRAS = 6;

    private static final Set<String> VERBOS = Set.of("usa", "use", "usar", "muda", "mude", "mudar",
            "passa", "passe", "passar", "troca", "troque", "trocar", "volta", "volte", "voltar",
            "fica", "fique");
    private static final Set<String> ENFEITES_DA_TROCA = Set.of("modo", "modelo", "ia", "o", "a",
            "pro", "pra", "para", "no", "de", "com", "por", "favor", "agora", "entao");
    private static final Set<String> ENFEITES_DA_PERGUNTA = Set.of("qual", "que", "modelo", "modo",
            "o", "voce", "vc", "esta", "ta", "usando", "usa", "agora", "de", "ia", "com",
            "pensando", "e");

    /**
     * No texto original, para devolver o pedido intacto. "Jarvis," opcional na frente; o separador
     * (dois-pontos ou vírgula) é obrigatório — sem ele, "pergunta pro Claude se..." é só uma frase.
     */
    private static final Pattern PONTUAL = Pattern.compile(
            "^\\s*(?:jarvis\\s*,?\\s*)?(?:pergunt[ae]r?\\s+(?:pro|pra|para\\s+o|ao)\\s+claude"
                    + "|us[ae]\\s+o\\s+claude\\s+(?:pra|para)\\s+isso)\\s*[:,]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

    /** O que a frase pediu. */
    public sealed interface Comando permits Mudar, Consultar { }

    public record Mudar(ModoDeIa modo) implements Comando { }

    public record Consultar() implements Comando { }

    private TrocaDeModelo() {
    }

    /** A troca ou a pergunta, se a frase for só isso. */
    public static Optional<Comando> comando(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String normalizado = Texto.normalizar(texto).replaceAll("[\\p{Punct}¿¡]+", " ")
                .replaceAll("\\s+", " ").trim();
        if (normalizado.isEmpty()) {
            return Optional.empty();
        }
        String[] palavras = java.util.Arrays.stream(normalizado.split(" "))
                .filter(p -> !p.equals("jarvis")).toArray(String[]::new);
        if (palavras.length == 0 || palavras.length > MAX_PALAVRAS) {
            return Optional.empty();
        }
        Optional<Comando> pergunta = consulta(palavras);
        return pergunta.isPresent() ? pergunta : troca(palavras);
    }

    private static Optional<Comando> consulta(String[] palavras) {
        if (!palavras[0].equals("qual") && !palavras[0].equals("que")) {
            return Optional.empty();
        }
        boolean falaDeModelo = false;
        for (String palavra : palavras) {
            if (palavra.equals("modelo") || palavra.equals("modo")) {
                falaDeModelo = true;
            } else if (!ENFEITES_DA_PERGUNTA.contains(palavra)) {
                return Optional.empty();
            }
        }
        return falaDeModelo ? Optional.of(new Consultar()) : Optional.empty();
    }

    private static Optional<Comando> troca(String[] palavras) {
        ModoDeIa alvo = null;
        boolean temGatilho = false;
        for (String palavra : palavras) {
            ModoDeIa modo = switch (palavra) {
                case "local" -> ModoDeIa.LOCAL;
                case "claude" -> ModoDeIa.CLAUDE;
                case "automatico", "auto" -> ModoDeIa.AUTO;
                default -> null;
            };
            if (modo != null) {
                // Dois modos na mesma frase ("modo local e claude") não é troca, é conversa.
                if (alvo != null) return Optional.empty();
                alvo = modo;
            } else if (VERBOS.contains(palavra) || palavra.equals("modo")) {
                temGatilho = true;
            } else if (!ENFEITES_DA_TROCA.contains(palavra)) {
                return Optional.empty();
            }
        }
        // Sem "modo" nem verbo, "local" sozinho não troca nada.
        return alvo != null && temGatilho ? Optional.of(new Mudar(alvo)) : Optional.empty();
    }

    /** O pedido sem o prefixo pontual, ou vazio se não houver prefixo ou nada depois dele. */
    public static Optional<String> pontual(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        Matcher casou = PONTUAL.matcher(texto);
        if (!casou.matches()) {
            return Optional.empty();
        }
        String pedido = casou.group(1).trim();
        return pedido.isEmpty() ? Optional.empty() : Optional.of(pedido);
    }

    /** Executa o comando e devolve a frase que vai ao dono. */
    public static String atender(Comando comando, SeletorDeModelo seletor, String canal) {
        return switch (comando) {
            case Mudar mudar -> mudar(mudar.modo(), seletor, canal);
            case Consultar consultar -> consultar(seletor);
        };
    }

    private static String mudar(ModoDeIa modo, SeletorDeModelo seletor, String canal) {
        if (!seletor.mudar(modo, canal)) {
            return "Já estou no modo " + modo.rotulo() + ".";
        }
        StringBuilder frase = new StringBuilder(switch (modo) {
            case LOCAL -> "Certo, agora penso com o modelo local.";
            case CLAUDE -> "Certo, agora penso com o Claude.";
            case AUTO -> "Certo, agora escolho sozinho: o modelo local quando o PC estiver "
                    + "disponível, o Claude quando não.";
        });
        if (modo != ModoDeIa.CLAUDE) {
            SeletorDeModelo.Retrato agora = seletor.retrato();
            if (SeletorDeModelo.IA_LOCAL_DESLIGADA.equals(agora.motivo())) {
                frase.append(" A IA local está desligada na configuração, então por enquanto tudo "
                        + "vai ao Claude.");
            } else if (modo == ModoDeIa.LOCAL && agora.provedor() == SeletorDeModelo.Provedor.CLAUDE) {
                frase.append(" O PC não está disponível agora; até ele voltar, uso o Claude.");
            }
        }
        return frase.toString();
    }

    /**
     * Nesta etapa o seletor decide só as tarefas de apoio, e a resposta diz isso: afirmar "estou
     * pensando com o modelo local" com a conversa no Claude seria o servidor mentindo sobre si.
     */
    private static String consultar(SeletorDeModelo seletor) {
        SeletorDeModelo.Retrato agora = seletor.retrato();
        String quem = agora.provedor() == SeletorDeModelo.Provedor.LOCAL
                ? "com o modelo local (" + agora.modelo() + ")"
                : "com o Claude";
        if (agora.provedor() == SeletorDeModelo.Provedor.CLAUDE && !agora.motivo().isBlank()) {
            quem += ", porque " + SeletorDeModelo.motivoFalado(agora.motivo());
        }
        String claude = seletor.modeloDoClaude().isBlank() ? ""
                : " (" + seletor.modeloDoClaude() + ")";
        return "Modo " + agora.modo().rotulo() + ". As tarefas de apoio estão " + quem
                + "; a conversa continua com o Claude" + claude + ".";
    }
}
