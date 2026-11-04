# Security — acompanhamento-de-tarefas

- Identidade: sempre `usuarioAutenticado()` do pedido; `listar/estado/pausar/retomar/cancelar` filtram
  por usuário. As ferramentas recusam dentro de tarefa; `aguardar` recusa sem `ContextoDaTarefa`.
- Entrada do modelo: `tarefa` ≤ 200, só casada contra objetivos (nunca vira id sem existir);
  `motivo` sem controle, ≤ 120, vai só à listagem do dono; `minutos` 1–60 validado no servidor.
- Injeção (página/arquivo lido na conversa) poderia levar o modelo a cancelar ou retomar uma tarefa.
  Impacto aceito: cancelar não executa nada; retomar não pula política, aprovação nem Freio.
- `pcOffline` só onde nada foi iniciado — repetir a ferramenta não duplica efeito.
- Evento `PcVoltou` publicado fora da trava; ouvinte só grava um volátil (sem I/O no thread do agente).
