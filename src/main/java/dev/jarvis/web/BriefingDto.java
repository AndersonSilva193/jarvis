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
        String abertura,
        String texto,
        boolean geradoAgora) {

    /**
     * Uma seção com as três leituras: `conteudo` é a prosa que a HUD desenha, `dados` é a forma
     * estruturada que ela usa para montar o card (cada seção tem a sua, discriminada pelo título) e
     * `fala` é o mesmo conteúdo narrado pelo Jarvis, que é o que a HUD diz em voz alta. Sem
     * narração, `fala` repete o `conteudo`. Seção que falhou vem com `dados` nulo e a explicação no
     * `conteudo`.
     */
    public record SecaoDto(String titulo, String conteudo, DadosSecao dados, String fala) {
    }

    /** @param geradoAgora false quando a resposta reaproveita um briefing montado antes. */
    public static BriefingDto de(Briefing briefing, boolean geradoAgora) {
        return new BriefingDto(
                briefing.geradoEm().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                briefing.nomeUsuario(),
                briefing.secoes().stream()
                        .map(s -> new SecaoDto(s.titulo(), s.conteudo(), s.dados(), s.paraFala()))
                        .toList(),
                briefing.abertura(),
                briefing.paraFala(),
                geradoAgora);
    }
}
