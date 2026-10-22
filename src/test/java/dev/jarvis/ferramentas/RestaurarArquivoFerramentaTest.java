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

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.LixeiraPcService;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.pc.ResultadoPc;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A ferramenta restaurar_arquivo: lista quando não há ID, e restaura sem pedir confirmação. */
class RestaurarArquivoFerramentaTest {

    private static final long CHAT = 42;
    private static final String ID_LIXEIRA = "33333333-3333-3333-3333-333333333333";
    private static final String ID_NOVO = "44444444-4444-4444-4444-444444444444";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RelogioFalso relogio = new RelogioFalso();
    private final PcService pc = new PcService(relogio);
    private final LixeiraPcService lixeira = new LixeiraPcService(pc, JSON);
    private final RestaurarArquivoFerramenta ferramenta =
            new RestaurarArquivoFerramenta(lixeira, JSON);

    private final List<ComandoPc> recebidos = new CopyOnWriteArrayList<>();

    private void pcOnline() {
        pc.proximoComando(Duration.ofMillis(10), null);
    }

    private ResultadoFerramenta chamar(ObjectNode entrada) throws Exception {
        return ferramenta.executarDetalhado(entrada, new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return CHAT; }
            @Override public String idPedido() { return "pedido"; }
            @Override public String canal() { return "telegram"; }
        });
    }

    /** O agente de mentira: responde a listagem ou a restauração com os dados que o PC manda. */
    private void agente(boolean ok, String mensagem, ObjectNode dados) {
        Thread agente = new Thread(() -> {
            Optional<ComandoPc> comando = pc.proximoComando(Duration.ofSeconds(2), null);
            comando.ifPresent(c -> {
                recebidos.add(c);
                pc.concluir(new ResultadoPc(c.id(), ok, mensagem, null, null, false,
                        ok ? dados : null));
            });
        });
        agente.setDaemon(true);
        agente.start();
    }

    private boolean restaurouAlgum() {
        return recebidos.stream().anyMatch(c -> "restaurar_da_lixeira".equals(c.acao()));
    }

    @Test
    @DisplayName("sem idLixeira, lista a lixeira e não restaura nada")
    void semIdListaSemRestaurar() throws Exception {
        pcOnline();
        ObjectNode dados = JSON.createObjectNode().put("total", 1).put("diasRetencao", 30);
        dados.putArray("itens").addObject().put("idLixeira", ID_LIXEIRA).put("nome", "nota.md");
        agente(true, "consulta concluída", dados);

        String resposta = chamar(JSON.createObjectNode()).texto();

        assertTrue(resposta.contains(ID_LIXEIRA), resposta);
        assertTrue(resposta.contains("nota.md"), resposta);
        assertFalse(restaurouAlgum(), "listar não pode restaurar por conta própria");
        assertEquals("listar_lixeira", recebidos.get(0).acao());
    }

    @Test
    @DisplayName("lixeira vazia é dita em uma frase, não num JSON vazio")
    void lixeiraVazia() throws Exception {
        pcOnline();
        agente(true, "consulta concluída", JSON.createObjectNode().put("total", 0));

        assertEquals("A lixeira está vazia.", chamar(JSON.createObjectNode()).texto());
    }

    @Test
    @DisplayName("com idLixeira, restaura sem nenhuma confirmação e informa o ID novo")
    void restauraSemAprovacao() throws Exception {
        pcOnline();
        ObjectNode dados = JSON.createObjectNode().put("nome", "nota.md")
                .put("nomeOriginal", "nota.md").put("renomeado", false).put("id", ID_NOVO);
        agente(true, "arquivo restaurado da lixeira", dados);

        ResultadoFerramenta resultado =
                chamar(JSON.createObjectNode().put("idLixeira", ID_LIXEIRA));

        assertNull(resultado.confirmacao(),
                "desfazer o que foi apagado com 'Posso?' não precisa de outro 'Posso?'");
        assertTrue(resultado.texto().startsWith("Restaurei nota.md."), resultado.texto());
        assertTrue(resultado.texto().contains(ID_NOVO),
                "o ID antigo morreu no movimento; uma edição na sequência precisa do novo");
        assertTrue(restaurouAlgum());
    }

    @Test
    @DisplayName("o aviso de renomeio chega ao usuário: nada foi sobrescrito")
    void avisoDeRenomeio() throws Exception {
        pcOnline();
        ObjectNode dados = JSON.createObjectNode().put("nome", "nota-1.md")
                .put("nomeOriginal", "nota.md").put("renomeado", true).put("id", ID_NOVO)
                .put("aviso", "já existia nota.md na pasta; restaurei como nota-1.md");
        agente(true, "arquivo restaurado da lixeira", dados);

        String resposta = chamar(JSON.createObjectNode().put("idLixeira", ID_LIXEIRA)).texto();

        assertTrue(resposta.contains("Restaurei nota-1.md."), resposta);
        assertTrue(resposta.contains("já existia nota.md"), resposta);
    }

    @Test
    @DisplayName("idLixeira que não é UUID é recusado antes de ir ao PC")
    void idInvalidoNaoVaiAoPc() throws Exception {
        pcOnline();

        String resposta = chamar(JSON.createObjectNode().put("idLixeira", "nota.md")).texto();

        assertTrue(resposta.contains("inválido"), resposta);
        assertTrue(resposta.contains("Nada foi restaurado"), resposta);
        assertEquals(0, pc.naFila());
    }

    @Test
    @DisplayName("item que não está mais na lixeira chega como recusa")
    void itemInexistenteViraRecusa() throws Exception {
        pcOnline();
        agente(false, "não achei esse item na lixeira", null);

        String resposta = chamar(JSON.createObjectNode().put("idLixeira", ID_LIXEIRA)).texto();

        assertEquals("Não restaurei: não achei esse item na lixeira", resposta);
    }

    @Test
    @DisplayName("PC offline diz que a lixeira está indisponível, sem enfileirar")
    void offlineNaoEnfileira() throws Exception {
        String resposta = chamar(JSON.createObjectNode().put("idLixeira", ID_LIXEIRA)).texto();

        assertTrue(resposta.contains("offline"), resposta);
        assertEquals(0, pc.naFila());
    }

    @Test
    @DisplayName("a descrição manda listar antes e avisa do ID novo")
    void descricaoOrientaOModelo() {
        String descricao = ferramenta.descricao();

        assertTrue(descricao.contains("Sem idLixeira"), descricao);
        assertTrue(descricao.contains("não escolha sozinho"), descricao);
        assertTrue(descricao.contains("ID de catálogo NOVO"), descricao);
        assertTrue(descricao.contains("Não pede confirmação"),
                "prometer uma confirmação que não existe faria o modelo esperar por ela");
    }
}
