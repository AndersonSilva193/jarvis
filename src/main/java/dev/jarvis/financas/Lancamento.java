package dev.jarvis.financas;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Um gasto ou um ganho do dono.
 *
 * <p>O valor é em <b>centavos</b>, num {@code long}: dinheiro em {@code double} soma 0,1 + 0,2 e
 * devolve 0,30000000000000004, e um resumo financeiro que erra o centavo perde a confiança inteira.
 *
 * <p>Remover não apaga: marca {@code removidoEm}. O lançamento sai das contas e da listagem, mas o
 * arquivo continua sabendo que ele existiu — é a lixeira das finanças, pelo motivo do
 * {@code apagar_arquivo}: o que sai de vista sem volta pede pergunta, e isto não precisa pedir.
 *
 * @param origem quem lançou: {@code telegram}, {@code hud} ou {@code escritorio} (um agente via MCP).
 */
public record Lancamento(String id, Tipo tipo, long centavos, String categoria, String descricao,
        LocalDate data, Instant criadoEm, String origem, Instant removidoEm) {

    public enum Tipo {
        GASTO("gasto"),
        GANHO("ganho");

        private final String chave;

        Tipo(String chave) {
            this.chave = chave;
        }

        public String chave() {
            return chave;
        }

        /** O tipo daquela chave, ou {@code null}. */
        public static Tipo porChave(String chave) {
            if (chave == null) {
                return null;
            }
            String procurada = chave.trim().toLowerCase(java.util.Locale.ROOT);
            for (Tipo tipo : values()) {
                if (tipo.chave.equals(procurada)) {
                    return tipo;
                }
            }
            return null;
        }
    }

    public boolean ativo() {
        return removidoEm == null;
    }

    Lancamento removidoEm(Instant quando) {
        return new Lancamento(id, tipo, centavos, categoria, descricao, data, criadoEm, origem, quando);
    }
}
