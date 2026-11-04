# QA — acompanhamento-de-tarefas

Linha de base (main f89e110): 1310 testes, 1 falha intermitente (HologramaServiceTest), 1 pulado.
Depois: 1326 testes, a mesma 1 falha intermitente (passa isolada), 1 pulado. 16 testes novos.

Novos (TarefasDeFundoTest, PcVoltouTest): listagem com status/passo/tempo e encerradas 24 h;
referência implícita/ambígua/por palavras; cancelar com diário; pausar/retomar; pausa sobrevive ao
restart; offline na hora sem erro e com nova execução; PcVoltou antes da espera não libera; espera
máxima (e após restart); aguardar sem thread presa (1 trabalhador, relógio falso, varrer manual);
aguardar valida e só existe no canal tarefa; recusa → "Certo, parei"; expirada → pausa e repergunta;
progresso na HUD sem mensagem por passo; PcService publica só > 90 s e uma vez entre E2 e antigo.
Alterado: `pcOfflineEsperaSemRepetirChamadaAoModelo` retoma pelo evento. `ContextoDaAplicacaoTest`: 58 ferramentas.
Os testes novos rodaram 3× seguidas sem falha.
