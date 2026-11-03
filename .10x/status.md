# Status

**Feature ativa:** concessoes-de-autonomia (sobre politica-de-autonomia, mesma branch)
**Fase:** 5 — Verificação concluída; aguardando revisão do dono (sem push, sem deploy)

- [x] Fase 0 — Brainstorm: 4 decisões do dono (tirar as 7 linhas; prazo = o mais cedo; oferta volta após +3; DIARIO_PASTA/concessoes.json)
- [x] Fase 2 — Design: ADR-002
- [x] Fase 3 — Planejamento
- [x] Fase 4 — Implementação (sde/concessoes-de-autonomia.md)
- [x] Fase 5 — Verificação (qa/, security/, reviews/2026-11-03-concessoes-*)
- [ ] Fase 6 — Entrega: merge da branch `feat/politica-de-autonomia` + `docker compose up -d --build`

Build: 1279 testes, 1 falha intermitente pré-existente (HologramaServiceTest), 1 pulado.
Aberto: `JarvisProperties.Historico` chega nulo do yml (HISTORICO_* ignorados) — pré-existente.
Host sem Java/Maven; build via Docker `maven:3.9-eclipse-temurin-21` com volume `jarvis-m2`.
