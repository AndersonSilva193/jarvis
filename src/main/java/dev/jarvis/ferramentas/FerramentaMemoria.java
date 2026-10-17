package dev.jarvis.ferramentas;

import java.util.Locale;
import java.text.Normalizer;
import java.util.regex.Pattern;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.memoria.MemoriaService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validação comum das ferramentas; os caminhos nunca entram nos esquemas. */
abstract class FerramentaMemoria implements Ferramenta {
    private static final Pattern REGISTRAR = Pattern.compile("(?iu)^(lembre( que)?|guarde|registre|anote)\\b");
    private static final Pattern ATUALIZAR = Pattern.compile("(?iu)^(atualize|corrija|mude|altere)\\b");
    private static final Pattern ESQUECER = Pattern.compile("(?iu)^(esqueça|apague da memória|remova da memória)\\b");
    protected final MemoriaService memoria;
    protected final JsonMapper json;
    FerramentaMemoria(MemoriaService memoria, JsonMapper json) { this.memoria = memoria; this.json = json; }
    @Override public final String executar(JsonNode entrada) { return "Memória exige pedido autenticado."; }

    protected static long usuario(OuvinteProgresso p) {
        if (p.usuarioAutenticado() <= 0 || !("telegram".equals(p.canal()) || "hud".equals(p.canal())))
            throw new IllegalArgumentException("Memória requer usuário autenticado no Telegram ou HUD");
        return p.usuarioAutenticado();
    }
    protected static String texto(JsonNode entrada, String campo) { return Json.texto(entrada.path(campo)).trim(); }
    protected static long revisao(JsonNode entrada) { return Json.numero(entrada.path("revisao")); }
    protected static String pedido(OuvinteProgresso p) {
        if (p.idPedido() == null || p.idPedido().isBlank()) throw new IllegalArgumentException("Pedido sem identificador");
        return p.idPedido();
    }
    protected static void exigir(OuvinteProgresso p, String acao) {
        String original = p.pedidoOriginal().stripLeading().toLowerCase(Locale.ROOT);
        boolean permitido = switch (acao) {
            case "registrar" -> REGISTRAR.matcher(original).find();
            case "atualizar" -> ATUALIZAR.matcher(original).find()
                    || (REGISTRAR.matcher(original).find()
                        && Pattern.compile("(?iu)\\b(agora|mudei|mudou|corrijo|na verdade|em vez de|não mais)\\b")
                                .matcher(original).find());
            case "esquecer" -> ESQUECER.matcher(original).find();
            default -> false;
        };
        if (!permitido) throw new IllegalArgumentException("Ação de memória exige pedido explícito do usuário");
    }
    protected static void exigirConteudoDoUsuario(OuvinteProgresso p, String conteudo) {
        String original = normalizar(p.pedidoOriginal());
        String literal = normalizar(conteudo);
        if (literal.isBlank() || !original.contains(literal))
            throw new IllegalArgumentException("Conteúdo não consta do pedido original; use trecho literal do usuário");
    }
    private static String normalizar(String texto) {
        return Normalizer.normalize(texto.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("\\s+", " ").trim();
    }
    protected ObjectNode base() { return json.createObjectNode().put("type", "object"); }
    protected static ResultadoFerramenta resposta(String texto) { return ResultadoFerramenta.texto(texto); }
}
