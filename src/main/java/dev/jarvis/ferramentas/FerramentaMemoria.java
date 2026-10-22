package dev.jarvis.ferramentas;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.memoria.MemoriaService;
import dev.jarvis.util.Json;
import dev.jarvis.util.Texto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validação comum das ferramentas; os caminhos nunca entram nos esquemas. */
abstract class FerramentaMemoria implements Ferramenta {
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
     * A prova de citação: o que o modelo grava tem de estar ancorado numa frase que o usuário
     * escreveu de verdade.
     *
     * <p>Confere a <b>evidência</b>, não o conteúdo, e é essa a diferença que faz uma correção em
     * linguagem natural funcionar: "isso mudou, agora é React" permite gravar "Usa React no
     * AgendaBarber" com a evidência "agora é React". Cobrar o conteúdo literal deixava a memória
     * cheia de frases cruas, ou simplesmente recusava a correção quando o modelo escrevia bem.
     *
     * <p>É o desenho que o {@code AnotadorDeMemoria.consta} já usa, com a mesma
     * {@link Texto#normalizar} de propósito: duas normalizações parecidas deixariam a trava valer
     * uma coisa na ferramenta e outra no anotador.
     *
     * <p>Não há mais exigência de palavra na abertura da mensagem — nem para gravar, nem para
     * corrigir, nem para esquecer. O dono conta coisa durável no meio da conversa, e cobrar "lembre
     * que" na primeira palavra fazia a memória depender de ele saber a senha. O que segura o
     * esquecimento agora é o {@code Aprovacoes}: uma pergunta montada em Java, nomeando o registro,
     * que o usuário responde como quiser.
     */
    protected static void exigirEvidenciaDoUsuario(OuvinteProgresso p, String evidencia) {
        String original = Texto.normalizar(p.pedidoOriginal());
        String literal = Texto.normalizar(evidencia);
        if (literal.isBlank() || !original.contains(literal))
            throw new IllegalArgumentException(
                    "Evidência não consta do pedido original; use trecho literal do usuário");
    }
    protected ObjectNode base() { return json.createObjectNode().put("type", "object"); }
    protected static ResultadoFerramenta resposta(String texto) { return ResultadoFerramenta.texto(texto); }
}
