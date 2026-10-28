package dev.jarvis.ia.provedor;

import tools.jackson.databind.JsonNode;

/**
 * Um pedaço de mensagem no formato neutro: o que qualquer provedor de modelo entende, mais uma
 * caixa fechada para o que só um deles entende.
 *
 * <p>São quatro, e é de propósito que sejam poucos. Texto, chamada de ferramenta e resultado de
 * ferramenta são o contrato comum de todo modelo com ferramentas; o resto — busca na web feita no
 * servidor, bloco de pensamento assinado, busca de ferramentas — é do provedor que o produziu, e
 * vai num {@link Opaco} que nenhum outro provedor recebe.
 */
public sealed interface Bloco {

    /**
     * Texto, do usuário ou do modelo.
     *
     * @param original o bloco cru de quem o produziu, ou {@code null} quando o texto nasceu em Java
     *                 (pedido, turno compacto, "(sem resposta)"). O {@code texto} vale para todos;
     *                 o original só volta para o provedor dele — é o que preserva as
     *                 {@code citations} de uma resposta com busca na web.
     */
    record Texto(String texto, Opaco original) implements Bloco {
        public Texto {
            texto = texto == null ? "" : texto;
        }

        public Texto(String texto) {
            this(texto, null);
        }
    }

    /** O modelo pedindo uma ferramenta do Jarvis. O {@code original} segue a regra do {@link Texto}. */
    record Chamada(String id, String nome, JsonNode entrada, Opaco original) implements Bloco {
        public Chamada {
            id = id == null ? "" : id;
            nome = nome == null ? "" : nome;
        }

        public Chamada(String id, String nome, JsonNode entrada) {
            this(id, nome, entrada, null);
        }
    }

    /** O que a ferramenta devolveu à chamada de mesmo {@code id}. */
    record Resultado(String id, String conteudo, boolean erro) implements Bloco {
        public Resultado {
            id = id == null ? "" : id;
            conteudo = conteudo == null ? "" : conteudo;
        }
    }

    /**
     * O que só o provedor que o produziu entende, guardado sem nenhuma mudança.
     *
     * @param soNoTurno   vale só dentro do turno em que nasceu e sai antes de o turno ir ao
     *                    histórico — o bloco de pensamento, preso ao prefixo exato que o produziu
     * @param consultaWeb a consulta, quando o bloco é uma busca na web; vazio no resto. É o que o
     *                    {@code CompactadorDeHistorico} lê para a nota, sem abrir a caixa
     */
    record Opaco(String provedor, JsonNode bruto, boolean soNoTurno, String consultaWeb)
            implements Bloco {
        public Opaco {
            provedor = provedor == null ? "" : provedor;
            consultaWeb = consultaWeb == null ? "" : consultaWeb;
        }

        public static Opaco de(String provedor, JsonNode bruto) {
            return new Opaco(provedor, bruto, false, "");
        }

        public boolean de(String provedor) {
            return this.provedor.equals(provedor);
        }
    }
}
