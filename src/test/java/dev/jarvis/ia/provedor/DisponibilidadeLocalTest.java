package dev.jarvis.ia.provedor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.RelogioFalso;

/** Cada motivo de o local não poder atender, lido do {@code X-Pc-Ia} como o agente o manda. */
class DisponibilidadeLocalTest {

    private static final String PRONTA = BancadaLocal.PRONTA;

    private final RelogioFalso relogio = new RelogioFalso();
    private final PcService pc = new PcService(relogio);
    private final DisponibilidadeLocal disponibilidade = new DisponibilidadeLocal(pc, props(), relogio);

    private static JarvisProperties props() {
        return new JarvisProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, new JarvisProperties.Pc("t", "/tmp/op.json", 1, 1, 1,
                        new JarvisProperties.Pc.IaLocal(true, "", 60, 6000)));
    }

    /** Uma consulta do agente trazendo este cabeçalho. */
    private void consulta(String ia) {
        pc.proximoComando(Duration.ZERO, null, ia);
    }

    private String motivo() {
        return disponibilidade.agora().motivo();
    }

    @Test
    @DisplayName("modelo carregado, GPU livre: pode")
    void pronta() {
        consulta(PRONTA);
        assertTrue(disponibilidade.agora().pode());
    }

    @Test
    @DisplayName("pc_offline: o agente nunca falou, ou parou de falar há mais de 90 s")
    void pcOffline() {
        assertEquals(DisponibilidadeLocal.PC_OFFLINE, motivo());
        consulta(PRONTA);
        relogio.avancar(Duration.ofSeconds(91));
        assertEquals(DisponibilidadeLocal.PC_OFFLINE, motivo());
    }

    @Test
    @DisplayName("ollama_offline: sem cabeçalho, IA desligada no PC ou Ollama fora do ar")
    void ollamaOffline() {
        consulta(null); // agente antigo: está online, mas não diz nada da IA
        assertEquals(DisponibilidadeLocal.OLLAMA_OFFLINE, motivo());
        consulta("ativo=0");
        assertEquals(DisponibilidadeLocal.OLLAMA_OFFLINE, motivo());
        consulta(PRONTA.replace("ollama=1", "ollama=0"));
        assertEquals(DisponibilidadeLocal.OLLAMA_OFFLINE, motivo());
    }

    @Test
    @DisplayName("ollama_offline: cabeçalho velho não vale, mesmo com o agente ainda online")
    void cabecalhoVelho() {
        consulta(PRONTA);
        relogio.avancar(Duration.ofSeconds(80));
        consulta(null); // segue em contato, sem renovar o cabeçalho
        relogio.avancar(Duration.ofSeconds(20));
        assertEquals(DisponibilidadeLocal.OLLAMA_OFFLINE, motivo());
    }

    @Test
    @DisplayName("gpu_ocupada: outra coisa usando a placa")
    void gpuOcupada() {
        consulta(PRONTA.replace("ocupada=0", "ocupada=1"));
        assertEquals(DisponibilidadeLocal.GPU_OCUPADA, motivo());
    }

    @Test
    @DisplayName("sem_vram: modelo fora da memória e sem espaço para ele; carregado, a VRAM baixa é dele")
    void semVram() {
        String frio = PRONTA.replace("carregado=1", "carregado=0");
        consulta(frio); // 3000 MB livres, 6000 exigidos
        assertEquals(DisponibilidadeLocal.SEM_VRAM, motivo());

        consulta(frio.replace("vramLivreMb=3000", "vramLivreMb=8000"));
        assertTrue(disponibilidade.agora().pode());

        consulta(frio.replace("gpu=1", "gpu=0").replace("vramLivreMb=3000;", ""));
        assertEquals(DisponibilidadeLocal.SEM_VRAM, motivo(), "sem leitura da GPU, só o que já está carregado");

        consulta(PRONTA); // 3000 livres, mas o modelo já está lá
        assertTrue(disponibilidade.agora().pode());
    }

    @Test
    @DisplayName("par torto no cabeçalho não derruba a leitura do resto")
    void parTorto() {
        consulta("lixo;=1;ativo=1;ollama=1;carregado=1;ocupada=0;vramLivreMb=abc");
        assertTrue(disponibilidade.agora().pode());
    }
}
