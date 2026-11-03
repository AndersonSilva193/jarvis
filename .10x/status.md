# Status

**Feature ativa:** politica-de-autonomia
**Fase:** 4 — Implementação (em andamento)

- [x] Fase 0 — Brainstorm: desenho aprovado; piso PERGUNTA para computer_use.iniciar (decisão do dono)
- [x] Fase 2 — Design: ADR-001, architect/politica-de-autonomia.md
- [ ] Fase 4 — Implementação
  - [ ] autonomia: Nivel, AcaoAutonoma, PoliticaDeAutonomia
  - [ ] config/autonomia.md
  - [ ] Aprovacoes.encaminhar + diário com `perguntada`
  - [ ] 6 ferramentas + MonitorDeSites
  - [ ] PromptDeSistema/SistemaDoJarvis com resumo gerado
  - [ ] minhas_permissoes
  - [ ] testes; `mvn -q -B package`
- [ ] Fase 5 — Verificação (QA + segurança)

Observação: o host não tem Java/Maven; o build roda em `maven:3.9-eclipse-temurin-21` via Docker.
