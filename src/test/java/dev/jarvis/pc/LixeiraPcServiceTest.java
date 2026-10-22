package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.util.Json;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * O {@code nomePorId}: a varredura do catálogo que sustenta a pergunta de aprovação.
 *
 * <p>Os testes respondem com a <b>forma exata</b> do {@code CatalogoDeArquivos.listar} do agente —
 * itens sob {@code "arquivos"}, não sob {@code "itens"}. Um agente de mentira que use a chave
 * errada só prova que o código concorda com o próprio engano: foi assim que a confusão entre o
 * catálogo e a lixeira passou pelos testes e quebrou no PC real.
 */
class LixeiraPcServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ALVO = "11111111-1111-1111-1111-111111111111";

    private final RelogioFalso relogio = new RelogioFalso();
    private final PcService pc = new PcService(relogio);
    private final LixeiraPcService lixeira = new LixeiraPcService(pc, JSON);

    private final List<ComandoPc> recebidos = new CopyOnWriteArrayList<>();

    private static final OuvinteProgresso CONTEXTO = new OuvinteProgresso() {
        @Override public long usuarioAutenticado() { return 42; }
        @Override public String canal() { return "telegram"; }
    };

    private void pcOnline() {
        pc.proximoComando(Duration.ofMillis(10), null);
    }

    /** Catálogo de mentira com {@code paginas} páginas; o alvo mora na última. */
    private void agenteComPaginas(int paginas) {
        Thread agente = new Thread(() -> {
            for (int i = 0; i < paginas + 1; i++) {
                Optional<ComandoPc> comando = pc.proximoComando(Duration.ofSeconds(2), null);
                if (comando.isEmpty()) return;
                ComandoPc c = comando.get();
                recebidos.add(c);
                int pagina = (int) Json.numero(c.dados().path("pagina"));
                boolean ultima = pagina >= paginas;
                ObjectNode dados = JSON.createObjectNode()
                        .put("total", paginas).put("pagina", pagina).put("tamanhoPagina", 50);
                var arquivos = dados.putArray("arquivos");
                arquivos.addObject()
                        .put("id", ultima ? ALVO : "0000000" + pagina + "-0000-0000-0000-000000000000")
                        .put("nome", ultima ? "planilha_de_teste.xlsx" : "outro-" + pagina + ".md")
                        .put("formato", ultima ? "xlsx" : "md").put("tamanho", 10)
                        .put("estado", "disponivel");
                dados.put("temMais", !ultima);
                pc.concluir(new ResultadoPc(c.id(), true, "ok", null, null, false, dados));
            }
        });
        agente.setDaemon(true);
        agente.start();
    }

    @Test
    @DisplayName("acha o nome na primeira página, lendo a chave 'arquivos' do catálogo")
    void achaNaPrimeiraPagina() throws Exception {
        pcOnline();
        agenteComPaginas(1);

        assertEquals(Optional.of("planilha_de_teste.xlsx"), lixeira.nomePorId(ALVO, CONTEXTO));
        assertEquals(1, recebidos.size(), "uma página basta quando temMais é falso");
        assertEquals("listar_arquivos", recebidos.get(0).acao());
    }

    @Test
    @DisplayName("segue a paginação enquanto temMais for verdadeiro")
    void seguePaginacao() throws Exception {
        pcOnline();
        agenteComPaginas(3);

        assertEquals(Optional.of("planilha_de_teste.xlsx"), lixeira.nomePorId(ALVO, CONTEXTO));
        assertEquals(3, recebidos.size());
        assertEquals(List.of(1L, 2L, 3L), recebidos.stream()
                .map(c -> Json.numero(c.dados().path("pagina"))).toList());
    }

    @Test
    @DisplayName("ID fora do catálogo devolve vazio, sem inventar nome")
    void idDesconhecidoDaVazio() throws Exception {
        pcOnline();
        agenteComPaginas(1);

        assertTrue(lixeira.nomePorId("99999999-9999-9999-9999-999999999999", CONTEXTO).isEmpty());
    }

    @Test
    @DisplayName("ID malformado nem chega ao PC")
    void idMalformadoNaoVaiAoPc() throws Exception {
        pcOnline();

        assertTrue(lixeira.nomePorId("planilha_de_teste.xlsx", CONTEXTO).isEmpty());
        assertEquals(0, pc.naFila());
    }
}
