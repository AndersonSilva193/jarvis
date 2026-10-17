package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.memoria.ConfirmacaoEsquecimento;
import dev.jarvis.memoria.MemoriaService;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class MemoriaFerramentasTest {
    @TempDir Path pasta;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicInteger usado = new AtomicInteger();
    private final AtomicBoolean apagou = new AtomicBoolean();
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
                .put("conteudo", "Curitiba").put("tipo", "declaracao_usuario");
        assertThrows(IllegalArgumentException.class, () -> registrar.executarDetalhado(entrada, contexto("oi", "t1", 42)));
        assertThrows(IllegalArgumentException.class, () -> registrar.executarDetalhado(entrada, contexto("lembre que moro em Curitiba", "t1", 0)));
        entrada.put("tipo", "observacao_verificada");
        assertTrue(registrar.executarDetalhado(entrada,
                contexto("lembre que moro em Curitiba", "t1", 42)).texto().contains("não verificada"));
        entrada.put("tipo", "declaracao_usuario");
        entrada.put("conteudo", "São Paulo");
        assertThrows(IllegalArgumentException.class, () -> registrar.executarDetalhado(entrada,
                contexto("lembre que moro em Curitiba", "t1", 42)));
        entrada.put("conteudo", "Curitiba");
        String saida = registrar.executarDetalhado(entrada, contexto("lembre que moro em Curitiba", "t1", 42)).texto();
        assertTrue(saida.contains("revisão=1"));
        var buscar = new BuscarMemoriaFerramenta(s, json);
        assertTrue(buscar.executarDetalhado(json.createObjectNode().put("consulta", "cidade"), contexto("qual cidade?", "t2", 42)).texto().contains("Curitiba"));
        usado.set(4_000);
        assertTrue(buscar.executarDetalhado(json.createObjectNode().put("consulta", "cidade"), contexto("qual cidade?", "t3", 42)).texto().contains("esgotado"));
    }

    @Test void confirmacaoExigeNovaMensagemDoMesmoUsuario() throws Exception {
        var s = memoria();
        var r = s.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "telegram", "t1");
        var esquecer = new EsquecerMemoriaFerramenta(s, json, new ConfirmacaoEsquecimento());
        ObjectNode entrada = json.createObjectNode().put("id", r.id());
        String aviso = esquecer.executarDetalhado(entrada, contexto("esqueça minha cidade", "t2", 42)).texto();
        assertNotNull(s.ler(42, r.id()));
        String codigo1 = Pattern.compile("[0-9]{6}$").matcher(aviso).results().findFirst().orElseThrow().group();
        entrada.put("codigo", codigo1);
        assertThrows(IllegalArgumentException.class, () -> esquecer.executarDetalhado(entrada,
                contexto("confirmo esquecer " + r.id() + " " + codigo1, "t2", 42)));
        // A tentativa no mesmo turno consome o desafio: o usuário deve pedir outro.
        entrada.remove("codigo");
        aviso = esquecer.executarDetalhado(entrada, contexto("esqueça minha cidade", "t3", 42)).texto();
        String codigo2 = Pattern.compile("[0-9]{6}$").matcher(aviso).results().findFirst().orElseThrow().group();
        entrada.put("codigo", codigo2);
        assertThrows(IllegalArgumentException.class, () -> esquecer.executarDetalhado(entrada,
                contexto("confirmo esquecer " + r.id() + " " + codigo2, "t4", 43)));
        entrada.remove("codigo");
        aviso = esquecer.executarDetalhado(entrada, contexto("esqueça minha cidade", "t5", 42)).texto();
        String codigo3 = Pattern.compile("[0-9]{6}$").matcher(aviso).results().findFirst().orElseThrow().group();
        entrada.put("codigo", codigo3);
        assertTrue(esquecer.executarDetalhado(entrada, contexto("confirmo esquecer " + r.id() + " " + codigo3, "t6", 42)).texto().contains(r.id()));
        assertTrue(apagou.get());
        assertNull(s.ler(42, r.id()));
    }
}
