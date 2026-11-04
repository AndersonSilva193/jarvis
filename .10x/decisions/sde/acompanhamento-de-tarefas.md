# SDE — acompanhamento-de-tarefas

## O que foi feito
- `pc/PcVoltou` (record); `PcService.registrarContato` publica (primeiro contato ou > 90 s), fora da
  trava `contato`; construtor `@Autowired` recebe `ApplicationEventPublisher`; `OperacoesPcService.aoContato`.
- `JarvisProperties.Tarefas.esperaPcHoras` + `tarefaEsperaPcHoras()` (12); yml, `.env.example`.
- `ResultadoFerramenta.pcOffline` em controlar_pc, criar_arquivo, restaurar_arquivo, apagar_arquivo,
  consultas de arquivo, rodar_rotina, tarefa_no_pc (só com computer use configurado), edição.
- `SuspensaoDaTarefa`: `repetirFerramenta` e `Pergunta(recusada, semResposta, renovar)`.
- `ContextoDaTarefa.esperarPcCom/esperarPcSeEmTarefa`; ciclo desconta a assinatura e não guarda continuação.
- `EsperasDaTarefa`: `@EventListener PcVoltou`, `pcOffline()`, `aguardar(dur, motivo)`.
- `Aprovacoes.AutorizacaoDaTarefa`: estado `SEM_RESPOSTA`, `recusada()`, `semResposta()`, `renovar()`.
- `TarefaDeFundo`: `pausa`, `esperaDesde`, `avisoFinal` (persistidos os dois primeiros).
- `TarefasDeFundo`: pausa no `varrer`, espera máxima, desfecho da pergunta, aviso do PC, progresso HUD,
  `pausar`/`retomar`/`cancelar(…, avisarNoFim)`/`feitoNoDiario`/`agora`.
- `ResolvedorDeTarefa`, `TarefaFalada`; ferramentas `FerramentaDeTarefa` (base), `tarefas_em_andamento`,
  `cancelar_tarefa`, `pausar_tarefa`, `retomar_tarefa`, `aguardar`; filtros `SO_EM_TAREFA`/`FORA_DE_TAREFA`.
- `RegistroConversas.progressoDeTarefa`. CLAUDE.md: seção "Acompanhar, pausar e esperar sozinho".

## Desvios
- O `pcOffline` virou suspensão com `repetirFerramenta` em vez de campo novo no record
  `ResultadoFerramenta` (não mexe nos construtores usados em todo lugar).
- Pausa do dono com a tarefa rodando para na próxima fronteira; status vira PAUSADA na varredura seguinte.

## Dívida
- A HUD descarta o progresso de id `tarefa:<id>` (jarvis-hud `conversa.service.ts:149`).
- Aprovação inicial do plano expirada continua cancelando (fora do escopo).
