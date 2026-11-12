package dev.jarvis.escritorio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.escritorio.EscritaNoPc.Estado;
import dev.jarvis.escritorio.EscritaNoPc.Resposta;
import tools.jackson.databind.json.JsonMapper;

class FilaDoPcTest {

    @TempDir Path pasta;

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> enviados = new ArrayList<>();
    private final List<String> avisos = new ArrayList<>();
    /** O que o "PC" responde, na ordem; vazio repete o último. */
    private final Deque<Estado> respostas = new ArrayDeque<>();
    private Estado ultima = Estado.GRAVADO;

    /** Resposta fixa por arquivo, quando houver; senão a sequência de {@link #respostas}. */
    private final java.util.Map<String, Estado> porArquivo = new java.util.HashMap<>();

    private final Function<PedidoDeEscrita, Resposta> pc = pedido -> {
        enviados.add(pedido.id());
        Estado fixo = porArquivo.get(pedido.arquivo());
        if (fixo != null) return new Resposta(fixo, fixo.name());
        if (!respostas.isEmpty()) ultima = respostas.poll();
        return new Resposta(ultima, ultima.name());
    };

    private PedidoDeEscrita doLivro(String id) {
        return new PedidoDeEscrita(id, "acrescentar_linhas", "financas.csv", json.createObjectNode().put("id", id),
                "2026-11-12T10:00:00Z");
    }

    @Test
    void recusaNaHoraVoltaParaQuemPediuSemFilaNemAviso() {
        FilaDoPc fila = fila();
        respostas.add(Estado.RECUSADO);
        assertEquals(Estado.RECUSADO, fila.gravarOuEnfileirar(pedido("a")).estado());
        assertEquals(0, fila.pendentes());
        assertTrue(avisos.isEmpty());
    }

    @Test
    void recusaDepoisSaiDaFilaEAvisaODono() {
        FilaDoPc fila = fila();
        respostas.add(Estado.PC_OFFLINE);
        fila.gravarOuEnfileirar(pedido("a"));
        respostas.add(Estado.RECUSADO);
        respostas.add(Estado.GRAVADO);
        fila.gravarOuEnfileirar(pedido("b"));
        assertEquals(0, fila.pendentes(), "a recusa não trava o que vem atrás");
        assertEquals(1, avisos.size());
        assertTrue(avisos.get(0).contains("agenda.csv"), avisos.get(0));
    }

    @Test
    void arquivoTravadoNaoSeguraOOutroArquivo() {
        FilaDoPc fila = fila();
        porArquivo.put("agenda.csv", Estado.ARQUIVO_TRAVADO);
        fila.gravarOuEnfileirar(pedido("a"));
        porArquivo.put("financas.csv", Estado.GRAVADO);
        assertEquals(Estado.GRAVADO, fila.gravarOuEnfileirar(doLivro("l")).estado());
        assertEquals(1, fila.pendentes(), "só a agenda espera");
    }

    @Test
    void idInexistenteDeQuemEstaEsperandoNaoVaiProTelegram() {
        FilaDoPc fila = fila();
        respostas.add(Estado.PC_OFFLINE);
        fila.gravarOuEnfileirar(pedido("a"));
        respostas.add(Estado.GRAVADO);
        respostas.add(Estado.ID_INEXISTENTE);
        assertEquals(Estado.ID_INEXISTENTE, fila.gravarOuEnfileirar(pedido("x", "alterar_linha")).estado());
        assertTrue(avisos.isEmpty(), "quem pediu já recebe a resposta: " + avisos);
    }

    @Test
    void esvaziarSoDisparaOEsvaziadorSemEsperarOPc() {
        respostas.add(Estado.PC_OFFLINE);
        fila().gravarOuEnfileirar(pedido("a"));
        List<Runnable> agendadas = new ArrayList<>();
        FilaDoPc fila = new FilaDoPc(pc, pasta.resolve("pendentes-pc.json"), json, avisos::add, agendadas::add);
        enviados.clear();
        fila.esvaziar();
        assertTrue(enviados.isEmpty(), "o agendador do Spring não espera o PC");
        assertEquals(1, agendadas.size());
    }

