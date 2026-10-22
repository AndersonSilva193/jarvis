package dev.jarvis.ferramentas;

import java.util.Locale;
import java.util.regex.Pattern;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.memoria.MemoriaService;
import dev.jarvis.util.Json;
import dev.jarvis.util.Texto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validação comum das ferramentas; os caminhos nunca entram nos esquemas. */
abstract class FerramentaMemoria implements Ferramenta {
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
    /**
     * Esquecer continua exigindo a palavra na abertura da mensagem, porque é o único caminho pelo
     * qual um registro <b>sai</b> de vista. Gravar e atualizar não exigem mais: o dono conta coisa
     * durável no meio da conversa, e cobrar "lembre que" na primeira palavra fazia a memória
     * depender de ele saber a senha. Quem segura essas duas é o {@link #exigirConteudoDoUsuario}.
     */
    protected static void exigirPedidoDeEsquecer(OuvinteProgresso p) {
        String original = p.pedidoOriginal().stripLeading().toLowerCase(Locale.ROOT);
        if (!ESQUECER.matcher(original).find())
            throw new IllegalArgumentException("Ação de memória exige pedido explícito do usuário");
    }
    protected static void exigirConteudoDoUsuario(OuvinteProgresso p, String conteudo) {
        String original = Texto.normalizar(p.pedidoOriginal());
        String literal = Texto.normalizar(conteudo);
        if (literal.isBlank() || !original.contains(literal))
            throw new IllegalArgumentException("Conteúdo não consta do pedido original; use trecho literal do usuário");
    }
    protected ObjectNode base() { return json.createObjectNode().put("type", "object"); }
    protected static ResultadoFerramenta resposta(String texto) { return ResultadoFerramenta.texto(texto); }
}
