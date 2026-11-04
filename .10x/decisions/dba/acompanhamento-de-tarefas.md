# DBA — acompanhamento-de-tarefas

Sem banco. `DIARIO_PASTA/tarefas.json` ganha dois campos opcionais por tarefa: `pausa`
(`dono`|`sem_resposta`, outro valor é descartado na leitura) e `esperaDesde` (ISO-8601). Arquivo
antigo lê sem eles (vazio/nulo); `versao` continua 1. Mesma troca atômica com `.bak`.
