# Senior Engineer — acompanhamento-de-tarefas

- Ciclo: o `pcOffline` é conferido depois da execução, **fora** do `catch` que grava
  `suspensao` — senão a retomada entregaria "PC disponível." como resultado em vez de rodar a
  ferramenta. Desconta `chamadasPorAssinatura` e remove o detalhado.
- `varrer`: tarefa com `pausa` não vazia é pulada (não zera `pausar`, não agenda).
- `retomar`: limpa `pausa`; se a espera é APROVACAO sem resposta, `renovar()`.
- Espera máxima conferida no `varrer` para status ESPERANDO_PC com `esperaDesde`.
- Desfecho da aprovação lido no `varrer` antes de agendar (recusa finaliza sem rodar o executor).
- Cancelar responde com o resumo coletado **antes** de finalizar (o finalizar limpa estado).
- Callback do E2 chamado fora de qualquer bloco `synchronized` longo; ouvintes só gravam um volátil.
