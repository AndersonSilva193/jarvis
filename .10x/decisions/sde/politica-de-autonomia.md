# SDE — politica-de-autonomia

## Construído
- `dev.jarvis.autonomia`: `Nivel`, `AcaoAutonoma` (7 chaves, padrão PERGUNTA; piso PERGUNTA em
  pc.desligar, pc.reiniciar, computer_use.iniciar), `PoliticaDeAutonomia` (`nivel`, `resumo`,
  `explicar`, `PROIBIDOS_FIXOS`, recarga por mtime, `ler` estático testável).
- `config/autonomia.md`: primeira versão = padrões atuais, comentado.
- `Aprovacoes.encaminhar` + `Encaminhamento` + `Aprovacoes.frase`; `rodar(p, canal, perguntada)`.
- `AcaoRegistrada.perguntada` (Boolean) + `semPergunta()`; `DiarioEmArquivo` lê/grava; briefing,
  `autonomas()` e "(aprovado)" usam `semPergunta()`.
- Ferramentas: rodar_rotina, esquecer_memoria, agendar_tarefa_planilha, tarefa_no_pc, controlar_pc
  (desligar/reiniciar), apagar_arquivo (lote) — todas via `encaminhar`. `MonitorDeSites` idem.
- `PromptDeSistema`/`SistemaDoJarvis`: lista à mão removida; resumo gerado + proibidos fixos.
- `minhas_permissoes` (grupo SISTEMA, simples).
- CLAUDE.md: mapa do código e item de segurança reescritos.

## Desvios do plano
- Nenhum de desenho. Descrições de `esquecer_memoria`, `agendar_tarefa_planilha`, `apagar_arquivo` e
  `controlar_pc` passaram a texto neutro ("pode devolver pergunta"/"ou recusa") — uma invalidação de
  cache de `tools` no deploy, depois estável.

## Dívida técnica
- `AcaoAutonoma.oQue(alvo)` assume que só rotina aceita alvo (verdade hoje).
- `HologramaServiceTest.aRetomadaReenviaOQueAHudPerdeuEContinua` já falhava antes desta mudança.
