package dev.jarvis.web;

import dev.jarvis.inicializacao.Introducao;

/**
 * A introdução como a HUD a lê ao acender.
 *
 * Além do texto que vira voz, os números alimentam as linhas de checagem da sequência de abertura
 * ("SISTEMAS MONITORADOS... 3/3") — a tela não faz uma segunda chamada para saber o que já foi
 * apurado aqui.
 */
public record InicializacaoDto(
        String texto,
        int sitesOk,
        int sitesTotal,
        boolean climaOk,
        int lembretesHoje,
        String geradoEm) {

    public static InicializacaoDto de(Introducao introducao) {
        return new InicializacaoDto(introducao.texto(), introducao.sitesOk(),
                introducao.sitesTotal(), introducao.climaOk(), introducao.lembretesHoje(),
                introducao.geradoEm());
    }
}
