package dev.jarvis.web;

import java.time.format.DateTimeFormatter;
import java.util.List;

import dev.jarvis.briefing.BriefingService.Briefing;
import dev.jarvis.briefing.DadosSecao;

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

    /**
     * Uma seção com as duas leituras: o texto pronto (que a HUD fala) e os dados estruturados
     * (que a HUD desenha). Cada seção tem a sua forma de `dados`, pelo título; seção que falhou
     * vem com `dados` nulo e a explicação no `conteudo`.
     */
    public record SecaoDto(String titulo, String conteudo, DadosSecao dados) {
    }

    /** @param geradoAgora false quando a resposta reaproveita um briefing montado antes. */
    public static BriefingDto de(Briefing briefing, boolean geradoAgora) {
        return new BriefingDto(
                briefing.geradoEm().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                briefing.nomeUsuario(),
                briefing.secoes().stream().map(s -> new SecaoDto(s.titulo(), s.conteudo(), s.dados())).toList(),
                briefing.paraFala(),
                geradoAgora);
    }
}
