package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.diario.AcaoRegistrada;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.LixeiraPcService;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.util.Json;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A ferramenta apagar_arquivo depois de virar "faz e avisa": o arquivo sai na própria chamada, e o
 * que ainda pergunta é o pedido que apaga em massa.
 *
 * <p>O agente de mentira responde com a <b>forma exata</b> do agente real — o catálogo com os itens
 * sob {@code "arquivos"} e o {@code mover_para_lixeira} com {@code idLixeira}, {@code data} e
 * {@code expiraEm}. Um falso agente que responda com a chave que o código espera só provaria que o
 * código concorda com o próprio engano.
 */
class ApagarArquivoFerramentaTest {

    private static final long CHAT = 42;
    /** O id do pedido que criou a aprovação: confirmar com ele é o modelo se autoconfirmando. */
    private static final String PEDIDO = "pedido-do-turno";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RelogioFalso relogio = new RelogioFalso();
    private final PcService pc = new PcService(relogio);
    private final Aprovacoes aprovacoes = new Aprovacoes(relogio);
    private final LixeiraPcService lixeira = new LixeiraPcService(pc, JSON);
    private final ApagarArquivoFerramenta ferramenta =
            new ApagarArquivoFerramenta(lixeira, aprovacoes, JSON);

    /** O que o agente de mentira recebeu, para provar o que chegou (e o que não chegou) ao PC. */
    private final List<ComandoPc> recebidos = new CopyOnWriteArrayList<>();

    /** O catálogo do PC de mentira: ID → nome, na ordem em que o agente os listaria. */
    private final Map<String, String> catalogo = new LinkedHashMap<>();
    /** Os IDs cujo movimento o agente recusa, como um arquivo aberto no Excel. */
    private final Set<String> recusados = new java.util.HashSet<>();

    private Thread agente;

    @AfterEach
    void pararAgente() {
        if (agente != null) agente.interrupt();
    }

    /** O enésimo UUID de mentira: `id(1)` é `111…`, `id(2)` é `222…`. */
    private static String id(int n) {
        String digito = Integer.toHexString(n % 16);
        return digito.repeat(8) + "-" + digito.repeat(4) + "-" + digito.repeat(4) + "-"
                + digito.repeat(4) + "-" + digito.repeat(12);
    }

    private void pcOnline() {
        pc.proximoComando(Duration.ofMillis(10), null);
    }

    /** Põe `quantos` arquivos no catálogo de mentira e devolve os IDs deles. */
    private List<String> catalogar(int quantos) {
        List<String> ids = new ArrayList<>();
        for (int n = 1; n <= quantos; n++) {
            catalogo.put(id(n), "nota" + n + ".md");
            ids.add(id(n));
        }
        return ids;
    }

    private static ObjectNode entradaIds(List<String> ids) {
        ObjectNode entrada = JSON.createObjectNode();
        var lista = entrada.putArray("ids");
        ids.forEach(lista::add);
        return entrada;
    }

    private ResultadoFerramenta chamar(ObjectNode entrada) throws Exception {
        return chamar(entrada, PEDIDO);
    }

