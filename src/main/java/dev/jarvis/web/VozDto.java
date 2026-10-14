package dev.jarvis.web;

import dev.jarvis.voz.VozService;

/**
 * Converte a decisão do VozService no que a HUD lê.
 *
 * É aqui — e só aqui — que o id do áudio vira rota, do mesmo jeito que o ConversaDto monta
 * "/api/anexos/". A URL sai relativa: quem sabe o endereço da API é a HUD, que pode estar
 * atrás do nginx ou em localhost.
 */
public final class VozDto {

    private VozDto() {
    }

    /**
     * @param provedor "elevenlabs" quando há áudio para tocar, "navegador" quando a HUD fala
     * @param url      rota do MP3, só no caso da ElevenLabs
     * @param motivo   por que a HUD vai falar sozinha, só no caso do navegador
     */
    public record Fala(String provedor, String url, String motivo) {
    }

    public static Fala de(VozService.Fala fala) {
        if (fala.id() == null) {
            return new Fala(fala.provedor(), null, fala.motivo());
        }
        return new Fala(fala.provedor(), "/api/voz/audio/" + fala.id(), null);
    }
}
