# QA — acompanhamento-de-tarefas (2026-11-04)
Build `mvn -q -B package` (Docker maven:3.9-eclipse-temurin-21): 1326 testes, 1 falha
(HologramaServiceTest.aRetomadaReenviaOQueAHudPerdeuEContinua — intermitente, igual à linha de base,
passa isolada), 1 pulado. Cobertura e casos em decisions/qa/acompanhamento-de-tarefas.md.
Risco residual: progresso não aparece na HUD até o jarvis-hud aceitar `tarefa:<id>`.
