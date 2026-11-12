package dev.jarvis.escritorio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.escritorio.EscritaNoPc.Estado;
import dev.jarvis.escritorio.EscritaNoPc.Resposta;
import dev.jarvis.pc.PcVoltou;
import dev.jarvis.telegram.TelegramClient;
import dev.jarvis.util.ArquivoAtomico;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O que precisa ir para as planilhas do PC e ainda não foi: PC desligado, arquivo aberto no Excel,
 * agente antigo ou resposta que não chegou.
 *
 * <p>Um esvaziador só, numa thread própria, manda os pedidos ao PC em ordem de chegada. Quem pede
 * põe o pedido na fila e espera a resposta do <b>próprio</b> pedido, sem segurar trava nenhuma
 * durante a ida ao PC. A ordem vale por arquivo: a agenda aberta no Excel não segura o livro.
 * PC desligado e agente antigo seguram todos, porque valem para todos.
 *
 * <p>Sai da fila: o que gravou; {@link Estado#ID_INEXISTENTE} (não adianta repetir; num
 * {@code remover_linha} já tentado é o sinal de que a tentativa anterior removeu sem a resposta
 * chegar); e {@link Estado#RECUSADO}. Quando ninguém está esperando a resposta, o dono é avisado.
 *
 * <p>A fila mora em {@code DIARIO_PASTA/pendentes-pc.json} e sobrevive ao restart. Esvazia quando
 * o PC volta (depois de um instante, para a VPS registrar o que o agente anunciou) e a cada cinco
 * minutos.
 */
@Component
public class FilaDoPc {

    private static final Logger log = LoggerFactory.getLogger(FilaDoPc.class);
    static final String ARQUIVO = "pendentes-pc.json";
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    /** Quanto quem pede espera a resposta do próprio pedido; depois disso ele segue na fila. */
    static final Duration ESPERA_DE_QUEM_PEDE = Duration.ofSeconds(40);
    static final Duration ATRASO_DA_VOLTA = Duration.ofSeconds(3);

    private final Function<PedidoDeEscrita, Resposta> envio;
    private final Path arquivo;
    private final JsonMapper json;
    private final Consumer<String> aviso;
    private final Executor executor;
    private final Duration atrasoDaVolta;

    /** Guarda a fila e os sinais abaixo; nunca é segurada durante a ida ao PC. */
    private final Object trava = new Object();
    private final List<PedidoDeEscrita> fila = new ArrayList<>();
    /** Quem está esperando a resposta de um pedido, pelo id do pedido. */
    private final Map<String, CompletableFuture<Resposta>> esperando = new HashMap<>();
    private boolean esvaziando;
    private boolean deNovo;
    /** Já avisou que o agente é antigo; volta a avisar depois de um pedido gravado. */
    private boolean avisouAgenteAntigo;
    /** Já avisou que a fila não grava no disco; volta a avisar depois de uma gravação boa. */
    private boolean avisouDisco;

    @Autowired
    public FilaDoPc(EscritaNoPc escrita, JarvisProperties props, JsonMapper json, TelegramClient telegram) {
        this(escrita::enviar, props.pastaDoDiario().resolve(ARQUIVO), json,
                texto -> {
                    if (props.telegram() != null) telegram.enviarMensagem(props.telegram().usuarioPermitido(), texto);
                },
                tarefa -> Thread.ofVirtual().name("fila-do-pc").start(tarefa), ATRASO_DA_VOLTA);
    }

    /** Para os testes e para quem monta à mão: o envio ao PC e o executor vêm de fora. */
    public FilaDoPc(Function<PedidoDeEscrita, Resposta> envio, Path arquivo, JsonMapper json, Consumer<String> aviso,
            Executor executor) {
        this(envio, arquivo, json, aviso, executor, Duration.ZERO);
    }

    public FilaDoPc(Function<PedidoDeEscrita, Resposta> envio, Path arquivo, JsonMapper json, Consumer<String> aviso,
            Executor executor, Duration atrasoDaVolta) {
        this.envio = envio;
        this.arquivo = arquivo;
        this.json = json;
        this.aviso = aviso;
        this.executor = executor;
        this.atrasoDaVolta = atrasoDaVolta;
        carregar();
    }

    /**
     * Põe na fila e espera a resposta deste pedido (até {@link #ESPERA_DE_QUEM_PEDE}). Fora de
     * {@link Estado#GRAVADO}, {@link Estado#ID_INEXISTENTE} e {@link Estado#RECUSADO}, o pedido
     * continua na fila e a resposta é a que o segurou.
     */
    public Resposta gravarOuEnfileirar(PedidoDeEscrita pedido) {
        CompletableFuture<Resposta> resposta = new CompletableFuture<>();
        synchronized (trava) {
            fila.add(pedido);
            esperando.put(pedido.id(), resposta);
            salvar();
        }
        pedirEsvaziamento();
        try {
            return resposta.get(ESPERA_DE_QUEM_PEDE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            synchronized (trava) {
                esperando.remove(pedido.id());
            }
            return new Resposta(Estado.FALHOU, "o PC ainda não confirmou");
        } catch (ExecutionException e) {
            return new Resposta(Estado.FALHOU, "a fila do PC falhou");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resposta(Estado.FALHOU, "interrompido");
        }
    }

    /** Só guarda e pede o esvaziamento, sem esperar. Para quem não tem a quem responder na hora. */
    public void enfileirar(PedidoDeEscrita pedido) {
        synchronized (trava) {
            fila.add(pedido);
            salvar();
        }
        pedirEsvaziamento();
    }

    public int pendentes() {
        synchronized (trava) {
            return fila.size();
        }
    }

    /**
     * O evento sai de dentro da consulta do agente: esperar aqui por um resultado que só chega
     * quando essa consulta termina travaria os dois. E espera um instante, para o resto da
     * volta do agente (o E2 em contato) já ter acontecido.
     */
    @EventListener
    public void pcVoltou(PcVoltou evento) {
        executor.execute(() -> {
            try {
                if (!atrasoDaVolta.isZero()) Thread.sleep(atrasoDaVolta.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            pedirEsvaziamento();
        });
    }

    /** Só dispara o esvaziador: o agendador do Spring não pode ficar esperando o PC. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void esvaziar() {
        pedirEsvaziamento();
    }

    private void pedirEsvaziamento() {
        synchronized (trava) {
            deNovo = true;
            if (esvaziando) return;
            esvaziando = true;
        }
        try {
            executor.execute(this::esvaziador);
        } catch (RuntimeException e) {
            synchronized (trava) {
                esvaziando = false;
            }
            log.error("Não consegui disparar o esvaziador da fila do PC", e);
        }
    }

    private void esvaziador() {
        while (true) {
            synchronized (trava) {
                if (!deNovo) {
                    esvaziando = false;
                    return;
                }
                deNovo = false;
            }
            try {
                umaPassada();
            } catch (RuntimeException e) {
                log.error("Falha ao esvaziar a fila do PC", e);
            }
        }
    }

    /** Manda cada pedido em ordem, pulando o arquivo que já travou nesta passada. */
    private void umaPassada() {
        List<PedidoDeEscrita> instantaneo;
        synchronized (trava) {
            instantaneo = new ArrayList<>(fila);
        }
        Resposta bloqueioGeral = null;
        Map<String, Resposta> bloqueioPorArquivo = new HashMap<>();
        for (PedidoDeEscrita pedido : instantaneo) {
            Resposta bloqueio = bloqueioGeral != null ? bloqueioGeral : bloqueioPorArquivo.get(pedido.arquivo());
            if (bloqueio != null) {
                synchronized (trava) {
                    responder(pedido, bloqueio);
                }
                continue;
            }
            Resposta resposta = envio.apply(pedido);
            synchronized (trava) {
                switch (resposta.estado()) {
                    case GRAVADO -> {
                        tirar(pedido);
                        avisouAgenteAntigo = false;
                        responder(pedido, resposta);
                    }
                    case ID_INEXISTENTE -> {
                        tirar(pedido);
                        boolean jaTentado = "remover_linha".equals(pedido.acao()) && pedido.tentativas() > 0;
                        if (!responder(pedido, resposta) && !jaTentado)
                            aviso.accept("Não achei a linha que era para " + ("remover_linha".equals(pedido.acao())
                                    ? "remover" : "alterar") + " em " + pedido.arquivo()
                                    + " no PC; o pedido foi descartado. Confira o arquivo.");
                    }
                    case RECUSADO -> {
                        tirar(pedido);
                        if (!responder(pedido, resposta))
                            aviso.accept("O PC recusou gravar em " + pedido.arquivo() + " (" + resposta.mensagem()
                                    + "); o pedido foi descartado.");
                    }
                    case PC_OFFLINE, AGENTE_ANTIGO -> {
                        maisUmaTentativa(pedido);
                        responder(pedido, resposta);
                        bloqueioGeral = resposta;
                        if (resposta.estado() == Estado.AGENTE_ANTIGO) avisarAgenteAntigo();
                    }
                    default -> {
                        maisUmaTentativa(pedido);
                        responder(pedido, resposta);
                        bloqueioPorArquivo.put(pedido.arquivo(), resposta);
                    }
                }
                salvar();
            }
        }
    }

    /** Entrega a resposta a quem espera este pedido. Falso quando ninguém espera. */
    private boolean responder(PedidoDeEscrita pedido, Resposta resposta) {
        CompletableFuture<Resposta> quem = esperando.remove(pedido.id());
        return quem != null && quem.complete(resposta);
    }

    private void tirar(PedidoDeEscrita pedido) {
        fila.removeIf(p -> p.id().equals(pedido.id()));
    }

    private void maisUmaTentativa(PedidoDeEscrita pedido) {
        fila.replaceAll(p -> p.id().equals(pedido.id()) ? p.maisUmaTentativa() : p);
    }

    private void avisarAgenteAntigo() {
        if (avisouAgenteAntigo) return;
        avisouAgenteAntigo = true;
        int quantos = fila.size();
        aviso.accept("Senhor, atualize o agente do PC: ele ainda não sabe gravar na agenda e no livro "
                + "financeiro. " + quantos + (quantos == 1 ? " pedido esperando." : " pedidos esperando."));
    }

    // ---------------------------------------------------------------- disco

    private void carregar() {
        if (!Files.exists(arquivo) || Files.isDirectory(arquivo)) return;
        try {
            JsonNode raiz = json.readTree(ArquivoAtomico.ler(arquivo, MAX_BYTES));
            for (JsonNode item : raiz) {
                if (!item.path("dados").isObject()) continue;
                fila.add(new PedidoDeEscrita(item.path("id").asString(), item.path("acao").asString(),
                        item.path("arquivo").asString(), (ObjectNode) item.path("dados"),
                        item.path("criadoEm").asString(), item.path("tentativas").asInt()));
            }
            if (!fila.isEmpty()) log.info("{} escrita(s) esperando o PC", fila.size());
        } catch (IOException | RuntimeException e) {
            fila.clear();
            // Guardado ao lado, e não sobrescrito na próxima gravação: é a prova do que se perdeu.
            Path aoLado = arquivo.resolveSibling(arquivo.getFileName() + ".corrompido-" + System.currentTimeMillis());
            try {
                Files.move(arquivo, aoLado, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException movendo) {
                log.error("Nem consegui guardar {} ao lado", arquivo, movendo);
            }
            log.error("Não consegui ler {}; guardei em {} e a fila do PC começa vazia", arquivo, aoLado, e);
        }
    }

    /**
     * Falha de disco não perde o pedido agora (ele segue em memória), mas perderia num restart:
     * o dono é avisado uma vez, porque é sinal de pasta sem permissão na VPS.
     */
    private void salvar() {
        ArrayNode lista = json.createArrayNode();
        for (PedidoDeEscrita pedido : fila) {
            lista.addObject().put("id", pedido.id()).put("acao", pedido.acao()).put("arquivo", pedido.arquivo())
                    .put("criadoEm", pedido.criadoEm()).put("tentativas", pedido.tentativas())
                    .set("dados", pedido.dados());
        }
        try {
            ArquivoAtomico.gravar(arquivo, json.writeValueAsString(lista));
            avisouDisco = false;
        } catch (IOException | RuntimeException e) {
            log.error("Não consegui gravar {}; {} pedido(s) só em memória", arquivo, fila.size(), e);
            if (!avisouDisco) {
                avisouDisco = true;
                aviso.accept("Senhor, não consegui gravar a fila do PC no disco da VPS (" + arquivo
                        + "). Os pedidos estão só na memória e se perdem se eu reiniciar: confira as "
                        + "permissões das pastas da VPS.");
            }
        }
    }
}