    @Test
    void pcVoltouEsperaOsCabecalhosAntesDeEsvaziar() {
        respostas.add(Estado.PC_OFFLINE);
        fila().gravarOuEnfileirar(pedido("a"));
        FilaDoPc fila = new FilaDoPc(pc, pasta.resolve("pendentes-pc.json"), json, avisos::add, Runnable::run,
                java.time.Duration.ofMillis(150));
        respostas.add(Estado.GRAVADO);
        long antes = System.nanoTime();
        fila.pcVoltou(null);
        assertTrue(System.nanoTime() - antes >= 150_000_000L, "esvaziou antes de a VPS ler os cabeçalhos do agente");
        assertEquals(0, fila.pendentes());
    }

    @Test
    void falhaAoGravarNoDiscoAvisaUmaVez() throws Exception {
        java.nio.file.Files.createDirectories(pasta.resolve("eh-pasta"));
        FilaDoPc fila = new FilaDoPc(pc, pasta.resolve("eh-pasta"), json, avisos::add, Runnable::run);
        respostas.add(Estado.PC_OFFLINE);
        fila.gravarOuEnfileirar(pedido("a"));
        fila.gravarOuEnfileirar(pedido("b"));
        assertEquals(1, avisos.size(), avisos.toString());
        assertTrue(avisos.get(0).contains("disco"), avisos.get(0));
    }

    @Test
    void arquivoCorrompidoVaiParaOLadoEmVezDeSerSobrescrito() throws Exception {
        java.nio.file.Files.writeString(pasta.resolve("pendentes-pc.json"), "{lixo");
        FilaDoPc fila = fila();
        assertEquals(0, fila.pendentes());
        try (var arquivos = java.nio.file.Files.list(pasta)) {
            assertTrue(arquivos.anyMatch(p -> p.getFileName().toString().startsWith("pendentes-pc.json.corrompido")));
        }
    }

    private FilaDoPc fila() {
        return new FilaDoPc(pc, pasta.resolve("pendentes-pc.json"), json, avisos::add, Runnable::run);
    }

    private PedidoDeEscrita pedido(String id) {
        return pedido(id, "acrescentar_linhas");
    }

    private PedidoDeEscrita pedido(String id, String acao) {
        return new PedidoDeEscrita(id, acao, "agenda.csv", json.createObjectNode().put("id", id),
                "2026-11-12T10:00:00Z");
    }

    @Test
    void gravaDiretoQuandoFilaVaziaEPcResponde() {
        FilaDoPc fila = fila();
        assertEquals(Estado.GRAVADO, fila.gravarOuEnfileirar(pedido("a")).estado());
        assertEquals(0, fila.pendentes());
    }

    @Test
    void travadoOfflineOuFalhaVaoParaAFila() {
        FilaDoPc fila = fila();
        respostas.add(Estado.ARQUIVO_TRAVADO);
        assertEquals(Estado.ARQUIVO_TRAVADO, fila.gravarOuEnfileirar(pedido("a")).estado());
        respostas.add(Estado.PC_OFFLINE);
        assertEquals(Estado.PC_OFFLINE, fila.gravarOuEnfileirar(pedido("b")).estado());
        respostas.add(Estado.FALHOU);
        assertEquals(Estado.FALHOU, fila.gravarOuEnfileirar(pedido("c")).estado());
        assertEquals(3, fila.pendentes());
    }

    @Test
    void novoPedidoComFilaCheiaEntraNoFimEPreservaOrdem() {
        FilaDoPc fila = fila();
        respostas.add(Estado.PC_OFFLINE);
        fila.gravarOuEnfileirar(pedido("velho"));
        enviados.clear();
        respostas.add(Estado.GRAVADO);
        assertEquals(Estado.GRAVADO, fila.gravarOuEnfileirar(pedido("novo")).estado());
        assertEquals(List.of("velho", "novo"), enviados);
        assertEquals(0, fila.pendentes());
    }

    @Test
    void novoPedidoAtrasDeUmTravadoRecebeOEstadoQueOSegura() {
        FilaDoPc fila = fila();
        respostas.add(Estado.ARQUIVO_TRAVADO);
        fila.gravarOuEnfileirar(pedido("velho"));
        enviados.clear();
        assertEquals(Estado.ARQUIVO_TRAVADO, fila.gravarOuEnfileirar(pedido("novo")).estado());
        assertEquals(List.of("velho"), enviados, "o novo não passa na frente");
        assertEquals(2, fila.pendentes());
    }

