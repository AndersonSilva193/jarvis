package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.aprovacao.ClassificadorDeResposta;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.AnexosService;
import dev.jarvis.conversa.HistoricoEmArquivo;
import dev.jarvis.conversa.RegistroConversas;
import dev.jarvis.conversa.RespostaConversa;
import dev.jarvis.conversa.ResultadosTardios;
import dev.jarvis.ferramentas.Ferramentas;
import dev.jarvis.ferramentas.HologramaFerramentaDeTeste;
import dev.jarvis.holograma.AtalhoDoHolograma;
import dev.jarvis.holograma.ComandoDoHolograma;
import dev.jarvis.holograma.EstadoDoHolograma;
import dev.jarvis.holograma.EstadoDoHologramaService;
import dev.jarvis.holograma.GaleriaDeHologramas;
import dev.jarvis.ia.provedor.Bloco;
import dev.jarvis.ia.provedor.PedidoAoModelo;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.ia.provedor.RespostaDoModelo;
import dev.jarvis.memoria.EsquecimentosDeMemoria;
import dev.jarvis.tela.PresencaDaHud;
import dev.jarvis.tela.TelaEvento;
import tools.jackson.databind.json.JsonMapper;

/**
 * O holograma pela conversa: um pedido composto vira várias chamadas da mesma ferramenta, executadas
 * em ordem, e o comando curto com o modo aberto não chega ao modelo.
 */
class AssistenteHologramaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EstadoDoHologramaService estado = new EstadoDoHologramaService();
    private final PresencaDaHud hud = () -> 1;
    private final List<TelaEvento> publicados = new ArrayList<>();
    private final AtomicInteger chamadasAoModelo = new AtomicInteger();

    /** Primeira volta: três chamadas na mesma resposta. Segunda: a fala. */
    private final ProvedorDeModelo modelo = new ProvedorDeModelo() {
        @Override public RespostaDoModelo responder(PedidoAoModelo pedido) {
            int volta = chamadasAoModelo.incrementAndGet();
            if (volta == 1) {
                return new RespostaDoModelo(List.of(
                        new Bloco.Chamada("t1", "holograma", JSON.createObjectNode().put("acao", "abrir")),
                        new Bloco.Chamada("t2", "holograma", JSON.createObjectNode().put("acao", "carregar")
                                .put("objeto", "motor V8")),
                        new Bloco.Chamada("t3", "holograma", JSON.createObjectNode().put("acao", "explodir")
                                .put("valor", "0.8"))),
                        RespostaDoModelo.MotivoDeParada.FERRAMENTA, null);
            }
            return new RespostaDoModelo(List.of(new Bloco.Texto("Construindo motor V8.")),
                    RespostaDoModelo.MotivoDeParada.FIM, null);
        }
    };

    private Assistente assistente() {
        GaleriaDeHologramas galeria = mock(GaleriaDeHologramas.class);
        when(galeria.buscar(any(), any())).thenReturn(Optional.empty());
        Ferramentas ferramentas = new Ferramentas(
                List.of(HologramaFerramentaDeTeste.criar(hud, JSON, estado, galeria)), JSON,
                evento -> publicados.add((TelaEvento) evento));
        JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null);
        return new Assistente(modelo, ferramentas,
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"), JSON, props),
                JSON, new RegistroConversas(evento -> { }), new AnexosService(), BuscaWebServidor.desligada(),
                new Aprovacoes(), HistoricoEmArquivo.desligado(), ClassificadorDeResposta.semModelo(), null,
                new EsquecimentosDeMemoria(), CompactadorDeHistorico.padrao(JSON), new ResultadosTardios(),
                null, null, new AtalhoDoHolograma(hud, estado));
    }

    private List<ComandoDoHolograma> comandos() {
        return publicados.stream().filter(e -> TelaEvento.HOLOGRAMA.equals(e.nome()))
                .map(e -> (ComandoDoHolograma) e.dados()).toList();
    }

    @Test
    @DisplayName("'abre o holograma e carrega o motor V8 explodido': três comandos na ordem, e o 0.8 fica")
    void compostoEmSequencia() throws Exception {
        RespostaConversa resposta = assistente().responder(7, "abre o holograma e carrega o motor V8 explodido",
                Canal.HUD, null);

        assertEquals(2, chamadasAoModelo.get(), "uma volta com as três chamadas, outra com a fala");
        List<ComandoDoHolograma> comandos = comandos();
        assertEquals(List.of("abrir", "carregar", "explodir"), comandos.stream().map(ComandoDoHolograma::acao).toList());
        assertEquals(0.8, comandos.get(2).dados().get("fator"),
                "o explodir não pode ser recusado por 'holograma fechado' — o abrir acabou de sair");
        assertEquals("Construindo motor V8.", resposta.texto());
    }

    @Test
    @DisplayName("com o modo aberto, 'mais' vai pelo caminho rápido: sem modelo, sem fala, comando marcado")
    void caminhoRapido() throws Exception {
        estado.guardar(new EstadoDoHolograma(true, "h1", "Motor V8", false,
                List.of(new EstadoDoHolograma.Peca("p1", "Pistão", "pistoes")), 0.5, "", List.of(), List.of(),
                "frente", Instant.now().toString()));

        RespostaConversa resposta = assistente().responder(7, "mais", Canal.HUD, null);

        assertEquals(0, chamadasAoModelo.get());
        assertTrue(resposta.silenciosa());
        assertEquals("", resposta.paraFala());
        ComandoDoHolograma comando = comandos().get(0);
        assertTrue(comando.rapido());
        assertEquals(0.75, comando.dados().get("fator"));
    }

    @Test
    @DisplayName("com o modo fechado, 'mais' é do modelo")
    void fechadoVaiAoModelo() throws Exception {
        assistente().responder(7, "mais", Canal.HUD, null);
        assertTrue(chamadasAoModelo.get() > 0);
    }
}
