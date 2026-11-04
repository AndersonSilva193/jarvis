# ADR-003: Retorno do PC como evento de domínio e pausas com dono persistido

**Status:** Accepted
**Date:** 2026-11-04
**Feature:** acompanhamento-de-tarefas
**Author:** 10x-Team (Architect + Staff Engineer)

## Context
As tarefas de fundo (`TarefasDeFundo`) já suspendem sem segurar thread, e uma espera `PC` ficava
pronta quando `pc.online()` voltava a ser verdade, numa varredura de 250 ms. Falta: o dono ver,
cancelar, pausar e retomar; a volta do PC ser um fato com aviso; espera com teto; e a diferença
entre "o dono recusou" e "o dono não respondeu". O `varrer` zera `t.pausar` a cada volta, então uma
pausa pedida pelo dono seria desfeita na hora.

## Decision
- `PcVoltou(em, ausencia)` é publicado pelo `PcService` — único gatilho para os dois rastreadores
  de contato (o E2 chama o `PcService` por callback). Publicação barata; ouvintes só marcam estado.
- A espera `PC` guarda o instante em que começou e fica pronta com um `PcVoltou` de instante
  igual ou posterior. A espera `computer_livre` mantém a checagem direta.
- Resultado "PC offline, nada iniciado" é marcado na origem (`ResultadoFerramenta.pcOffline`),
  sem casar texto; em tarefa vira suspensão e a ferramenta roda de novo na volta.
- `pausa` persistida na tarefa (`""`, `dono`, `sem_resposta`), separada do `pausar` volátil do
  Freio. Só `retomar_tarefa` a limpa.
- `aguardar` é uma suspensão `HORARIO` relativa; o agendador é o `varrer` já existente.

## Alternatives Considered
| Alternativa | Prós | Contras | Por que não |
|---|---|---|---|
| Continuar só com a varredura de `online()` | Zero código | Sem fato "o PC voltou" para avisar; outros interessados teriam de repetir a regra | O pedido é um evento de domínio |
| Evento publicado pelos dois serviços | Sem callback | Dois eventos por volta quando o agente usa os dois caminhos | Um gatilho só |
| Detectar "offline" pelo texto do resultado | Sem tocar nas ferramentas | Frágil; risco de repetir ação que de fato rodou | Marca explícita só onde nada foi iniciado |
| `Thread.sleep`/`ScheduledExecutor` por `aguardar` | Acorda no milissegundo | Segura thread ou cria segundo agendador | A varredura de 250 ms já é o agendador |

## Consequences
### Positive
- A volta do PC é um fato observável e testável; teto de espera explícito.
- Pausa do dono sobrevive ao `varrer`, ao Freio e ao restart.
### Negative
- Cinco ferramentas novas: o primeiro pedido depois do deploy reescreve o cache de `tools`.
- A HUD não mostra o progresso da tarefa até ser ajustada.
### Risks
- Evento perdido (corrida checagem × contato): mitigado pelo instante de início da espera.
- Referência implícita errada: só resolve sozinho com uma ativa; com várias pergunta.

## Dependencies
ADR-001/002 (política e recibos de plano continuam valendo); `TarefasDeFundo` (7.5).
