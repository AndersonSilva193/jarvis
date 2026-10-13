package dev.jarvis.web;

import java.time.format.DateTimeFormatter;
import java.util.List;

import dev.jarvis.briefing.BriefingService.Briefing;

/**
 * O briefing no formato que a HUD consome.
 *
 * A data vai como texto ISO-8601 de propósito: o JsonMapper do projeto é criado cru em BeansConfig
 * (sem módulos), então não dependemos de como ele serializaria um ZonedDateTime. Do lado da HUD,
 * `new Date(geradoEm)` resolve direto.
 */
public record BriefingDto(
        String geradoEm,
        String nomeUsuario,
        List<SecaoDto> secoes,
        String texto,
        boolean geradoAgora) {

    public record SecaoDto(String titulo, String conteudo) {
    }

    /** @param geradoAgora false quando a resposta reaproveita um briefing montado antes. */
    public static BriefingDto de(Briefing briefing, boolean geradoAgora) {
        return new BriefingDto(
                briefing.geradoEm().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                briefing.nomeUsuario(),
                briefing.secoes().stream().map(s -> new SecaoDto(s.titulo(), s.conteudo())).toList(),
                briefing.paraTexto(),
                geradoAgora);
    }
}
