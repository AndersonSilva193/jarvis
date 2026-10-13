package dev.jarvis.briefing;

import dev.jarvis.briefing.BriefingService.Briefing;

/** Avisa que um briefing ficou pronto, venha o disparo do /bomdia, do cron, da ferramenta ou da API. */
public record BriefingGeradoEvento(Briefing briefing) {
}
