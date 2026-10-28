package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.aprovacao.ClassificadorDeResposta;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.AnexosService;
import dev.jarvis.conversa.HistoricoEmArquivo;
import dev.jarvis.conversa.RegistroConversas;
import dev.jarvis.conversa.ResultadosTardios;
import dev.jarvis.custo.Origem;
import dev.jarvis.ferramentas.Ferramentas;
import dev.jarvis.ia.provedor.BancadaLocal;
import dev.jarvis.ia.provedor.ModoDeIa;
import dev.jarvis.ia.provedor.ProvedorAnthropic;
import dev.jarvis.ia.provedor.SeletorDeModelo;
import dev.jarvis.memoria.EsquecimentosDeMemoria;
import dev.jarvis.proativo.FalasService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** "Modo local" e "qual modelo" são do servidor, nos dois canais; "pergunta pro Claude" é pontual. */
class AssistenteModoTest {

    private static final JsonMapper JSON = BancadaLocal.JSON;

    @TempDir Path pasta;
    private BancadaLocal bancada;
    private final ClaudeClient claude = mock(ClaudeClient.class);

    @AfterEach
    void fechar() throws Exception {
        if (bancada != null) bancada.close();
    }

    private SeletorDeModelo seletor() {
        bancada = new BancadaLocal(pasta);
        JarvisProperties.Pc pc = bancada.props.pc();
        JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("token", 0L),
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, pc, null, null, null, null, null,
                bancada.props.custo());
        return new SeletorDeModelo(bancada.disponibilidade, props, JSON, evento -> { }, null,
                new FalasService(), null, Clock.systemUTC(), pasta.resolve("modo-ia.json"));
    }

    private Assistente assistente(SeletorDeModelo seletor) {
        JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null);
        return new Assistente(ProvedorAnthropic.sobre(claude, JSON), new Ferramentas(List.of(), JSON),
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"), JSON, props),
                JSON, new RegistroConversas(evento -> { }), new AnexosService(), BuscaWebServidor.desligada(),
                new Aprovacoes(), HistoricoEmArquivo.desligado(), ClassificadorDeResposta.semModelo(), null,
                new EsquecimentosDeMemoria(), CompactadorDeHistorico.padrao(JSON), new ResultadosTardios(),
                seletor);
    }

    @Test void trocaDeModoNosDoisCanaisSemModelo() throws Exception {
        SeletorDeModelo seletor = seletor();
        Assistente assistente = assistente(seletor);

        assertEquals("Certo, agora penso com o modelo local.",
                assistente.responder(1, "Usa o modelo local!", Canal.TELEGRAM));
        assertEquals(ModoDeIa.LOCAL, seletor.modo());
        assertEquals("Já estou no modo local.", assistente.responder(1, "modo local", Canal.HUD));
        assertEquals("Certo, agora penso com o Claude.", assistente.responder(1, "Modo Claude.", Canal.HUD));
        assertEquals(ModoDeIa.CLAUDE, seletor.modo());
        verifyNoInteractions(claude);
    }

    @Test void localComOPcForaDizQueUsaOClaude() throws Exception {
        SeletorDeModelo seletor = seletor();
        bancada.cabecalho(BancadaLocal.PRONTA.replace("ocupada=0", "ocupada=1"));
        assertEquals("Certo, agora penso com o modelo local. O PC não está disponível agora; até ele "
                + "voltar, uso o Claude.", assistente(seletor).responder(1, "modo local", Canal.TELEGRAM));
    }

    @Test void perguntaSobreOModeloRespondidaPeloServidor() throws Exception {
        SeletorDeModelo seletor = seletor();
        Assistente assistente = assistente(seletor);

        assertEquals("Modo automático. As tarefas de apoio estão com o modelo local (qwen3.5:9b); a "
                + "conversa continua com o Claude (claude-haiku-5-5).",
                assistente.responder(1, "Qual modelo você está usando?", Canal.TELEGRAM));
        bancada.cabecalho(BancadaLocal.PRONTA.replace("ocupada=0", "ocupada=1"));
        assertEquals("Modo automático. As tarefas de apoio estão com o Claude, porque a GPU do PC está "
                + "ocupada; a conversa continua com o Claude (claude-haiku-5-5).",
                assistente.responder(1, "qual modelo vc tá usando?", Canal.HUD));
        verifyNoInteractions(claude);
    }

    @Test void pontualVaiAoClaudeSemPrefixoESemMudarOModo() throws Exception {
        SeletorDeModelo seletor = seletor();
        AtomicReference<SeletorDeModelo.Escolha> duranteOTurno = new AtomicReference<>();
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenAnswer(chamada -> {
                    duranteOTurno.set(seletor.escolher(Origem.CLASSIFICADOR));
                    return resposta("4.");
                });

        assertEquals("4.", assistente(seletor).responder(1, "Pergunta pro Claude: quanto é 2+2?", Canal.TELEGRAM));

        ArgumentCaptor<ArrayNode> mensagens = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), mensagens.capture(), any(ArrayNode.class));
        String enviado = mensagens.getValue().toString();
        assertTrue(enviado.contains("quanto é 2+2?"), enviado);
        assertFalse(enviado.toLowerCase().contains("pergunta pro claude"), enviado);
        assertEquals(SeletorDeModelo.PONTUAL, duranteOTurno.get().motivo(),
                "dentro do turno, toda escolha é o Claude");
        assertEquals(ModoDeIa.AUTO, seletor.modo());
        assertTrue(seletor.escolher(Origem.CLASSIFICADOR).local(), "depois do turno, o modo volta a valer");
    }

    private static JsonNode resposta(String texto) {
        ObjectNode resposta = JSON.createObjectNode().put("stop_reason", "end_turn");
        resposta.putArray("content").addObject().put("type", "text").put("text", texto);
        return resposta;
    }
}
