# EM — acompanhamento-de-tarefas

Ordem (cada uma compila e testa antes da próxima):
1. `PcVoltou` + publicação no `PcService` + callback do E2 + teste.
2. Config `TAREFA_ESPERA_PC_HORAS`.
3. `ResultadoFerramenta.pcOffline` nas ferramentas de PC + tratamento no ciclo.
4. `EsperasDaTarefa`: espera `PC` pelo evento.
5. `TarefasDeFundo`: pausa persistida, pausar/retomar, cancelar com resumo, espera máxima,
   desfechos da aprovação, aviso de volta, progresso HUD; `TarefasEmArquivo` com campos novos.
6. `Aprovacoes.AutorizacaoDaTarefa`: desfecho e `renovar`.
7. `ResolvedorDeTarefa` + quatro ferramentas + `aguardar` + filtro de canal.
8. Testes; CLAUDE.md; `mvn -q -B package`.

Risco principal: mexer no laço do `TarefasDeFundo` sem quebrar os 25 testes existentes.
