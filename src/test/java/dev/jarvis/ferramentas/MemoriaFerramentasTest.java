package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.memoria.EsquecimentosDeMemoria;
import dev.jarvis.memoria.MemoriaService;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class MemoriaFerramentasTest {
    @TempDir Path pasta;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicInteger usado = new AtomicInteger();
    private final AtomicBoolean apagou = new AtomicBoolean();
    private final RelogioFalso relogio = new RelogioFalso();
    private final Aprovacoes aprovacoes = new Aprovacoes(relogio);
    private final EsquecimentosDeMemoria esquecimentos = new EsquecimentosDeMemoria();

    private MemoriaService memoria() {
        return new MemoriaService(pasta.resolve("dados"), pasta.resolve("exclusoes"), json,
                Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneId.of("UTC")), ZoneId.of("America/Sao_Paulo"));
    }
    private OuvinteProgresso contexto(String texto, String id, long usuario) {
        return new OuvinteProgresso() {
            @Override public String canal() { return "telegram"; }
            @Override public long usuarioAutenticado() { return usuario; }
            @Override public String pedidoOriginal() { return texto; }
            @Override public String idPedido() { return id; }
            @Override public boolean consumirContexto(int caracteres) {
                return usado.addAndGet(caracteres) <= MemoriaService.LIMITE_CONTEXTO;
            }
            @Override public void marcarMemoriaEsquecida(String id, long revisao) { apagou.set(true); }
        };
    }

    @Test void modeloSoNaoAutorizaGravacaoEBuscaTemOrcamento() throws Exception {
        var s = memoria(); var registrar = new RegistrarMemoriaFerramenta(s, json);
        ObjectNode entrada = json.createObjectNode().put("categoria", "perfil").put("assunto", "cidade")
                .put("conteudo", "Mora em Curitiba").put("evidencia", "moro em Curitiba")
                .put("tipo", "declaracao_usuario");
        assertThrows(IllegalArgumentException.class, () -> registrar.executarDetalhado(entrada, contexto("oi", "t1", 42)));
        assertThrows(IllegalArgumentException.class, () -> registrar.executarDetalhado(entrada, contexto("lembre que moro em Curitiba", "t1", 0)));
        entrada.put("tipo", "observacao_verificada");
        assertTrue(registrar.executarDetalhado(entrada,
                contexto("lembre que moro em Curitiba", "t1", 42)).texto().contains("não verificada"));
        entrada.put("tipo", "declaracao_usuario");
        entrada.put("evidencia", "moro em São Paulo");
        assertThrows(IllegalArgumentException.class, () -> registrar.executarDetalhado(entrada,
                contexto("lembre que moro em Curitiba", "t1", 42)),
                "evidência que o usuário não escreveu não grava");
        entrada.put("evidencia", "moro em Curitiba");
        String saida = registrar.executarDetalhado(entrada, contexto("lembre que moro em Curitiba", "t1", 42)).texto();
        assertEquals("Anotei: cidade.", saida, "o que volta é o assunto, não id nem revisão");
        var buscar = new BuscarMemoriaFerramenta(s, json);
        assertTrue(buscar.executarDetalhado(json.createObjectNode().put("consulta", "cidade"), contexto("qual cidade?", "t2", 42)).texto().contains("Curitiba"));
        usado.set(4_000);
        assertTrue(buscar.executarDetalhado(json.createObjectNode().put("consulta", "cidade"), contexto("qual cidade?", "t3", 42)).texto().contains("esgotado"));
    }

    @Test
    @DisplayName("o conteúdo pode ser bem escrito: o que tem de ser literal é a evidência")
    void correcaoEmLinguagemNaturalComEvidencia() throws Exception {
        var s = memoria();
        var r = s.registrar(42, "projeto", "agendabarber", "stack", "Usa Angular no front",
                "declaracao_usuario", "usuario:telegram", "t0");
        var atualizar = new AtualizarMemoriaFerramenta(s, json);
        ObjectNode entrada = json.createObjectNode().put("id", r.id()).put("revisao", r.revisao())
                .put("conteudo", "Usa React no front do AgendaBarber")
                .put("evidencia", "agora é React").put("tipo", "declaracao_usuario");

        String saida = atualizar.executarDetalhado(entrada,
                contexto("isso mudou, agora é React", "t1", 42)).texto();

        assertEquals("Atualizei: stack.", saida);
        assertEquals("Usa React no front do AgendaBarber", s.ler(42, r.id()).conteudo());

        entrada.put("revisao", 2).put("conteudo", "Usa Vue").put("evidencia", "agora é Vue");
        assertThrows(IllegalArgumentException.class, () -> atualizar.executarDetalhado(entrada,
                contexto("isso mudou, agora é React", "t2", 42)),
                "evidência que não consta do pedido continua sendo recusada");
    }

    @Test
    @DisplayName("listar_memorias agrupa por categoria e não devolve id nem revisão")
    void listarAgrupaESemId() throws Exception {
        var s = memoria();
        s.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "telegram", "p1");
        s.registrar(42, "preferencias", "", "bebida", "Café sem açúcar", "declaracao_usuario", "telegram", "p2");
        var r = s.registrar(42, "projeto", "agendabarber", "stack", "Angular no front",
                "declaracao_usuario", "telegram", "p3");
        var listar = new ListarMemoriasFerramenta(s, json);

        String saida = listar.executarDetalhado(json.createObjectNode(),
                contexto("o que você sabe sobre mim?", "t1", 42)).texto();

        assertTrue(saida.contains("Perfil:\n- cidade: Curitiba"), saida);
        assertTrue(saida.contains("Preferências:\n- bebida: Café sem açúcar"), saida);
        assertTrue(saida.contains("Projeto agendabarber:\n- stack: Angular no front"), saida);
        assertFalse(saida.contains(r.id()), "id no meio da fala é texto de máquina: " + saida);
        assertFalse(saida.contains("revisão"), saida);
        assertFalse(saida.contains("declaracao_usuario"), saida);

        String doProjeto = listar.executarDetalhado(
                json.createObjectNode().put("projeto", "agendabarber"),
                contexto("o que você sabe do agendabarber?", "t2", 42)).texto();
        assertTrue(doProjeto.contains("stack"), doProjeto);
        assertFalse(doProjeto.contains("Curitiba"), doProjeto);

        usado.set(4_000);
        assertTrue(listar.executarDetalhado(json.createObjectNode(),
                contexto("e mais?", "t3", 42)).texto().contains("esgotado"));
    }

    @Test
    @DisplayName("memória vazia e usuário não autenticado não viram listagem")
    void listarExigeUsuarioEAvisaQuandoVazio() throws Exception {
        var s = memoria();
        var listar = new ListarMemoriasFerramenta(s, json);

        assertThrows(IllegalArgumentException.class, () -> listar.executarDetalhado(
                json.createObjectNode(), contexto("o que você sabe?", "t1", 0)));
        assertTrue(listar.executarDetalhado(json.createObjectNode(),
                contexto("o que você sabe?", "t2", 42)).texto().contains("Ainda não há nada"));
        assertEquals("Memória exige pedido autenticado.", listar.executar(json.createObjectNode()));
    }

    @Test
    @DisplayName("esquecer pergunta em português e só apaga na mensagem seguinte")
    void esquecerPassaPelaAprovacao() throws Exception {
        var s = memoria();
        var r = s.registrar(42, "perfil", "", "cidade", "Mora em Curitiba desde 2019",
                "declaracao_usuario", "telegram", "t0");
        var esquecer = new EsquecerMemoriaFerramenta(s, json, aprovacoes, esquecimentos);
        ObjectNode entrada = json.createObjectNode().put("id", r.id());

        var pedido = esquecer.executarDetalhado(entrada, contexto("esquece minha cidade", "t1", 42));

        assertEquals("Vou apagar da memória: 'cidade: Mora em Curitiba desde 2019'. Posso?",
                pedido.confirmacao().pergunta(), "a pergunta é montada em Java, do registro no disco");
        assertEquals(pedido.confirmacao().pergunta(), pedido.texto());
        assertNotNull(s.ler(42, r.id()), "a chamada do modelo não pode apagar nada");
        assertFalse(apagou.get());

        // O caminho de verdade: o servidor classifica a resposta e consome a pendência.
        assertEquals(pedido.confirmacao().pergunta(),
                aprovacoes.pendenciaDe(42, "t2").orElseThrow());
        String resposta = aprovacoes.executarPendente(42, "t2").orElseThrow();

        assertEquals("Esqueci: cidade.", resposta);
        assertNull(s.ler(42, r.id()));
        assertEquals(r.id(), esquecimentos.drenar(42).orElseThrow().id(),
                "o recado é o que faz o histórico da conversa sair do disco");
    }

    @Test
    @DisplayName("no mesmo pedido, como outro usuário ou depois de dez minutos, nada é apagado")
    void esquecerRecusaConfirmacaoIndevida() throws Exception {
        var s = memoria();
        var r = s.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "telegram", "t0");
        var esquecer = new EsquecerMemoriaFerramenta(s, json, aprovacoes, esquecimentos);
        ObjectNode entrada = json.createObjectNode().put("id", r.id());
        esquecer.executarDetalhado(entrada, contexto("esquece minha cidade", "t1", 42));

        assertTrue(aprovacoes.executarPendente(42, "t1").isEmpty(), "o modelo não se autoconfirma");
        assertTrue(aprovacoes.executarPendente(43, "t2").isEmpty(), "outro usuário também não");
        relogio.avancar(Duration.ofMinutes(10));
        assertTrue(aprovacoes.executarPendente(42, "t2").isEmpty(), "a aprovação COMUM vence em 10 min");

        assertNotNull(s.ler(42, r.id()));
        assertTrue(esquecimentos.drenar(42).isEmpty());
    }

    @Test
    @DisplayName("id que não está na memória não abre pendência nenhuma")
    void esquecerIdDesconhecidoNaoPergunta() throws Exception {
        var s = memoria();
        var esquecer = new EsquecerMemoriaFerramenta(s, json, aprovacoes, esquecimentos);

        var resultado = esquecer.executarDetalhado(
                json.createObjectNode().put("id", "11111111-1111-1111-1111-111111111111"),
                contexto("esquece aquilo", "t1", 42));

        assertNull(resultado.confirmacao(),
                "ninguém deve responder 'Posso?' sobre lembrança que não existe");
        assertTrue(resultado.texto().contains("Não achei"), resultado.texto());
        assertTrue(aprovacoes.pendenciaDe(42, "t2").isEmpty());
    }

    @Test
    @DisplayName("as descrições ensinam o fluxo novo e não cobram frase exata do usuário")
    void descricoesRefletemOModeloNovo() {
        var s = memoria();
        String esquecer = new EsquecerMemoriaFerramenta(s, json, aprovacoes, esquecimentos).descricao();
        String listar = new ListarMemoriasFerramenta(s, json).descricao();

        assertTrue(esquecer.contains("pergunta de aprovação"), esquecer);
        assertTrue(esquecer.contains("sem pedir palavra, código nem frase exata"), esquecer);
        assertFalse(esquecer.contains("confirmo esquecer"), "o protocolo de código saiu: " + esquecer);
        assertFalse(esquecer.toLowerCase(java.util.Locale.ROOT).contains("duas mensagens"), esquecer);
        assertTrue(listar.contains("sem id"), listar);
        assertFalse(new ListarMemoriasFerramenta(s, json).esquemaEntrada().toString().contains("consulta"),
                "listar não pede palavra-chave: 'o que você sabe sobre mim' não é busca");
    }
}
