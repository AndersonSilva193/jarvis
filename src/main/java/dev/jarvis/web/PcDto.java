package dev.jarvis.web;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.nio.charset.StandardCharsets;

import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.ResultadoPc;

/**
 * O que trafega entre a VPS e o agente do PC.
 *
 * Validação à mão, como no resto da camada web (o projeto não usa Bean Validation). Aqui ela pesa
 * mais do que de costume: o corpo vem de um processo fora da VPS, a mensagem acaba dentro do
 * prompt do modelo e a imagem acaba na tela do usuário.
 */
public final class PcDto {

    /** Teto da imagem já decodificada. Ver o javadoc de {@link #erros()}. */
    static final int MAX_IMAGEM = 8 * 1024 * 1024;
    private static final int MAX_ID = 64;
    private static final int MAX_MENSAGEM = 1000;
    /** Teto de {@code dados} das consultas de arquivo, da lixeira e da edição. */
    static final int MAX_DADOS = 32_000;
    /**
     * Teto de {@code dados} do {@code inferir}: texto de modelo e tool_calls. É o padrão do
     * {@code inferencia.maxKib} do agente — um agente configurado acima disso recebe 400 em vez
     * de empurrar meio megabyte de resposta para dentro do prompt.
     */
    static final int MAX_DADOS_INFERENCIA = 256 * 1024;
    /**
     * Teto de uma parcial de {@code inferir} com stream, em caracteres. O agente manda a cada
     * quebra de linha ou 300 ms — uma linha de holograma tem uns 200 —, então isto só barra o torto.
     */
    static final int MAX_TEXTO_PARCIAL = 32_000;

    private PcDto() {
    }

    /** O comando como o agente o recebe. */
    public record Comando(String id, String acao, String argumento, String criadoEm,
            tools.jackson.databind.JsonNode dados) {
    }

    public static Comando de(ComandoPc comando) {
        return new Comando(comando.id(), comando.acao(), comando.argumento(), comando.criadoEm(), comando.dados());
    }

    /**
     * O pedido de fala do agente.
     *
     * @param frase a <b>chave</b> de uma frase do {@code FalaDaTela}, nunca o texto: o que a tela
     *              diz é decidido aqui na VPS, não na máquina de mesa
     */
    public record Fala(String frase) {
    }

    /**
     * O resultado como o agente o posta.
     *
     * @param ok            {@code Boolean} e não {@code boolean} de propósito: "mandou false" e
     *                      "não mandou nada" são coisas diferentes, e o primitivo as confundiria.
     *                      Também é o que faz o campo ausente cair na mensagem de erro daqui em
     *                      vez de num 400 cru do Jackson 3, que recusa primitivo ausente.
     * @param imagemBase64  o PNG/JPEG da captura, só em {@code captura_tela}
     */
    public record Resultado(String comandoId, Boolean ok, String mensagem, String imagemBase64,
            Boolean arquivo, tools.jackson.databind.JsonNode dados) {
        public Resultado(String comandoId, Boolean ok, String mensagem, String imagemBase64,
                Boolean arquivo) {
            this(comandoId, ok, mensagem, imagemBase64, arquivo, null);
        }
        public Resultado(String comandoId, Boolean ok, String mensagem, String imagemBase64) {
            this(comandoId, ok, mensagem, imagemBase64, false, null);
        }

        /**
         * Os problemas encontrados, em texto. Lista vazia significa que o corpo está bom.
         *
         * <p>Sobre a imagem: o teto de 8 MB decodificados fica abaixo dos 10 MB que o
         * {@code sendPhoto} aceita e dos 10 MB do {@code AnexosService}, então os dois destinos do
         * print sempre dão certo juntos — nunca uma captura que aparece na HUD mas não no Telegram.
         * E o mime é <b>farejado dos bytes</b>, nunca aceito do agente: um {@code image/svg+xml}
         * declarado chegaria à HUD como imagem e SVG é scriptável.
         */
        public List<String> erros() {
            return erros(MAX_DADOS);
        }

        /** Os mesmos problemas, com o teto de {@code dados} que a ação do comando permite. */
        public List<String> erros(int maxDados) {
            List<String> erros = new ArrayList<>();
            if (comandoId == null || comandoId.isBlank() || comandoId.length() > MAX_ID) {
                erros.add("comandoId ausente ou longo demais");
            }
            if (ok == null) {
                erros.add("informe ok (true ou false)");
            }
            if (mensagem != null && mensagem.length() > MAX_MENSAGEM) {
                erros.add("mensagem acima de " + MAX_MENSAGEM + " caracteres");
            }
            if (dados != null && (!dados.isObject()
                    || dados.toString().getBytes(StandardCharsets.UTF_8).length > maxDados)) {
                erros.add("dados estruturados inválidos ou acima de " + maxDados + " bytes");
            }
            if (imagemBase64 != null && !imagemBase64.isBlank()) {
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(imagemBase64);
                } catch (IllegalArgumentException e) {
                    erros.add("imagem em base64 invalida");
                    return erros;
                }
                if (bytes.length > MAX_IMAGEM) {
                    erros.add("imagem acima de " + MAX_IMAGEM / (1024 * 1024) + " MB");
                } else if (mimeDe(bytes) == null) {
                    erros.add("imagem precisa ser PNG ou JPEG");
                }
            }
            return erros;
        }

        public ResultadoPc paraDominio() {
            byte[] bytes = imagemBase64 == null || imagemBase64.isBlank()
                    ? null
                    : Base64.getDecoder().decode(imagemBase64);
            return new ResultadoPc(comandoId.trim(), Boolean.TRUE.equals(ok), mensagem,
                    bytes, bytes == null ? null : mimeDe(bytes), Boolean.TRUE.equals(arquivo), dados);
        }
    }

    /** O mime pelos bytes de assinatura, ou nulo quando não é imagem que aceitamos. */
    static String mimeDe(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return null;
    }
}
