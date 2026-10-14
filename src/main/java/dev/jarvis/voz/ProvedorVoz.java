package dev.jarvis.voz;

import java.io.IOException;

/**
 * Quem sabe transformar texto em áudio falado.
 *
 * Só existe uma implementação de verdade ({@link ElevenLabsVoz}): a "voz do navegador" não é um
 * provedor daqui, é a HUD sintetizando sozinha quando este aqui não pode atender.
 */
public interface ProvedorVoz {

    /** Nome curto do provedor, como aparece na resposta da API e no log. */
    String nome();

    /**
     * Sintetiza o texto e devolve o MP3.
     *
     * @throws CotaDeVozException quando o provedor recusou por falta de crédito, chave ou limite
     *                            de chamadas — motivo para trocar de modo, não para tentar de novo
     * @throws IOException        quando o provedor está fora do ar: a fala de agora sai pelo
     *                            navegador, mas o modo continua o mesmo
     */
    byte[] sintetizar(String texto) throws CotaDeVozException, IOException, InterruptedException;
}
