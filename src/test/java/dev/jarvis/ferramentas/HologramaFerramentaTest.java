package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.holograma.ComandoDoHolograma;
import dev.jarvis.holograma.EstadoDoHolograma;
import dev.jarvis.holograma.EstadoDoHologramaService;
import dev.jarvis.holograma.FalasDoHolograma;
import dev.jarvis.holograma.GaleriaDeHologramas;
import dev.jarvis.holograma.SaidaHolograma;
import dev.jarvis.proativo.FalasService;
import dev.jarvis.tela.PresencaDaHud;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A ferramenta holograma: sem HUD, carregar com e sem galeria, "mais" partindo do estado e o empate. */
class HologramaFerramentaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final OuvinteProgresso DA_HUD = new OuvinteProgresso() {
        @Override public String canal() { return "hud"; }
    };

    private final EstadoDoHologramaService estado = new EstadoDoHologramaService();
    private final GaleriaDeHologramas galeria = mock(GaleriaDeHologramas.class);
    private final List<String> avisos = new ArrayList<>();
    private final List<String> geradosSemTela = new ArrayList<>();
    /** Sorteio fixo no zero: a primeira variação de cada frase. */
    private final FalasDoHolograma falas = new FalasDoHolograma(new RandomGenerator() {
        @Override public long nextLong() { return 0; }
        @Override public int nextInt(int limite) { return 0; }
    });

    private HologramaFerramenta ferramenta(PresencaDaHud hud) {
        return new HologramaFerramenta(hud, JSON, estado, galeria, falas, (objeto, detalhe, aoFim) -> {
            geradosSemTela.add(objeto + "|" + detalhe.chave());
            aoFim.accept(new SaidaHolograma.Fim(42, 0).naGaleria(SaidaHolograma.ORIGEM_MODELO, false));
            return "h00000000";
        }, avisos::add, new FalasService());
    }

    private HologramaFerramenta comHud() {
        return ferramenta(() -> 1);
    }

    private static ObjectNode pedido(String acao, String... pares) {
        ObjectNode entrada = JSON.createObjectNode().put("acao", acao);
        for (int i = 0; i < pares.length; i += 2) entrada.put(pares[i], pares[i + 1]);
        return entrada;
    }

    private void motorNaTela(double explosao) {
        estado.guardar(new EstadoDoHolograma(true, "h1", "Motor V8", false,
                List.of(new EstadoDoHolograma.Peca("p1", "Pistão esquerdo", "pistoes"),
                        new EstadoDoHolograma.Peca("p2", "Pistão direito", "pistoes"),
                        new EstadoDoHolograma.Peca("pino", "Pino do pistão", "pinos_do_pistao"),
                        new EstadoDoHolograma.Peca("pb", "Pino da biela", "bielas")),
                explosao, "p1", List.of(), List.of(), "frente", Instant.now().toString()));
    }

    @Test
    @DisplayName("sem HUD: cena recusa, carregar oferece a galeria e gerar_na_galeria gera e avisa")
    void semHud() throws Exception {
        HologramaFerramenta semTela = ferramenta(PresencaDaHud.semTela());
        ResultadoFerramenta cena = semTela.executarDetalhado(pedido("explodir"), DA_HUD);
        assertEquals(HologramaFerramenta.SEM_HUD, cena.texto());
        assertTrue(cena.holograma().isEmpty());

        ResultadoFerramenta carregar = semTela.executarDetalhado(pedido("carregar", "objeto", "motor V8"), DA_HUD);
        assertTrue(carregar.texto().contains("gerar_na_galeria"), carregar.texto());

        ResultadoFerramenta gerar = semTela.executarDetalhado(pedido("gerar_na_galeria", "objeto", "motor V8"), DA_HUD);
        assertTrue(gerar.texto().contains("galeria"), gerar.texto());
        assertEquals(List.of("motor V8|normal"), geradosSemTela);
        assertEquals(List.of("motor V8 está na galeria do holograma: 42 peças."), avisos);
    }

    @Test
    @DisplayName("carregar sem nada na galeria gera, e conta como aberto antes de a HUD reportar")
    void carregar() throws Exception {
        ResultadoFerramenta gerando = comHud().executarDetalhado(pedido("carregar", "objeto", "motor V8"), DA_HUD);
        ComandoDoHolograma comando = gerando.holograma().get(0);
        assertEquals("carregar", comando.acao());
        assertEquals("motor V8", comando.dados().get("objeto"));
        assertEquals("Pronto: {n} peças.", comando.dados().get("falaPronto"));
        assertTrue(gerando.texto().contains("Construindo motor V8."), gerando.texto());
        assertTrue(estado.abertoAgora(), "carregar conta como aberto antes de a HUD reportar");
    }

    private static GaleriaDeHologramas.Item item(String id, String nome) {
        return new GaleriaDeHologramas.Item(id, nome, 86, "", "exemplo", "normal", "", true, false);
    }

    @Test
    @DisplayName("'abre o átomo de carbono da galeria' abre o exemplo pelo id, sem gerar; novo=true gera")
    void carregarDaGaleria() throws Exception {
        when(galeria.listar()).thenReturn(List.of(item("exemplo_atomo", "Átomo de carbono"), item("exemplo_dna", "DNA")));
        ResultadoFerramenta aberto = comHud().executarDetalhado(
                pedido("carregar", "objeto", "átomo de carbono da galeria", "detalhe", "detalhado"), DA_HUD);
        ComandoDoHolograma comando = aberto.holograma().get(0);
        assertEquals("abrir_galeria", comando.acao());
        assertEquals("exemplo_atomo", comando.dados().get("id"));
        assertTrue(aberto.texto().contains("Átomo de carbono na tela."), aberto.texto());

        ObjectNode novo = pedido("carregar", "objeto", "átomo de carbono").put("novo", true);
        assertEquals("carregar", comHud().executarDetalhado(novo, DA_HUD).holograma().get(0).acao());
    }

    @Test
    @DisplayName("sem HUD, o que já está na galeria não se oferece gerar; dois candidatos viram pergunta")
    void galeriaSemHudEVarios() throws Exception {
        when(galeria.listar()).thenReturn(List.of(item("exemplo_motor_v8", "Motor V8"), item("h00000002", "Motor elétrico")));
        HologramaFerramenta semTela = ferramenta(PresencaDaHud.semTela());
        String texto = semTela.executarDetalhado(pedido("carregar", "objeto", "motor V8"), DA_HUD).texto();
        assertTrue(texto.contains("já está na galeria") && !texto.contains("gerar_na_galeria"), texto);

        ResultadoFerramenta varios = comHud().executarDetalhado(pedido("carregar", "objeto", "motor"), DA_HUD);
        assertTrue(varios.holograma().isEmpty());
        assertTrue(varios.texto().contains("Motor V8") && varios.texto().contains("Motor elétrico"), varios.texto());
        assertTrue(geradosSemTela.isEmpty());
    }

    @Test
    @DisplayName("'mais' e 'menos' partem do estado e somam o que o servidor acabou de mandar")
    void maisMenos() throws Exception {
        motorNaTela(0.5);
        HologramaFerramenta holograma = comHud();
        assertEquals(0.75, holograma.executarDetalhado(pedido("explodir", "valor", "mais"), DA_HUD)
                .holograma().get(0).dados().get("fator"));
        assertEquals(1.0, holograma.executarDetalhado(pedido("explodir", "valor", "mais"), DA_HUD)
                .holograma().get(0).dados().get("fator"), "o segundo 'mais' parte do primeiro, e prende em 1");
        assertEquals(0.75, holograma.executarDetalhado(pedido("explodir", "valor", "menos"), DA_HUD)
                .holograma().get(0).dados().get("fator"));
        assertEquals(0.8, holograma.executarDetalhado(pedido("explodir", "valor", "80%"), DA_HUD)
                .holograma().get(0).dados().get("fator"));
        assertEquals(0.0, holograma.executarDetalhado(pedido("montar"), DA_HUD)
                .holograma().get(0).dados().get("fator"));
    }

    @Test
    @DisplayName("alvo resolvido vira ids; empate volta ao modelo sem comando")
    void alvo() throws Exception {
        motorNaTela(0);
        ResultadoFerramenta destacar = comHud().executarDetalhado(pedido("destacar", "alvo", "os pistões"), DA_HUD);
        assertEquals(List.of("pistoes"), destacar.holograma().get(0).dados().get("grupos"));

        ResultadoFerramenta empate = comHud().executarDetalhado(pedido("isolar", "alvo", "pino"), DA_HUD);
        assertTrue(empate.holograma().isEmpty());
        assertTrue(empate.texto().contains("pinos do pistao") && empate.texto().contains("Pino da biela"),
                empate.texto());
    }

    @Test
    @DisplayName("explicar sem alvo fala da selecionada e não mexe na tela")
    void explicar() throws Exception {
        motorNaTela(0);
        ResultadoFerramenta explicar = comHud().executarDetalhado(pedido("explicar"), DA_HUD);
        assertTrue(explicar.holograma().isEmpty());
        assertTrue(explicar.texto().contains("Pistão esquerdo") && explicar.texto().contains("Motor V8"),
                explicar.texto());
    }

    @Test
    @DisplayName("cena com o modo fechado pede um objeto antes; o caminho rápido marca o comando")
    void fechadoERapido() throws Exception {
        assertEquals(HologramaFerramenta.FECHADO, comHud().executarDetalhado(pedido("girar"), DA_HUD).texto());
        motorNaTela(0);
        ObjectNode rapido = pedido("vista", "valor", "cima").put("rapido", true);
        ComandoDoHolograma comando = comHud().executarDetalhado(rapido, DA_HUD).holograma().get(0);
        assertTrue(comando.rapido());
        assertEquals("cima", comando.dados().get("vista"));
    }
}
