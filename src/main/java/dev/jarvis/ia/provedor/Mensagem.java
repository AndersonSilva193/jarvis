package dev.jarvis.ia.provedor;

import java.util.List;

/** Uma mensagem no formato neutro: quem falou e os blocos que ela traz. */
public record Mensagem(Papel papel, List<Bloco> blocos) {

    public enum Papel {
        USUARIO("usuario"), ASSISTENTE("assistente");

        private final String chave;

        Papel(String chave) {
            this.chave = chave;
        }

        /** O nome que vai ao disco. */
        public String chave() {
            return chave;
        }

        /** O papel de uma chave, ou {@code null} quando ela não é nenhum. */
        public static Papel porChave(String chave) {
            for (Papel papel : values()) {
                if (papel.chave.equals(chave)) return papel;
            }
            return null;
        }
    }

    public Mensagem {
        blocos = List.copyOf(blocos);
    }

    /** A mensagem de um texto só, nascido em Java. */
    public static Mensagem texto(Papel papel, String texto) {
        return new Mensagem(papel, List.of(new Bloco.Texto(texto)));
    }

    /** Os blocos de texto juntos por linha em branco, sem aparar: um texto só sai como está. */
    public String texto() {
        StringBuilder sb = new StringBuilder();
        for (Bloco bloco : blocos) {
            if (bloco instanceof Bloco.Texto t) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(t.texto());
            }
        }
        return sb.toString();
    }
}
