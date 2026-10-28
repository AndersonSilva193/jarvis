package dev.jarvis.ia.provedor;

/**
 * O seletor mudou de ideia: o dono trocou o modo, ou o provedor efetivo entrou ou saiu do fallback.
 * Evento de domínio, como o {@code BriefingGeradoEvento}; quem o leva à HUD como {@code ia.modo} é o
 * {@code EventosHud}.
 *
 * @param modo     {@code auto}, {@code local} ou {@code claude}
 * @param provedor quem está pensando agora: {@code local} ou {@code claude}
 * @param modelo   o nome do modelo efetivo ({@code qwen3.5:9b}, {@code claude-haiku-5-5})
 * @param motivo   por que não é o que o modo pediu, ou vazio — {@code pc_offline},
 *                 {@code ia_local_desligada}...
 */
public record ModoDeIaEvento(String modo, String provedor, String modelo, String motivo) {
}
