package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.LixeiraPcService;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.pc.ResultadoPc;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A ferramenta apagar_arquivo: a pergunta montada do nome real, e o arquivo saindo de vista
 * somente depois de uma mensagem nova do usuário.
 */
class ApagarArquivoFerramentaTest {

    private static final long CHAT = 42;
    /** O id do pedido que criou a aprovação: confirmar com ele é o modelo se autoconfirmando. */
    private static final String PEDIDO = "pedido-do-turno";
    private static final String ID = "11111111-1111-1111-1111-111111111111";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RelogioFalso relogio = new RelogioFalso();
    private final PcService pc = new PcService(relogio);
    private final Aprovacoes aprovacoes = new Aprovacoes(relogio);
    private final LixeiraPcService lixeira = new LixeiraPcService(pc, JSON);
    private final ApagarArquivoFerramenta ferramenta =
            new ApagarArquivoFerramenta(lixeira, aprovacoes, JSON);

    /** O que o agente de mentira recebeu, para provar o que chegou (e o que não chegou) ao PC. */
    private final List<ComandoPc> recebidos = new CopyOnWriteArrayList<>();

    private void pcOnline() {
        pc.proximoComando(Duration.ofMillis(10), null);
    }

    private static ObjectNode entrada(String id) {
        ObjectNode entrada = JSON.createObjectNode();
        if (id != null) entrada.put("id", id);
        return entrada;
    }

