# Segurança — politica-de-autonomia

- **Decisão fora do modelo:** o nível vem de `PoliticaDeAutonomia` com chave fixa em Java; o modelo
  só escolhe o alvo entre rotinas anunciadas (enum no schema). Liberar uma rotina é decisão do dono.
- **Pisos em código:** desligar, reiniciar e computer use nunca rodam sem pergunta (dupla tranca:
  descarte na leitura + `max` em `nivel`). Testado.
- **Arquivo hostil/corrompido:** só lido, nunca executado; linhas inválidas ignoradas; exceções viram
  padrões (que perguntam). Volume `:ro`: o Jarvis não consegue se dar permissão.
- **Falha aberta?** Arquivo apagado → padrões (PERGUNTA), nunca FAZ_E_AVISA. Proibições do arquivo se
  perdem nesse caso — aceito, os padrões são o comportamento anterior.
- **Diário:** FAZ_E_AVISA passa pelo mesmo `rodar`; não há ação liberada sem rastro.
- **Monitor de sites:** rotina com FAZ_E_AVISA roda sozinha numa queda — continua atrás do `Freio`
  (`podeAgirSozinho("gatilho")`). Avisar o dono antes de liberar uma rotina usada no `ACOES_DE_SITES`.
