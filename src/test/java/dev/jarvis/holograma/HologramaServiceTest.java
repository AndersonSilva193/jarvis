package dev.jarvis.holograma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.provedor.Bloco;
import dev.jarvis.ia.provedor.Cancelamento;
import dev.jarvis.ia.provedor.DisponibilidadeLocal;
import dev.jarvis.ia.provedor.FalhaLocal;
import dev.jarvis.ia.provedor.PedidoAoModelo;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.ia.provedor.RespostaDoModelo;
import tools.jackson.databind.json.JsonMapper;

/** A geração com stream: peça na hora, limite, reserva, prazo e cliente que vai embora. */
class HologramaServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String META = "{\"t\":\"meta\",\"nome\":\"Cubo\",\"cat\":\"teste\",\"desc\":\"um cubo\"}\n";
    private static final String PECA = "{\"t\":\"p\",\"id\":\"cubo\",\"n\":\"Cubo\",\"g\":\"corpo\",\"f\":\"box\","
            + "\"p\":[0,0,0],\"r\":[0,0,0],\"s\":[1,1,1],\"c\":\"#38bdf8\",\"e\":[0,1,0]}\n";
    private static final String FIM = "{\"t\":\"fim\"}";

    private HologramaService servico;

    @AfterEach
    void fechar() {
        if (servico != null) servico.fechar();
    }

    @Test
    void aPecaSaiAntesDeOModeloTerminar() throws Exception {
        Emissor emissor = new Emissor();
        AtomicBoolean pecaNaTelaNoMeio = new AtomicBoolean();
        Falso anthropic = new Falso(List.of(META, PECA, FIM));
        anthropic.depoisDoDelta = i -> {
            if (i == 1) pecaNaTelaNoMeio.set(emissor.corpo().contains("event:peca"));
        };
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true);

        servico.gerar("cubo", DetalheHolograma.RAPIDO, emissor);
        emissor.esperar();

        assertTrue(pecaNaTelaNoMeio.get(), "a peça só saiu no fim");
        // O fim veio sem quebra de linha: é o resto do buffer, e não há aviso de fim faltando.
        assertEquals(List.of("meta", "peca", "fim"), emissor.eventos());
        assertTrue(emissor.corpo().contains("\"pecas\":1,\"avisos\":0"), emissor.corpo());
    }

    @Test
    void anthropicRecebeOModeloEOTetoDoNivelSemTemperatura() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of(META, FIM));
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true);

        servico.gerar("cubo", DetalheHolograma.DETALHADO, emissor);
        emissor.esperar();

        var opcoes = anthropic.ultimo.opcoes();
        assertEquals("modelo-holograma", opcoes.modelo());
        assertEquals(DetalheHolograma.DETALHADO.maxTokens(), opcoes.maxTokens());
        assertEquals(null, opcoes.temperatura());
        assertEquals("Gere: cubo", anthropic.ultimo.mensagens().get(0).texto());
        assertTrue(anthropic.ultimo.sistema().blocos().get(0).texto().contains("OBJETO: cubo"));
        assertTrue(anthropic.ultimo.sistema().blocos().get(0).texto().contains("NÍVEL DE DETALHE: detalhado"));
    }

    @Test
    void oLimiteDePecasCortaOModelo() throws Exception {
        Emissor emissor = new Emissor();
        String sessenta = "{\"t\":\"p\",\"id\":\"dente\",\"f\":\"box\",\"rep\":{\"n\":60,\"d\":[0.01,0,0]}}\n";
        Falso anthropic = new Falso(List.of(META, sessenta, sessenta, sessenta, sessenta, sessenta, sessenta));
        anthropic.esperarCorte = true;
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true);

        servico.gerar("engrenagem", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertTrue(anthropic.cortado, "o stream do modelo não foi fechado");
        assertTrue(emissor.corpo().contains("\"pecas\":250"), emissor.corpo());
        assertFalse(emissor.eventos().contains("erro"));
        assertEquals("fim", emissor.eventos().get(emissor.eventos().size() - 1));
    }

    @Test
    void localQueCaiAntesDaPrimeiraPecaPassaAoClaude() throws Exception {
        Emissor emissor = new Emissor();
        Falso local = new Falso(List.of(META));
        local.falha = new FalhaLocal("timeout", "sem escrever");
        Falso anthropic = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, local, "local", true);

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertEquals(1, local.chamadas);
        assertEquals(1, anthropic.chamadas);
        assertEquals(0.4, local.ultimo.opcoes().temperatura());
        assertTrue(emissor.eventos().contains("peca"));
        assertFalse(emissor.eventos().contains("erro"));
    }

    @Test
    void localQueCaiDepoisDaPrimeiraPecaNaoTemReserva() throws Exception {
        Emissor emissor = new Emissor();
        Falso local = new Falso(List.of(META, PECA));
        local.falha = new FalhaLocal("timeout", "sem escrever");
        Falso anthropic = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, local, "local", true);

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertEquals(0, anthropic.chamadas);
        assertEquals(List.of("meta", "peca", "erro", "fim"), emissor.eventos());
        assertTrue(emissor.corpo().contains(HologramaService.TEXTO_FALHA), emissor.corpo());
    }

    @Test
    void localIndisponivelVaiDiretoAoClaude() throws Exception {
        Emissor emissor = new Emissor();
        Falso local = new Falso(List.of(META, PECA, FIM));
        Falso anthropic = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, local, "local", false);

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertEquals(0, local.chamadas);
        assertEquals(1, anthropic.chamadas);
    }

    @Test
    void oPrazoFechaComErroEFimEMantemAsPecas() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of(META, PECA));
        anthropic.esperarCorte = true;
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true,
                Duration.ofMillis(200), Duration.ofSeconds(10));

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertTrue(anthropic.cortado);
        assertEquals(List.of("meta", "peca", "erro", "fim"), emissor.eventos());
        assertTrue(emissor.corpo().contains(HologramaService.TEXTO_TEMPO), emissor.corpo());
    }

    @Test
    void oClienteQueSaiCortaOModeloAntesDaPrimeiraPeca() throws Exception {
        Emissor emissor = new Emissor();
        emissor.foiEmbora = true;
        Falso anthropic = new Falso(List.of());
        anthropic.esperarCorte = true;
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true,
                Duration.ofSeconds(10), Duration.ofMillis(20));

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);

        assertTrue(anthropic.corte.await(5, TimeUnit.SECONDS), "o ping não percebeu o cliente fora");
    }

    @Test
    void umaGeracaoNovaSubstituiAAnterior() throws Exception {
        Emissor primeiro = new Emissor();
        Falso lento = new Falso(List.of(META));
        lento.esperarCorte = true;
        servico = servico(lento, new Falso(List.of()), "anthropic", true);

        servico.gerar("cubo", DetalheHolograma.NORMAL, primeiro);
        assertTrue(lento.comecou.await(5, TimeUnit.SECONDS));
        servico.gerar("esfera", DetalheHolograma.NORMAL, new Emissor());
        primeiro.esperar();

        assertTrue(primeiro.corpo().contains(HologramaService.TEXTO_SUBSTITUIDO), primeiro.corpo());
    }

    // ---- montagem ---------------------------------------------------------------------------

    private HologramaService servico(Falso anthropic, Falso local, String provedor, boolean localPode) {
        return servico(anthropic, local, provedor, localPode, Duration.ofSeconds(10), Duration.ofSeconds(10));
    }

    private HologramaService servico(Falso anthropic, Falso local, String provedor, boolean localPode,
            Duration prazo, Duration ping) {
        JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "modelo-a", 1024, 512), null, null, null,
                null, null, null, null,
                new JarvisProperties.Pc("t", "/tmp/op.json", 1, 1, 1,
                        new JarvisProperties.Pc.IaLocal(true, "", 60, 6000)),
                null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Holograma(provedor, "modelo-holograma", 0.4));
        return new HologramaService(anthropic, local,
                () -> new DisponibilidadeLocal.Situacao(localPode, localPode ? "" : "pc_offline"),
                props, new PromptDeHolograma(), new HologramaLineParser(JSON), prazo, ping);
    }

    /** Um provedor que transmite os pedaços dados, e reage ao corte como um stream fechado. */
    private static final class Falso implements ProvedorDeModelo {
        private final List<String> deltas;
        IOException falha;
        boolean esperarCorte;
        Consumer<Integer> depoisDoDelta = i -> { };
        volatile boolean cortado;
        volatile int chamadas;
        volatile PedidoAoModelo ultimo;
        final CountDownLatch corte = new CountDownLatch(1);
        final CountDownLatch comecou = new CountDownLatch(1);

        Falso(List<String> deltas) {
            this.deltas = deltas;
        }

        @Override
        public RespostaDoModelo responder(PedidoAoModelo pedido) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RespostaDoModelo transmitir(PedidoAoModelo pedido, Consumer<String> aoTexto,
                Cancelamento cancelamento) throws IOException, InterruptedException {
            chamadas++;
            ultimo = pedido;
            cancelamento.aoCancelar(() -> {
                cortado = true;
                corte.countDown();
            });
            for (int i = 0; i < deltas.size(); i++) {
                if (cortado) throw new IOException("stream fechado");
                aoTexto.accept(deltas.get(i));
                depoisDoDelta.accept(i);
            }
            comecou.countDown();
            if (esperarCorte) {
                if (!corte.await(5, TimeUnit.SECONDS)) throw new IOException("ninguém cortou");
                throw new IOException("stream fechado");
            }
            if (falha != null) throw falha;
            return new RespostaDoModelo(List.of(new Bloco.Texto(String.join("", deltas))),
                    RespostaDoModelo.MotivoDeParada.FIM, null);
        }
    }

    /** Um SseEmitter que guarda o que foi mandado, e que pode fingir que o cliente saiu. */
    private static final class Emissor extends SseEmitter {
        private final List<String> eventos = Collections.synchronizedList(new ArrayList<>());
        private final StringBuilder corpo = new StringBuilder();
        private final CountDownLatch completo = new CountDownLatch(1);
        volatile boolean foiEmbora;

        @Override
        public void send(SseEventBuilder evento) throws IOException {
            if (foiEmbora) throw new IOException("Broken pipe");
            StringBuilder texto = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType parte : evento.build()) {
                texto.append(parte.getData() instanceof String s ? s : JSON.writeValueAsString(parte.getData()));
            }
            String bruto = texto.toString();
            synchronized (corpo) {
                corpo.append(bruto);
            }
            if (bruto.startsWith("event:")) {
                eventos.add(bruto.substring(6, bruto.indexOf('\n')));
            }
        }

        @Override
        public void complete() {
            completo.countDown();
        }

        void esperar() throws InterruptedException {
            assertTrue(completo.await(5, TimeUnit.SECONDS), "a geração não terminou: " + corpo());
        }

        List<String> eventos() {
            return List.copyOf(eventos);
        }

        String corpo() {
            synchronized (corpo) {
                return corpo.toString();
            }
        }
    }
}
