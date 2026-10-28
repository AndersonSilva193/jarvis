package dev.jarvis.aprovacao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.aprovacao.ClassificadorDeResposta.Decisao;
import dev.jarvis.ia.provedor.BancadaLocal;
import dev.jarvis.ia.provedor.Bloco;
import dev.jarvis.ia.provedor.ProvedorDeApoio;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.ia.provedor.RespostaDoModelo;

/** O classificador no modelo do PC, com o JSON da decisão como validador dos dois modelos. */
class ClassificadorLocalTest {

    private static final String PERGUNTA = "Posso rodar a rotina deploy?";

    @TempDir Path pasta;
    private BancadaLocal bancada;
    private final AtomicInteger chamadasAoClaude = new AtomicInteger();

    @AfterEach
    void fechar() throws Exception {
        if (bancada != null) bancada.close();
    }

    private ProvedorDeModelo claude(String texto) {
        return pedido -> {
            chamadasAoClaude.incrementAndGet();
            return new RespostaDoModelo(List.of(new Bloco.Texto(texto)), null, null);
        };
    }

    @Test
    @DisplayName("local sem JSON de decisão: reprovado, o Claude decide, e a reserva é contada")
    void semJsonVaiAoClaude() throws Exception {
        bancada = new BancadaLocal(pasta).responde("Acho que a pessoa concordou.");
        var classificador = new ClassificadorDeResposta(bancada.apoio(claude("{\"decisao\":\"sim\"}")));

        assertEquals(Decisao.SIM, classificador.classificar(PERGUNTA, "pode mandar ver, tô de acordo"));
        assertEquals(1, chamadasAoClaude.get());
        assertEquals(1, bancada.recebidos.size(), "o local foi tentado primeiro");
        assertEquals(1L, bancada.medidor.localDeHoje().reservas().get(ProvedorDeApoio.REPROVADO));
    }

    @Test
    @DisplayName("'duvida' escrito pelo local é decisão: vale, e o Claude não é chamado")
    void duvidaDoLocalVale() throws Exception {
        bancada = new BancadaLocal(pasta).responde("{\"decisao\":\"duvida\"}");
        var classificador = new ClassificadorDeResposta(bancada.apoio(claude("{\"decisao\":\"sim\"}")));

        assertEquals(Decisao.DUVIDA, classificador.classificar(PERGUNTA, "hmm, sei lá, talvez depois"));
        assertEquals(0, chamadasAoClaude.get());
        assertTrue(bancada.medidor.localDeHoje().reservas().isEmpty());
    }

    @Test
    @DisplayName("a lista de graça continua decidindo antes de qualquer modelo")
    void listaAntesDoModelo() throws Exception {
        bancada = new BancadaLocal(pasta);
        var classificador = new ClassificadorDeResposta(bancada.apoio(claude("{}")));
        assertEquals(Decisao.SIM, classificador.classificar(PERGUNTA, "pode"));
        assertTrue(bancada.recebidos.isEmpty());
        assertEquals(0, chamadasAoClaude.get());
    }
}
