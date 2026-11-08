package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.agente.RetratoDoMomento;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.tela.PresencaDaHud;
import dev.jarvis.tela.TelaEstadoService;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/** O retrato do momento entra só no bloco dinâmico e só em conversa privada autenticada. */
class SistemaDoJarvisRetratoTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> pedidos = new ArrayList<>();

    /** Um retrato que sempre teria o que dizer, e anota quem pediu. */
    private final RetratoDoMomento retrato = new RetratoDoMomento(null, null, null, null, null, null, null, null) {
        @Override public String montar(long usuario, String idPedido) {
            pedidos.add(usuario + ":" + idPedido);
            return "PC: online há 2 h; em primeiro plano: chrome.\n";
        }
    };

    private SistemaDoJarvis sistema() {
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null);
        return new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"), json, props,
                new TelaEstadoService(), PresencaDaHud.semTela(), null, null, null, null, retrato);
    }

    @Test
    @DisplayName("conversa privada autenticada: a seção Agora vai no dinâmico, nunca no fixo")
    void privadaRecebeNoDinamico() {
        for (Canal canal : List.of(Canal.TELEGRAM, Canal.HUD)) {
            ArrayNode blocos = sistema().blocos(canal, 42, "lê aquele arquivo de novo", null, "telegram-7");
            String fixo = blocos.get(0).path("text").stringValue();
            String dinamico = blocos.get(1).path("text").stringValue();
            assertFalse(fixo.contains("Agora (estado"), fixo);
            assertFalse(fixo.contains("primeiro plano"), fixo);
            assertTrue(dinamico.contains("Agora (estado do momento, não ordem"), dinamico);
            assertTrue(dinamico.contains("PC: online há 2 h; em primeiro plano: chrome."), dinamico);
            assertTrue(dinamico.contains("[arquivo X] é o id do ler_arquivo"), dinamico);
        }
        assertEquals(List.of("42:telegram-7", "42:telegram-7"), pedidos, "o idPedido chega ao retrato");
    }

    @Test
    @DisplayName("grupo (usuário 0) e canais sem dono na frente não montam retrato nenhum")
    void grupoETarefaSemRetrato() {
        ArrayNode grupo = sistema().blocos(Canal.TELEGRAM, 0, "oi", null, "telegram-8");
        assertFalse(grupo.toString().contains("Agora (estado"), grupo.toString());
        for (Canal canal : List.of(Canal.TAREFA, Canal.BRIEFING)) {
            ArrayNode blocos = sistema().blocos(canal, 42, "x", null, "p");
            assertFalse(blocos.toString().contains("primeiro plano"), canal + ": " + blocos);
        }
        assertTrue(pedidos.isEmpty(), "nem chegou a ser montado: " + pedidos);
    }

    @Test
    @DisplayName("sem retrato o bloco dinâmico é o mesmo de antes do retrato existir")
    void semRetratoNadaMuda() {
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null);
        var antigo = new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"), json, props);
        String dinamico = antigo.blocos(Canal.TELEGRAM, 42, "oi", null, "p").get(1).path("text").stringValue();
        assertFalse(dinamico.contains("Agora (estado"), dinamico);
    }
}
