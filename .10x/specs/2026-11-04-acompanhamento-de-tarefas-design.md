# Acompanhamento de tarefas de fundo — design

**Data:** 2026-11-04 · **Feature:** acompanhamento-de-tarefas · **Aprovado pelo dono:** sim (HUD só no backend)

## Objetivo
O dono acompanha o que o Jarvis está fazendo, e as tarefas que dependem do PC ou do dono param e
voltam sozinhas.

## Escopo
1. Ferramentas de conversa (fora do canal `tarefa`): `tarefas_em_andamento`, `cancelar_tarefa`,
   `pausar_tarefa`, `retomar_tarefa`. Entrada opcional `tarefa` (id ou palavras do objetivo).
   Sem `tarefa`, ou só com dêitico ("isso", "ela", "aquela tarefa"), e com **uma** ativa → ela.
   Várias ativas sem casamento único → a resposta lista e pergunta qual; nada é feito.
   - Listagem: `objetivo — status em pt, passo N, há X` + detalhe da espera; encerradas nas
     últimas 24 h no fim.
   - Cancelar não pede aprovação; a resposta diz o que já tinha sido feito (resumo do servidor) e
     que está no diário, com desfazer onde houver.
   - Pausa do dono é persistida (`pausa=dono`) e não é desfeita pelo `varrer` nem pelo Freio.
2. Espera pelo PC: ferramenta que responde "PC offline" na hora devolve
   `ResultadoFerramenta.pcOffline(...)`; dentro de tarefa o resultado é descartado, a assinatura
   repetida é descontada e a tarefa vai para ESPERANDO_PC sem registrar passo com falha. O
   evento de domínio `PcVoltou` sai quando o PC fala com a VPS após > 90 s sem contato (ou pela
   primeira vez desde a partida). A espera `PC` fica pronta com um `PcVoltou` posterior ao início
   dela. Aviso: "O PC voltou; continuei <objetivo>." Espera máxima `TAREFA_ESPERA_PC_HORAS`
   (padrão 12), contada de `esperaDesde` (persistido): passou → FALHOU com o motivo.
3. Espera por você: aprovada → retoma (já existia). Recusada → CANCELADA, aviso
   "Certo, parei <objetivo>.". Expirada ou substituída sem resposta → PAUSADA com
   `pausa=sem_resposta`, exibida como "esperando sua resposta"; `retomar_tarefa` refaz a pergunta
   com código e prazo novos.
4. `aguardar(minutos 1–60, motivo)`: só no canal `tarefa`, recusada sem `ContextoDaTarefa`.
   Suspensão `HORARIO` com o motivo higienizado em `esperaAlvo`. Não segura thread: o executor é
   liberado e o `@Scheduled varrer` acorda pelo `Clock`.
5. Progresso: o ouvinte da tarefa publica `conversa.ferramenta` (id `tarefa:<id>`, canal
   `tarefa`) quando a HUD está conectada. Telegram: só aviso final, perguntas e "PC voltou".
   A HUD hoje descarta progresso de id não pendente — ajuste da HUD fica fora (outro repositório).

## Fora de escopo
Mudança no jarvis-hud; aprovação inicial do plano (AGUARDANDO_APROVACAO) mantém o comportamento.

## Testes
As quatro ferramentas; referência implícita com uma ativa; ESPERANDO_PC retomando no `PcVoltou`;
espera máxima; `aguardar` sem thread presa (relógio falso + `varrer` manual como agendador, um
trabalhador só); aprovação recusada cancelando; expirada pausando; `PcService` publicando o evento
só depois de > 90 s.