    private ResultadoFerramenta chamar(ObjectNode entrada, String pedido) throws Exception {
        return ferramenta.executarDetalhado(entrada, new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return CHAT; }
            @Override public String idPedido() { return pedido; }
            @Override public String canal() { return "telegram"; }
        });
    }

    /**
     * Um agente de mentira que serve comandos até o teste acabar.
     *
     * @param comRetencao quando falso, o {@code mover_para_lixeira} responde sem {@code data} nem
     *                    {@code expiraEm} — um agente mais velho, de onde o prazo não se deduz.
     */
    private void agente(boolean comRetencao) {
        agente = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                Optional<ComandoPc> comando = pc.proximoComando(Duration.ofMillis(200), null);
                if (comando.isEmpty()) continue;
                ComandoPc c = comando.get();
                recebidos.add(c);
                if ("listar_arquivos".equals(c.acao())) pc.concluir(listagem(c));
                else pc.concluir(movimento(c, comRetencao));
            }
        });
        agente.setDaemon(true);
        agente.start();
    }

    /** A forma do {@code CatalogoDeArquivos.listar}: os itens vêm em "arquivos", não em "itens". */
    private ResultadoPc listagem(ComandoPc c) {
        ObjectNode dados = JSON.createObjectNode()
                .put("total", catalogo.size()).put("pagina", 1).put("tamanhoPagina", 50);
        var arquivos = dados.putArray("arquivos");
        catalogo.forEach((id, nome) -> arquivos.addObject().put("id", id).put("nome", nome)
                .put("formato", nome.substring(nome.lastIndexOf('.') + 1))
                .put("tamanho", 128).put("modificadoEm", "2026-10-21T12:00:00Z")
                .put("versaoEsperada", "a".repeat(64)).put("estado", "disponivel"));
        dados.put("temMais", false);
        return new ResultadoPc(c.id(), true, "ok", null, null, false, dados);
    }

    /** A forma do {@code LixeiraDeArquivos.mover}, com o idLixeira e o prazo que o agente calcula. */
    private ResultadoPc movimento(ComandoPc c, boolean comRetencao) {
        String alvo = Json.texto(c.dados().path("id"));
        if (recusados.contains(alvo))
            return new ResultadoPc(c.id(), false,
                    "o arquivo está aberto em outro programa; feche-o e tente de novo",
                    null, null, false, null);
        ObjectNode dados = JSON.createObjectNode()
                .put("idLixeira", "lixo-" + alvo)
                .put("id", alvo).put("nome", catalogo.get(alvo))
                .put("formato", "md").put("tamanho", 128).put("hash", "b".repeat(64))
                .put("movidoEm", "2026-10-15T12:00:00Z")
                .put("alteradoDesdeOCadastro", false);
        if (comRetencao) dados.put("data", "2026-10-15").put("expiraEm", "2026-11-14");
        return new ResultadoPc(c.id(), true, "arquivo movido para a lixeira", null, null, false,
                dados);
    }

    private List<String> movidos() {
        return recebidos.stream().filter(c -> "mover_para_lixeira".equals(c.acao()))
                .map(c -> Json.texto(c.dados().path("id"))).toList();
    }

    @Test
    @DisplayName("um arquivo sai na própria chamada, sem nenhuma pergunta")
    void apagaSemPerguntar() throws Exception {
        pcOnline();
        agente(true);
        catalogar(1);

        ResultadoFerramenta resultado = chamar(entradaIds(List.of(id(1))));

        assertNull(resultado.confirmacao(), "apagar vai para a lixeira, e a lixeira volta sozinha");
        assertEquals("Apaguei nota1.md. Fica na lixeira por 30 dias; se quiser, eu volto.",
                resultado.texto());
        assertEquals(List.of(id(1)), movidos(), "o arquivo saiu na chamada do modelo");
    }

    @Test
    @DisplayName("o prazo vem do agente: sem data e expiraEm, a frase não promete número nenhum")
    void prazoVemDoAgente() throws Exception {
        pcOnline();
        agente(false);
        catalogar(1);

        ResultadoFerramenta resultado = chamar(entradaIds(List.of(id(1))));

        assertEquals("Apaguei nota1.md. Fica na lixeira; se quiser, eu volto.", resultado.texto(),
                "melhor não prometer prazo do que prometer o errado");
    }

    @Test
    @DisplayName("cinco arquivos no mesmo pedido ainda saem direto")
    void cincoCabemNoOrcamento() throws Exception {
        pcOnline();
        agente(true);
        List<String> ids = catalogar(5);

        ResultadoFerramenta resultado = chamar(entradaIds(ids));

        assertNull(resultado.confirmacao());
        assertEquals(5, movidos().size());
        assertEquals("Apaguei 5 arquivos: nota1.md, nota2.md e mais 3. "
                + "Ficam na lixeira por 30 dias; se quiser, eu volto.", resultado.texto());
    }

    @Test
    @DisplayName("doze de uma vez não move nada e vira uma pendência só")
    void loteViraUmaPendencia() throws Exception {
        pcOnline();
        agente(true);
        List<String> ids = catalogar(12);

        ResultadoFerramenta pedido = chamar(entradaIds(ids));

        assertNotNull(pedido.confirmacao(), "o pedido em massa é o que ainda pergunta");
        assertEquals("Vou mover 12 arquivos para a lixeira: nota1.md, nota2.md e mais 10. Posso?",
                pedido.confirmacao().pergunta());
        assertEquals(pedido.confirmacao().pergunta(), pedido.texto(),
                "o modelo recebe a pergunta para repetir, não um protocolo de palavra exata");
        assertTrue(movidos().isEmpty(), "nada de meio-feito: a chamada inteira espera o 'sim'");

        String resposta = aprovacoes.executarPendente(CHAT, "mensagem-seguinte").orElseThrow();

        assertEquals(12, movidos().size(), "aprovada, a lista inteira é movida de uma vez");
        assertTrue(resposta.startsWith("Apaguei 12 arquivos: nota1.md, nota2.md e mais 10."),
                resposta);
    }

    @Test
    @DisplayName("o orçamento é do pedido: três e depois três, e o segundo trio pergunta")
    void orcamentoEhPorPedido() throws Exception {
        pcOnline();
        agente(true);
        List<String> ids = catalogar(6);

        assertNull(chamar(entradaIds(ids.subList(0, 3))).confirmacao());
        assertEquals(3, movidos().size());

        ResultadoFerramenta segunda = chamar(entradaIds(ids.subList(3, 6)));

        assertNotNull(segunda.confirmacao(), "o 6º arquivo do mesmo pedido leva a chamada à pergunta");
        assertEquals("Vou mover 3 arquivos para a lixeira: nota4.md, nota5.md e nota6.md. Posso?",
                segunda.confirmacao().pergunta());
        assertEquals(3, movidos().size(), "nada mais saiu enquanto a pergunta está em aberto");
    }

    @Test
    @DisplayName("pedido novo começa com o orçamento inteiro de novo")
    void pedidoNovoZeraOOrcamento() throws Exception {
        pcOnline();
        agente(true);
        List<String> ids = catalogar(10);

        chamar(entradaIds(ids.subList(0, 5)));
        ResultadoFerramenta outroTurno = chamar(entradaIds(ids.subList(5, 10)), "outro-pedido");

        assertNull(outroTurno.confirmacao(), "o orçamento é por pedido, não do dia");
        assertEquals(10, movidos().size());
    }

    @Test
    @DisplayName("a lixeira e os backups não são apagáveis, e a recusa não vai ao PC")
    void pastaProtegidaNaoEApagada() throws Exception {
        pcOnline();
        agente(true);
        catalogo.put(id(1), ".lixeira");
        catalogo.put(id(2), "backups");
        catalogo.put(id(3), "nota.md");

        ResultadoFerramenta protegida = chamar(entradaIds(List.of(id(1))));
        assertTrue(protegida.texto().contains("não saem de vista"), protegida.texto());

        // Junto de um arquivo comum, a chamada inteira é recusada: metade apagada não foi pedida.
        ResultadoFerramenta misturada = chamar(entradaIds(List.of(id(3), id(2))));
        assertTrue(misturada.texto().startsWith("Não apago backups:"), misturada.texto());

        assertTrue(movidos().isEmpty());
        assertNull(protegida.confirmacao());
        assertNull(misturada.confirmacao());
    }

    @Test
    @DisplayName("ID fora do catálogo recusa a chamada inteira, sem apagar os que existem")
    void idDesconhecidoRecusaTudo() throws Exception {
        pcOnline();
        agente(true);
        catalogar(1);

        ResultadoFerramenta so = chamar(entradaIds(List.of(id(9))));
        assertTrue(so.texto().contains("Não achei esse arquivo"), so.texto());

        ResultadoFerramenta comUmReal = chamar(entradaIds(List.of(id(1), id(9))));
        assertTrue(comUmReal.texto().startsWith("Não achei 1 dos 2 arquivos"), comUmReal.texto());

        assertTrue(movidos().isEmpty(), "nada sai de vista num pedido que o catálogo não confirma");
    }

    @Test
    @DisplayName("ID que não é do catálogo é recusado antes de qualquer ida ao PC")
    void idInvalidoNaoVaiAoPc() throws Exception {
        pcOnline();

        ResultadoFerramenta resultado = chamar(entradaIds(List.of("../../etc/passwd")));

        assertTrue(resultado.texto().contains("ID inválido"), resultado.texto());
        assertNull(resultado.confirmacao());
        assertEquals(0, pc.naFila());
    }

    @Test
    @DisplayName("acima de 50 IDs o pedido é para ser dividido, e o repetido conta uma vez")
    void tetoDeCinquenta() throws Exception {
        pcOnline();
        List<String> cinquentaEUm = new ArrayList<>();
        for (int n = 0; n < 51; n++) cinquentaEUm.add(java.util.UUID.randomUUID().toString());

        ResultadoFerramenta estourou = chamar(entradaIds(cinquentaEUm));

        assertTrue(estourou.texto().contains("o teto é 50"), estourou.texto());
        assertNull(estourou.confirmacao());
        assertEquals(0, pc.naFila(), "um pedido para dividir não chega a falar com o PC");

        // O mesmo ID repetido não gasta orçamento duas vezes nem pede o arquivo duas vezes.
        agente(true);
        catalogar(1);
        ResultadoFerramenta repetido = chamar(entradaIds(List.of(id(1), id(1), id(1))));

        assertEquals("Apaguei nota1.md. Fica na lixeira por 30 dias; se quiser, eu volto.",
                repetido.texto());
        assertEquals(List.of(id(1)), movidos());
    }

    @Test
    @DisplayName("PC offline responde na hora e não enfileira nada")
    void offlineRespondeNaHora() throws Exception {
        ResultadoFerramenta resultado = chamar(entradaIds(List.of(id(1))));

        assertTrue(resultado.texto().contains("offline"), resultado.texto());
        assertNull(resultado.confirmacao());
        assertEquals(0, pc.naFila(), "travar o turno para no fim dizer 'offline' é o pior dos mundos");
    }

    @Test
    @DisplayName("recusa do agente não vira sucesso, e não derruba os outros arquivos do lote")
    void recusaDoAgenteNaoViraSucesso() throws Exception {
        pcOnline();
        agente(true);
        catalogar(3);
        catalogo.put(id(2), "planilha.xlsx");
        recusados.add(id(2));

        ResultadoFerramenta resultado = chamar(entradaIds(List.of(id(1), id(2), id(3))));

        assertEquals("Apaguei 2 arquivos: nota1.md e nota3.md. Ficam na lixeira por 30 dias; "
                + "se quiser, eu volto. Não apaguei planilha.xlsx: o arquivo está aberto em outro "
                + "programa; feche-o e tente de novo.", resultado.texto());
    }

    @Test
    @DisplayName("quando nada dá certo a frase não afirma que apagou")
    void nenhumApagado() throws Exception {
        pcOnline();
        agente(true);
        catalogo.put(id(1), "planilha.xlsx");
        recusados.add(id(1));

        ResultadoFerramenta resultado = chamar(entradaIds(List.of(id(1))));

        assertTrue(resultado.texto().startsWith("Não apaguei planilha.xlsx:"), resultado.texto());
        assertTrue(resultado.texto().contains("aberto em outro programa"), resultado.texto());
    }

    @Test
    @DisplayName("a aprovação do lote é COMUM: vence em 10 minutos, sem apagar nada")
    void aprovacaoDoLoteVenceEmDezMinutos() throws Exception {
        pcOnline();
        agente(true);
        chamar(entradaIds(catalogar(6)));

        relogio.avancar(Duration.ofMinutes(10));

        assertTrue(aprovacoes.tratar("confirmo", CHAT, "mensagem-seguinte").orElseThrow()
                .contains("expirou"));
        assertTrue(movidos().isEmpty());
    }

    @Test
    @DisplayName("o modelo não aprova o lote: dentro do mesmo pedido, ou como outro usuário")
    void modeloNaoAprovaOLote() throws Exception {
        pcOnline();
        agente(true);
        chamar(entradaIds(catalogar(6)));

        assertTrue(aprovacoes.tratar("confirmo", CHAT, PEDIDO).orElseThrow().contains("recusada"));
        assertTrue(aprovacoes.tratar("confirmo", 7, "mensagem-seguinte").orElseThrow()
                .contains("recusada"), "outro usuário também não");
        assertTrue(movidos().isEmpty(), "em nenhum desses caminhos os arquivos saíram de vista");
    }

    @Test
    @DisplayName("sem pedido autenticado a ferramenta não faz nada")
    void exigePedidoAutenticado() throws Exception {
        pcOnline();

        String resposta = ferramenta
                .executarDetalhado(entradaIds(List.of(id(1))), new OuvinteProgresso() { }).texto();

        assertTrue(resposta.contains("pedido autenticado"), resposta);
        assertEquals(0, pc.naFila());
        assertEquals("Pedido autenticado necessário.",
                ferramenta.executar(entradaIds(List.of(id(1)))));
    }

    @Test
    @DisplayName("o diário guarda uma linha por arquivo, com o idLixeira que o traz de volta")
    void oDiarioGuardaOIdDaLixeira(@TempDir Path pasta) throws Exception {
        pcOnline();
        agente(true);
        List<String> ids = catalogar(2);
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Diario(null));
        var diario = new DiarioDeAcoes(props, JSON, relogio, pasta);
        var comDiario = new ApagarArquivoFerramenta(lixeira, aprovacoes, JSON, diario);
        try {
            comDiario.executarDetalhado(entradaIds(ids), new OuvinteProgresso() {
                @Override public long usuarioAutenticado() { return CHAT; }
                @Override public String idPedido() { return PEDIDO; }
                @Override public String canal() { return "telegram"; }
            });
            diario.aguardar();

            var acoes = diario.deHoje().acoes();

            assertEquals(2, acoes.size(), "uma linha por arquivo, não uma por lote");
            assertEquals(List.of("apaguei nota1.md", "apaguei nota2.md"),
                    acoes.stream().map(AcaoRegistrada::descricao).toList());
            assertEquals(List.of("lixo-" + id(1), "lixo-" + id(2)),
                    acoes.stream().map(AcaoRegistrada::referencia).toList(),
                    "é por este id que o desfazer encontra o arquivo na lixeira");
            assertTrue(acoes.stream().noneMatch(a -> a.tipo().autonoma() == false),
                    "apagar entrou na metade autônoma do diário");
        } finally {
            diario.fechar();
        }
    }

    @Test
    @DisplayName("a descrição diz que apaga direto, e o que fazer quando o lote pergunta")
    void descricaoExplicaOFluxo() {
        String descricao = ferramenta.descricao();

        assertTrue(descricao.contains("sem perguntar"), descricao);
        assertTrue(descricao.contains("restaurar_arquivo"), "o caminho de volta tem de ser citado");
        assertTrue(descricao.contains("sem pedir palavra, código nem frase exata"), descricao);
        assertFalse(ferramenta.esquemaEntrada().toString().contains("nome"),
                "o nome não entra no schema: ele vem do catálogo, não do modelo");
    }
}
