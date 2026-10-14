package dev.jarvis.web;

import java.time.Duration;
import java.time.ZonedDateTime;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.jarvis.briefing.BriefingService;
import dev.jarvis.briefing.BriefingService.Briefing;
import dev.jarvis.briefing.Saudacao;

/** API que a tela da HUD consome. Todo endpoint aqui exige o token (ver TokenHudFiltro). */
@RestController
@RequestMapping("/api/briefing")
public class BriefingController {

    /**
     * Dentro dessa janela, o POST devolve o briefing que já existe em vez de montar outro.
     * Montar um briefing faz GET real em cada site monitorado, e a HUD não precisa castigar
     * a sua própria API a cada clique.
     */
    private static final Duration JANELA_DE_REUSO = Duration.ofSeconds(60);

    private final BriefingService briefing;
    private final EventosHud eventos;

    public BriefingController(BriefingService briefing, EventosHud eventos) {
        this.briefing = briefing;
        this.eventos = eventos;
    }

    /** Gera um briefing novo (ou reaproveita um de menos de 60 s) e devolve o JSON. */
    @PostMapping
    public BriefingDto gerar() {
        Briefing recente = briefing.ultimo().filter(BriefingController::ehRecente).orElse(null);
        if (recente != null) {
            return BriefingDto.de(recente, false);
        }
        return BriefingDto.de(briefing.gerar(), true);
    }

    /** O último briefing montado neste processo. 404 enquanto não houver nenhum. */
    @GetMapping("/ultimo")
    public ResponseEntity<BriefingDto> ultimo() {
        return briefing.ultimo()
                .map(b -> ResponseEntity.ok(BriefingDto.de(b, false)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Fluxo de eventos: "gerando" quando um briefing começa a ser montado e "briefing" quando
     * fica pronto — inclusive os disparados pelo /bomdia e pelo cron das 8h.
     */
    @GetMapping("/eventos")
    public SseEmitter eventos() {
        return eventos.registrar();
    }

    private static boolean ehRecente(Briefing b) {
        return Saudacao.reutilizavel(b.geradoEm(), ZonedDateTime.now(b.geradoEm().getZone()), JANELA_DE_REUSO);
    }
}
