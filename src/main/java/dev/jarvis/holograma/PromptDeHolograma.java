package dev.jarvis.holograma;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

/**
 * O prompt de geração, em {@code src/main/resources/holograma/holograma-prompt.txt}, com
 * {@code {{OBJETO}}} e {@code {{DETALHE}}} trocados em Java.
 *
 * <p>Fica no jar, não em {@code config/}: mudar o prompt é mudar o contrato com o validador, e as
 * duas coisas viajam juntas no mesmo build.
 *
 * <p>O objeto é texto do dono e entra no {@code system}. Por isso ele chega aqui já higienizado —
 * sem quebra de linha nem caractere de controle, espaços colapsados, até {@link #MAX_OBJETO}
 * caracteres —, para não abrir uma seção nova no meio do prompt.
 */
@Component
public class PromptDeHolograma {

    public static final int MAX_OBJETO = 120;

    /**
     * O pedido de continuação, depois da resposta cortada que vai como mensagem do assistente.
     * Fixo em Java: quem recusa a meta e o id repetidos é a {@link MontagemDeHolograma}, e isto só
     * poupa os tokens de o modelo reescrever o que vai ser jogado fora.
     */
    public static final String CONTINUAR = "A resposta acima foi cortada. Continue o MESMO objeto: "
            + "escreva só as peças que faltam, uma por linha, sem repetir a linha de meta nem nenhum id "
            + "que já aparece acima, e termine com {\"t\":\"fim\"}. Nada além de linhas JSON.";
    private static final String RECURSO = "holograma/holograma-prompt.txt";

    private final String modelo;

    public PromptDeHolograma() {
        try (InputStream entrada = PromptDeHolograma.class.getClassLoader().getResourceAsStream(RECURSO)) {
            if (entrada == null) {
                throw new IllegalStateException("Prompt do holograma ausente: " + RECURSO);
            }
            this.modelo = new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Prompt do holograma ilegível", e);
        }
    }

    /** O system da geração. {@code objeto} já tem de ter passado por {@link #higienizar}. */
    public String sistema(String objeto, DetalheHolograma detalhe) {
        return modelo.replace("{{OBJETO}}", objeto).replace("{{DETALHE}}", detalhe.chave());
    }

    /** O objeto pronto para o prompt e para o log, ou vazio quando não sobra nada. */
    public static String higienizar(String bruto) {
        if (bruto == null) {
            return "";
        }
        String limpo = bruto.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ").replaceAll("\\s+", " ").trim();
        return limpo.length() > MAX_OBJETO ? limpo.substring(0, MAX_OBJETO).trim() : limpo;
    }
}
