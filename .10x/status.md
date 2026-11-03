# Status

**Feature ativa:** politica-de-autonomia
**Fase:** 5 — Verificação concluída; aguardando revisão do dono (sem push, sem deploy)

- [x] Fase 0 — Brainstorm: desenho aprovado; piso PERGUNTA para computer_use.iniciar (decisão do dono)
- [x] Fase 2 — Design: ADR-001, architect/politica-de-autonomia.md
- [x] Fase 4 — Implementação (ver sde/politica-de-autonomia.md)
- [x] Fase 5 — Verificação: qa/ e security/politica-de-autonomia.md
- [ ] Fase 6 — Entrega: merge da branch `feat/politica-de-autonomia` + `docker compose up -d --build`

Build: 1246 testes, 1 falha pré-existente (HologramaServiceTest), igual à linha de base.
Host sem Java/Maven; build via Docker `maven:3.9-eclipse-temurin-21`.
