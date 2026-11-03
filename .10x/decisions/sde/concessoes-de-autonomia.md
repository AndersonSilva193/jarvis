# SDE — concessoes-de-autonomia

## Construído
- `autonomia/Concessao` (record + `Escopo`, `Origem`), `autonomia/Concessoes` (JSON em
  `DIARIO_PASTA/concessoes.json`, troca atômica, `.bak`, expiração preguiçosa + tique de 10 min,
  contagem de aprovações 30 d, bloqueio da oferta 30 d), `autonomia/ClassificadorDeConcessao`.
- `PoliticaDeAutonomia.decidir/arquivoDecide/concessoes()`; `resumo()` e `explicar()` com concessões.
- `Aprovacoes`: `Pendente` com acao/alvo/aoRecusar; `encaminhar` marca primeiro uso; `rodar` conta
  e oferece; `recusarPendente`; `TIPO_CONCEDER_PERMANENTE`; "cancelar" conta como NÃO.
- `Assistente`: NÃO do classificador → `recusarPendente`.
- Ferramentas `conceder_permissao`, `revogar_permissao`; `minhas_permissoes` com descrição nova.
- `TipoDeAcao`: CONCESSAO_CRIADA/USADA/EXPIRADA/REVOGADA (não autônomas).
- Config `CONCESSAO_TEMPORARIA_MAX_DIAS` em `JarvisProperties.Diario`, `application.yml`, `.env.example`.
- `config/autonomia.md` sem as 7 linhas de `pergunta_antes`.

## Desvios
- Config mora no bloco `Diario` (e não num bloco `Autonomia` novo): evita mais um construtor de
  compatibilidade de 23 argumentos; a pasta é a mesma.
- `@ConstructorBinding` no `Diario`: o segundo construtor deixava o bloco nulo no binding.
- "1 concessão por pedido" é memória do processo (não disco): repetição só acontece no mesmo turno.

## Dívida / achados
- **Pré-existente:** `JarvisProperties.Historico` tem dois construtores sem `@ConstructorBinding` e
  chega nulo do yml; `HISTORICO_*` do `.env` são ignorados (valem os fallbacks). Não corrigido aqui.
- Descrições de 1 ferramenta mudaram e 2 entraram: o primeiro pedido depois do deploy reescreve o
  cache de `tools`.