    @Test
    void filaSobreviveAoReinicio() {
        respostas.add(Estado.PC_OFFLINE);
        fila().gravarOuEnfileirar(pedido("a"));
        FilaDoPc depois = fila();
        assertEquals(1, depois.pendentes());
        enviados.clear();
        respostas.add(Estado.GRAVADO);
        depois.pcVoltou(null);
        assertEquals(List.of("a"), enviados);
        assertEquals(0, depois.pendentes());
    }

    @Test
    void reenvioDoMesmoIdNaoDuplica() {
        FilaDoPc fila = fila();
        respostas.add(Estado.FALHOU);
        fila.gravarOuEnfileirar(pedido("a"));
        respostas.add(Estado.GRAVADO);
        fila.esvaziar();
        fila.esvaziar();
        assertEquals(List.of("a", "a"), enviados, "uma tentativa que falhou e uma que gravou; nada depois");
        assertEquals(0, fila.pendentes());
    }

    @Test
    void agenteAntigoAvisaUmaVez() {
        FilaDoPc fila = fila();
        respostas.add(Estado.AGENTE_ANTIGO);
        fila.gravarOuEnfileirar(pedido("a"));
        fila.esvaziar();
        fila.esvaziar();
        assertEquals(1, avisos.size(), avisos.toString());
        assertTrue(avisos.get(0).contains("atualize o agente do PC"), avisos.get(0));
        assertTrue(avisos.get(0).contains("1"), avisos.get(0));
        respostas.add(Estado.GRAVADO);
        fila.esvaziar();
        respostas.add(Estado.AGENTE_ANTIGO);
        fila.gravarOuEnfileirar(pedido("b"));
        assertEquals(2, avisos.size(), "depois de um GRAVADO, volta a avisar");
    }

    @Test
    void idInexistenteSaiDaFilaEAvisa() {
        FilaDoPc fila = fila();
        respostas.add(Estado.PC_OFFLINE);
        fila.gravarOuEnfileirar(pedido("x", "alterar_linha"));
        respostas.add(Estado.ID_INEXISTENTE);
        fila.esvaziar();
        assertEquals(0, fila.pendentes());
        assertEquals(1, avisos.size());
        assertTrue(avisos.get(0).contains("agenda.csv"), avisos.get(0));
    }

    @Test
    void removerReenviadoQueNaoAchaOIdContaComoFeito() {
        FilaDoPc fila = fila();
        respostas.add(Estado.FALHOU);
        fila.gravarOuEnfileirar(pedido("r", "remover_linha"));
        respostas.add(Estado.ID_INEXISTENTE);
        fila.esvaziar();
        assertEquals(0, fila.pendentes());
        assertTrue(avisos.isEmpty(), "a primeira tentativa pode ter removido sem a resposta chegar");
    }

    @Test
    void idInexistenteNaHoraVoltaParaQuemPediuSemEntrarNaFila() {
        FilaDoPc fila = fila();
        respostas.add(Estado.ID_INEXISTENTE);
        assertEquals(Estado.ID_INEXISTENTE, fila.gravarOuEnfileirar(pedido("x", "alterar_linha")).estado());
        assertEquals(0, fila.pendentes());
        assertTrue(avisos.isEmpty(), "quem pediu já recebe a resposta");
    }

    @Test
    void paraNoPrimeiroErro() {
        FilaDoPc fila = fila();
        respostas.add(Estado.PC_OFFLINE);
        fila.gravarOuEnfileirar(pedido("a"));
        respostas.add(Estado.PC_OFFLINE);
        fila.gravarOuEnfileirar(pedido("b"));
        respostas.add(Estado.PC_OFFLINE);
        fila.gravarOuEnfileirar(pedido("c"));
        enviados.clear();
        respostas.add(Estado.ARQUIVO_TRAVADO);
        fila.esvaziar();
        assertEquals(List.of("a"), enviados);
        assertEquals(3, fila.pendentes());
    }

    @Test
    void enfileirarGuardaEEsvaziaPeloExecutor() {
        List<Runnable> agendadas = new ArrayList<>();
        FilaDoPc fila = new FilaDoPc(pc, pasta.resolve("pendentes-pc.json"), json, avisos::add, agendadas::add);
        fila.enfileirar(pedido("m"));
        assertEquals(1, fila.pendentes());
        assertTrue(enviados.isEmpty(), "não espera o PC na hora");
        agendadas.forEach(Runnable::run);
        assertEquals(List.of("m"), enviados);
        assertEquals(0, fila.pendentes());
    }
}
