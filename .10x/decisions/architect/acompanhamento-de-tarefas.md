# Architect — acompanhamento-de-tarefas (ADR-003)

## Componentes
- `dev.jarvis.pc.PcVoltou` (record): `em`, `ausencia` (nula se nunca houve contato).
- `PcService.registrarContato`: decide a volta (> 90 s ou primeiro contato) e publica. O E2
  (`OperacoesPcService.aoContato`) chama o mesmo método.
- `EsperasDaTarefa`: `@EventListener PcVoltou` guarda o último retorno; espera `PC` pronta quando
  `retorno >= inicio da espera`.
- `TarefasDeFundo`: `pausar/retomar`, `cancelar` com resumo, espera máxima, desfechos da aprovação,
  aviso "O PC voltou", progresso para a HUD.
- `ResolvedorDeTarefa` (pacote `tarefas`): id, palavras ou implícito.
- Ferramentas: `tarefas_em_andamento`, `cancelar_tarefa`, `pausar_tarefa`, `retomar_tarefa`
  (conversa) e `aguardar` (só tarefa).
- `Aprovacoes.AutorizacaoDaTarefa`: expõe `recusada()`/`semResposta()` e `renovar()`.

## Fluxos
- PC offline → `pcOffline` → ciclo pergunta ao ouvinte → `SuspensaoDaTarefa(PC)` sem `suspensao`
  gravada no ciclo (a ferramenta roda de novo) → ESPERANDO_PC, `esperaDesde` gravado.
- `PcVoltou` → espera pronta → `varrer` avisa e agenda.
- Aprovação resolvida: aprovada → continua; recusada → CANCELADA + "Certo, parei"; sem resposta →
  PAUSADA `sem_resposta`; `retomar_tarefa` → `renovar()` → ESPERANDO_VOCE.

## Falhas
- Evento chega antes de a espera ser gravada: o instante de início resolve.
- Restart durante espera do PC: `esperaDesde` persistido; o primeiro contato após a partida conta
  como volta.
