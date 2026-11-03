# QA — politica-de-autonomia

`mvn -q -B package` (Docker, maven:3.9-eclipse-temurin-21): 1246 testes, 1 falha, 1 skip.
A falha é `HologramaServiceTest.aRetomadaReenviaOQueAHudPerdeuEContinua`, idêntica na linha de base
(1226 testes, mesma falha) — não relacionada.

## Novos (20)
- `PoliticaDeAutonomiaTest` (11): padrões sem arquivo; cada nível; piso não baixa (3 ações); piso deixa
  endurecer; chave desconhecida / alvo inválido / sem alvo / fora de seção / seção desconhecida;
  alvo vence geral; duas seções = mais rigorosa; recarga ao mudar e ao apagar; resumo só diferenças;
  `explicar` sem chaves; arquivo do repositório sem linhas ignoradas e igual aos padrões.
- `AprovacoesPoliticaTest` (6): FAZ_E_AVISA sem pendência + diário `perguntada=false`; PERGUNTA +
  `perguntada=true`; PROIBIDO não roda nem anota; piso no encaminhar; exige usuário; persistência do
  campo e linha antiga sem campo.
- `RodarRotinaFerramentaTest` (+2): FAZ_E_AVISA executa e registra; PROIBIDO não chega ao PC.
- `PromptDeSistemaTest` (reescrito + 1): sem lista à mão; resumo só no dinâmico; bloco fixo idêntico.
- `ContextoDaAplicacaoTest`: contagem de ferramentas 48 → 49.

## Não coberto por teste automático
- `MonitorDeSites` com FAZ_E_AVISA (rotina roda sozinha na queda) — caminho simples, sem teste novo.
