package dev.jarvis.computer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;

/**
 * Percebe a tarefa que anda em círculos.
 *
 * <p>Os outros limites do {@link TarefasComputerUse} são tetos: ciclos, ações e cinco minutos.
 * Nenhum deles distingue trabalho que avança de modelo preso clicando no mesmo lugar — e as duas
 * coisas custam os mesmos tokens. Aqui a conta é outra: a <b>mesma ação com a mesma entrada</b>
 * seguida de uma <b>captura idêntica à anterior</b>, três vezes seguidas, é tarefa travada.
 *
 * <p>Um detector por tarefa, sem Spring e sem estado compartilhado: ele guarda só o último par
 * (assinatura da ação, resumo da tela) e um contador.
 *
 * <p>Duas escolhas que valem registro:
 *
 * <ul>
 *   <li>{@code wait} é <b>neutra</b> — não conta e não zera. Esperar é a resposta certa para tela
 *       lenta, e é o que as regras do prompt mandam fazer; puni-la encerraria justamente a tarefa
 *       que está se comportando bem.
 *   <li>a entrada é comparada pelo <b>JSON canônico</b> (chaves de objeto em ordem), e não pelo
 *       texto cru. Dois provedores — ou duas respostas do mesmo — podem escrever
 *       {@code {"x":1,"y":2}} e {@code {"y":2,"x":1}} para o mesmo clique, e isso é repetição.
 * </ul>
 */
public final class DetectorDeTravamento {

    /** Repetições idênticas seguidas que encerram a tarefa. */
    static final int REPETICOES = 3;

    /** A única ação que não entra na conta: tela lenta é motivo legítimo para esperar. */
    private static final String NEUTRA = "wait";

    private String ultimaAcao = "";
    private byte[] assinatura;
    private byte[] captura;
    private int repeticoes;

    /**
     * Registra uma ação já executada e a tela que ela deixou.
     *
     * @param acao          o nome da ação, como o provedor a pediu ({@code left_click}, {@code scroll}…)
     * @param entrada       os argumentos daquela ação; nulo conta como entrada vazia
     * @param capturaDepois os bytes da captura que voltou do PC. Vazia ou nula não muda nada: sem
     *                      tela para comparar não há como afirmar que nada mudou
     * @return {@code true} quando esta foi a terceira repetição seguida — a tarefa travou
     */
    public boolean registrar(String acao, JsonNode entrada, byte[] capturaDepois) {
        if (acao == null || NEUTRA.equals(acao)) return false;
        if (capturaDepois == null || capturaDepois.length == 0) return false;
        byte[] assinaturaAgora = sha256((acao + '\u0000' + Json.canonico(entrada))
                .getBytes(StandardCharsets.UTF_8));
        byte[] capturaAgora = sha256(capturaDepois);
        boolean repetiu = Arrays.equals(assinaturaAgora, assinatura)
                && Arrays.equals(capturaAgora, captura);
        repeticoes = repetiu ? repeticoes + 1 : 1;
        assinatura = assinaturaAgora;
        captura = capturaAgora;
        ultimaAcao = acao;
        return repeticoes >= REPETICOES;
    }

    /** O motivo que vai ao usuário e ao estado da tarefa. Vale depois de {@code registrar} dizer sim. */
    public String motivo() {
        return "parei: repeti " + ultimaAcao + " " + REPETICOES + " vezes sem mudança na tela";
    }

    private static byte[] sha256(byte[] valor) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(valor);
        } catch (java.security.NoSuchAlgorithmException ausente) {
            throw new IllegalStateException("SHA-256 deveria existir em qualquer JVM", ausente);
        }
    }
}
