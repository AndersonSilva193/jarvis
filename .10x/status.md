# Status

**Feature ativa:** acompanhamento-de-tarefas (branch `feat/acompanhamento-de-tarefas`, a partir do main f89e110)
**Fase:** 5 — Verificação concluída; aguardando revisão do dono (sem push, sem deploy)

- [x] Fase 0 — Brainstorm: desenho aprovado; HUD só no backend (decisão do dono)
- [x] Fase 2 — Design: ADR-003
- [x] Fase 3 — Planejamento (engineering-manager/acompanhamento-de-tarefas.md)
- [x] Fase 4 — Implementação (sde/acompanhamento-de-tarefas.md)
- [x] Fase 5 — Verificação (qa/, security/, reviews/2026-11-04-acompanhamento-*)
- [ ] Fase 6 — Entrega: merge da branch + `docker compose up -d --build` na VPS

Build: 1326 testes, 1 falha intermitente pré-existente (HologramaServiceTest), 1 pulado.

Anterior: concessoes-de-autonomia já está no main.
Repositório de trabalho: /root/claudecode/jarvis. /root/jarvis é produção — não mexer.
Host sem Java/Maven; build via Docker `maven:3.9-eclipse-temurin-21` com volume `jarvis-m2`.