    private ResultadoFerramenta chamar(ObjectNode entrada) throws Exception {
        return ferramenta.executarDetalhado(entrada, new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return CHAT; }
            @Override public String idPedido() { return PEDIDO; }
            @Override public String canal() { return "telegram"; }
        });
    }

    /**
     * Um agente de mentira que responde cada comando como o PC responderia: o catálogo para
     * {@code listar_arquivos} e o resultado da lixeira para {@code mover_para_lixeira}.
     */
    private Thread agente(String nomeCatalogado, boolean moveuOk, String mensagem) {
        Thread agente = new Thread(() -> {
            for (int i = 0; i < 4; i++) {
                Optional<ComandoPc> comando = pc.proximoComando(Duration.ofSeconds(2), null);
                if (comando.isEmpty()) return;
                ComandoPc c = comando.get();
                recebidos.add(c);
                if ("listar_arquivos".equals(c.acao())) {
                    // A forma exata do CatalogoDeArquivos.listar do agente: os itens vêm em
                    // "arquivos" (só a lixeira usa "itens"), com os mesmos campos que ele manda.
                    ObjectNode dados = JSON.createObjectNode()
                            .put("total", nomeCatalogado == null ? 0 : 1)
                            .put("pagina", 1).put("tamanhoPagina", 50);
                    var arquivos = dados.putArray("arquivos");
                    if (nomeCatalogado != null)
                        arquivos.addObject().put("id", ID).put("nome", nomeCatalogado)
                                .put("formato", nomeCatalogado.substring(
                                        nomeCatalogado.lastIndexOf('.') + 1))
                                .put("tamanho", 128)
                                .put("modificadoEm", "2026-10-21T12:00:00Z")
                                .put("versaoEsperada", "a".repeat(64))
                                .put("estado", "disponivel");
                    dados.put("temMais", false);
                    pc.concluir(new ResultadoPc(c.id(), true, "ok", null, null, false, dados));
                } else {
                    ObjectNode dados = JSON.createObjectNode().put("nome", nomeCatalogado);
                    pc.concluir(new ResultadoPc(c.id(), moveuOk, mensagem, null, null, false,
                            moveuOk ? dados : null));
                }
            }
        });
        agente.setDaemon(true);
        agente.start();
        return agente;
    }

    private boolean moveuAlgum() {
        return recebidos.stream().anyMatch(c -> "mover_para_lixeira".equals(c.acao()));
    }

    @Test
    @DisplayName("a pergunta usa o nome do catálogo do agente e nada é movido na chamada do modelo")
    void perguntaVemDoNomeReal() throws Exception {
        pcOnline();
        agente("nota.md", true, "arquivo movido para a lixeira");

        ResultadoFerramenta pedido = chamar(entrada(ID));

        assertEquals("Vou mover nota.md para a lixeira (dá para recuperar por 30 dias). Posso?",
                pedido.confirmacao().pergunta(),
                "a pergunta é montada em Java, do nome que o agente devolveu");
        assertEquals(pedido.confirmacao().pergunta(), pedido.texto(),
                "o modelo recebe a pergunta para repetir, não um protocolo de palavra exata");
        assertFalse(moveuAlgum(), "a chamada do modelo não pode apagar nada");
    }

    @Test
    @DisplayName("só a mensagem seguinte move o arquivo, e a frase afirma o que foi feito")
    void somenteAMensagemSeguinteMove() throws Exception {
        pcOnline();
        agente("nota.md", true, "arquivo movido para a lixeira");
        chamar(entrada(ID));

        String resposta = aprovacoes.tratar("confirmo", CHAT, "mensagem-seguinte").orElseThrow();

        assertEquals("Movi nota.md para a lixeira. Para trazer de volta, diga restaura.", resposta);
        assertTrue(moveuAlgum(), "a aprovação consumida é que manda o comando ao PC");
    }

    @Test
    @DisplayName("o caminho natural: a pergunta fica em aberto e o 'sim' do servidor executa")
    void aprovacaoEmLinguagemNatural() throws Exception {
        pcOnline();
        agente("nota.md", true, "arquivo movido para a lixeira");
        chamar(entrada(ID));

        // "pode" não é frase fixa do Aprovacoes: quem o entende é o ClassificadorDeResposta, e o
        // Assistente então chama executarPendente. É este par que o usuário exercita de verdade.
        assertEquals("Vou mover nota.md para a lixeira (dá para recuperar por 30 dias). Posso?",
                aprovacoes.pendenciaDe(CHAT, "mensagem-seguinte").orElseThrow(),
                "é a pergunta pendente que o classificador recebe junto com a resposta");
        assertTrue(aprovacoes.tratar("pode", CHAT, "mensagem-seguinte").isEmpty(),
                "mensagem comum não decide nada sozinha");
        assertFalse(moveuAlgum());

        String resposta = aprovacoes.executarPendente(CHAT, "mensagem-seguinte").orElseThrow();

        assertEquals("Movi nota.md para a lixeira. Para trazer de volta, diga restaura.", resposta);
        assertTrue(moveuAlgum());
    }

    @Test
    @DisplayName("o modelo não aprova: dentro do mesmo pedido, ou como outro usuário, é recusado")
    void modeloNaoAprovaNoMesmoPedido() throws Exception {
        pcOnline();
        agente("nota.md", true, "arquivo movido para a lixeira");
        chamar(entrada(ID));

        assertTrue(aprovacoes.tratar("confirmo", CHAT, PEDIDO).orElseThrow().contains("recusada"));
        assertTrue(aprovacoes.tratar("confirmo", 7, "mensagem-seguinte").orElseThrow()
                .contains("recusada"), "outro usuário também não");
        assertFalse(moveuAlgum(), "em nenhum desses caminhos o arquivo saiu de vista");
    }

    @Test
    @DisplayName("a aprovação de apagar é COMUM: vence em 10 minutos, sem apagar nada")
    void aprovacaoVenceEmDezMinutos() throws Exception {
        pcOnline();
        agente("nota.md", true, "arquivo movido para a lixeira");
        chamar(entrada(ID));

        relogio.avancar(Duration.ofMinutes(10));

        assertTrue(aprovacoes.tratar("confirmo", CHAT, "mensagem-seguinte").orElseThrow()
                .contains("expirou"));
        assertFalse(moveuAlgum());
    }

    @Test
    @DisplayName("recusar em linguagem natural cancela a pendência sem apagar")
    void recusaCancelaSemApagar() throws Exception {
        pcOnline();
        agente("nota.md", true, "arquivo movido para a lixeira");
        chamar(entrada(ID));

        aprovacoes.cancelarPendente(CHAT, "mensagem-seguinte");

        assertTrue(aprovacoes.pendenciaDe(CHAT, "outra-mensagem").isEmpty());
        assertFalse(moveuAlgum());
    }

    @Test
    @DisplayName("ID fora do catálogo não gera pergunta nenhuma")
    void idDesconhecidoNaoPergunta() throws Exception {
        pcOnline();
        agente(null, true, "");

        ResultadoFerramenta resultado = chamar(entrada(ID));

        assertNull(resultado.confirmacao(),
                "não se incomoda o usuário com um 'Posso?' sobre um arquivo que não existe");
        assertTrue(resultado.texto().contains("Não achei esse arquivo"), resultado.texto());
        assertFalse(moveuAlgum());
    }

    @Test
    @DisplayName("ID que não é do catálogo é recusado antes de qualquer ida ao PC")
    void idInvalidoNaoVaiAoPc() throws Exception {
        pcOnline();

        ResultadoFerramenta resultado = chamar(entrada("../../etc/passwd"));

        assertTrue(resultado.texto().contains("ID inválido"), resultado.texto());
        assertNull(resultado.confirmacao());
        assertEquals(0, pc.naFila());
    }

    @Test
    @DisplayName("PC offline responde na hora e não enfileira nada")
    void offlineRespondeNaHora() throws Exception {
        ResultadoFerramenta resultado = chamar(entrada(ID));

        assertTrue(resultado.texto().contains("offline"), resultado.texto());
        assertNull(resultado.confirmacao());
        assertEquals(0, pc.naFila(), "travar o turno para no fim dizer 'offline' é o pior dos mundos");
    }

    @Test
    @DisplayName("arquivo aberto em outro programa chega como recusa, não como feito")
    void recusaDoAgenteNaoViraSucesso() throws Exception {
        pcOnline();
        agente("planilha.xlsx", false,
                "o arquivo está aberto em outro programa; feche-o e tente de novo");
        chamar(entrada(ID));

        String resposta = aprovacoes.tratar("confirmo", CHAT, "mensagem-seguinte").orElseThrow();

        assertTrue(resposta.startsWith("Não apaguei planilha.xlsx:"), resposta);
        assertTrue(resposta.contains("aberto em outro programa"), resposta);
    }

    @Test
    @DisplayName("sem pedido autenticado a ferramenta não faz nada")
    void exigePedidoAutenticado() throws Exception {
        pcOnline();

        String resposta = ferramenta.executarDetalhado(entrada(ID), new OuvinteProgresso() { })
                .texto();

        assertTrue(resposta.contains("pedido autenticado"), resposta);
        assertEquals(0, pc.naFila());
        assertEquals("Pedido autenticado necessário.", ferramenta.executar(entrada(ID)));
    }

    @Test
    @DisplayName("a descrição ensina o fluxo de aprovação sem mandar cobrar palavra do usuário")
    void descricaoExplicaOFluxo() {
        String descricao = ferramenta.descricao();

        assertTrue(descricao.contains("pergunta de aprovação"), descricao);
        assertTrue(descricao.contains("sem pedir palavra, código nem frase exata"), descricao);
        assertTrue(descricao.contains("restaurar_arquivo"), "o caminho de volta tem de ser citado");
        assertFalse(ferramenta.esquemaEntrada().toString().contains("nome"),
                "o nome não entra no schema: ele vem do catálogo, não do modelo");
    }
}
