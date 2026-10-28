package dev.jarvis.ia.provedor;

import java.util.ArrayList;
import java.util.List;

/** O que o modelo devolveu: os blocos, por que parou e quanto gastou. */
public record RespostaDoModelo(List<Bloco> blocos, MotivoDeParada motivo, Uso uso) {

    public enum MotivoDeParada {
        /** Terminou de falar. */
        FIM,
        /** Pediu uma ou mais ferramentas. */
        FERRAMENTA,
        /** O provedor pausou um turno longo; continuar é reenviar a mensagem como está. */
        PAUSA,
        LIMITE_DE_TOKENS,
        RECUSA,
        OUTRO
    }

    /** O consumo da chamada. Quem soma ao custo é o cliente do provedor; isto é para quem decide. */
    public record Uso(long entrada, long saida, long cacheEscrito, long cacheLido,
            int buscasNaWeb) {

        public static Uso nenhum() {
            return new Uso(0, 0, 0, 0, 0);
        }
    }

    public RespostaDoModelo {
        blocos = List.copyOf(blocos);
        motivo = motivo == null ? MotivoDeParada.OUTRO : motivo;
        uso = uso == null ? Uso.nenhum() : uso;
    }

    /** Os textos, na ordem, para cada chamador juntar como já juntava. */
    public List<String> textos() {
        List<String> textos = new ArrayList<>();
        for (Bloco bloco : blocos) {
            if (bloco instanceof Bloco.Texto t) textos.add(t.texto());
        }
        return textos;
    }
}
