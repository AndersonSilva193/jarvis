package dev.jarvis.ia.provedor;

import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.pc.OperacoesPcService;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A IA local inteira montada sem rede: fila E2 real, {@code PcService} real, medidor real numa
 * pasta temporária e um <b>agente dublado</b> numa thread, que busca o {@code inferir} na fila e
 * responde o que o teste roteirizou.
 *
 * <p>O agente dublado copia a forma do agente real ({@code InferenciaLocal.resumir} no
 * {@code jarvis-agente-pc}): {@code message} com {@code role}, {@code content} e
 * {@code tool_calls[].function.{name,arguments}}, mais {@code modelo}, {@code done_reason},
 * {@code prompt_eval_count}, {@code eval_count} e {@code total_duration}; a falha volta em
 * {@code falhou} com {@code dados.codigoErro}. Agente de mentira que responde na forma que o código
 * espera só prova que o código concorda com o próprio engano.
 */
public final class BancadaLocal implements AutoCloseable {

    public static final JsonMapper JSON = JsonMapper.builder().build();
    /** O que o agente manda quando a IA está pronta: modelo na memória, GPU livre. */
    public static final String PRONTA =
            "ativo=1;ollama=1;modelo=qwen3.5:9b;carregado=1;gpu=1;vramLivreMb=3000;vramTotalMb=12288;gpuUso=5;ocupada=0;inferindo=0";

    public final OperacoesPcService e2;
    public final PcService pc;
    public final JarvisProperties props;
    public final MedidorDeCusto medidor;
    public final DisponibilidadeLocal disponibilidade;
    public final ProvedorLocal local;
    /** O {@code dados} de cada {@code inferir} que o agente recebeu, na ordem. */
    public final List<JsonNode> recebidos = new CopyOnWriteArrayList<>();
    /** O id de cada {@code inferir} recebido, para conferir cancelamento. */
    public final List<String> ids = new CopyOnWriteArrayList<>();

    private final ConcurrentLinkedDeque<Function<String, ResultadoPc>> roteiro = new ConcurrentLinkedDeque<>();
    private final Thread agente;

    public BancadaLocal(Path pasta) {
        this(pasta, "");
    }

    /** @param apoio o {@code IA_LOCAL_APOIO} cru ({@code ""} acompanha a IA local, ligada aqui) */
    public BancadaLocal(Path pasta, String apoio) {
        props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("token", 0L), null, null, null, null, null, null,
                null, null,
                new JarvisProperties.Pc("token", pasta.resolve("operacoes.json").toString(),
                        1024 * 1024, 10, 1024 * 1024,
                        new JarvisProperties.Pc.IaLocal(true, apoio, 1, 6000)),
                null, null, null, null, null,
                new JarvisProperties.Custo(pasta.resolve("custo").toString(), "", "", "", ""));
        e2 = new OperacoesPcService(JSON, pasta.resolve("operacoes.json"), Clock.systemUTC());
        e2.catalogo(List.of()); // o agente deu sinal de vida
        pc = new PcService(e2);
        pc.proximoComando(Duration.ZERO, null, PRONTA);
        medidor = new MedidorDeCusto(props, mock(TelegramClient.class), JSON);
        disponibilidade = new DisponibilidadeLocal(pc, props);
        local = new ProvedorLocal(pc, medidor, props, JSON);
        agente = Thread.ofVirtual().start(this::atender);
    }

    /** O de apoio sobre esta bancada, com o Claude que o teste der. */
    public ProvedorDeApoio apoio(ProvedorDeModelo claude) {
        return new ProvedorDeApoio(claude, local, disponibilidade, medidor, props);
    }

    /** Um novo {@code X-Pc-Ia}, como se tivesse chegado numa consulta do agente. */
    public void cabecalho(String ia) {
        pc.proximoComando(Duration.ZERO, null, ia);
    }

    /** A próxima inferência responde só texto. */
    public BancadaLocal responde(String conteudo) {
        return responde(mensagem(conteudo));
    }

    /** A próxima inferência responde esta {@code message}. */
    public BancadaLocal responde(ObjectNode mensagem) {
        roteiro.add(id -> {
            ObjectNode dados = JSON.createObjectNode();
            dados.set("message", mensagem);
            dados.put("modelo", "qwen3.5:9b").put("done_reason", "stop")
                    .put("prompt_eval_count", 120).put("eval_count", 30).put("total_duration", 900_000_000L);
            return new ResultadoPc(id, true, "inferência concluída", null, null, false, dados);
        });
        return this;
    }

    /** A próxima inferência falha com este código, como o agente real. */
    public BancadaLocal falha(String codigo) {
        roteiro.add(id -> new ResultadoPc(id, false, "recusada", null, null, false,
                JSON.createObjectNode().put("codigoErro", codigo)));
        return this;
    }

    /** A próxima inferência é recebida e nunca respondida. */
    public BancadaLocal calado() {
        roteiro.add(id -> null);
        return this;
    }

    public static ObjectNode mensagem(String conteudo) {
        return JSON.createObjectNode().put("role", "assistant").put("content", conteudo);
    }

    /** Uma {@code message} com chamadas; {@code arguments} vai como o teste mandar, até em texto. */
    public static ObjectNode comChamadas(String conteudo, String nome, JsonNode argumentos) {
        ObjectNode mensagem = mensagem(conteudo);
        ObjectNode funcao = mensagem.putArray("tool_calls").addObject().put("type", "function")
                .putObject("function").put("name", nome);
        funcao.set("arguments", argumentos);
        return mensagem;
    }

    private void atender() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                var entrega = e2.proximo(Duration.ofMillis(100));
                if (entrega.isEmpty()) continue;
                var comando = entrega.get().comando();
                e2.recebido(comando.id());
                e2.executando(comando.id());
                recebidos.add(comando.dados());
                ids.add(comando.id());
                Function<String, ResultadoPc> passo = roteiro.poll();
                ResultadoPc resultado = passo == null ? null : passo.apply(comando.id());
                if (resultado != null) {
                    e2.concluir(comando.id(), resultado.ok() ? "concluído" : "falhou", resultado, null);
                }
            }
        } catch (InterruptedException fim) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws InterruptedException {
        agente.interrupt();
        agente.join(2000);
        medidor.fechar();
    }
}
