# Concessões de autonomia — design

**Data:** 2026-11-03 · **Feature:** concessoes-de-autonomia · **Base:** politica-de-autonomia (7.3)

Objetivo: perguntar menos sem abrir mão do controle. O dono concede, o Jarvis aprende com aprovações
repetidas, e tudo é revogável e auditado.

## Ordem de decisão
`piso do código > config/autonomia.md > Concessoes válidas > padrão do código`.
- Piso PERGUNTA (`pc.desligar`, `pc.reiniciar`, `computer_use.iniciar`): `conceder_permissao` recusa,
  a oferta aprendida nunca aparece, e a política corta no piso mesmo que o JSON seja editado à mão.
- Uma linha explícita no `autonomia.md` vence a concessão. Por isso as 7 linhas de `pergunta_antes`
  (iguais ao padrão) saem do arquivo versionado. Quem quiser travar uma ação contra concessão
  escreve a linha em `pergunta_antes` ou `proibido`.

## Concessoes (`dev.jarvis.autonomia.Concessoes`)
Arquivo `DIARIO_PASTA/concessoes.json`, troca atômica com `.bak` (`ArquivoAtomico`), sem volume novo.
- `Concessao`: `id`, `chave`, `alvo` opcional, `escopo` (`ATE` + `ate` | `PERMANENTE` | `TAREFA` +
  `idTarefa`), `origem` (`temporaria` | `aprendida` | `plano`), `evidencia`, `criadaEm`, `usadaEm`.
- `TAREFA` é guardada e lida, mas a política só a considera a partir do 7.5 (não vale em conversa).
- Na consulta, o que venceu sai do arquivo e vai para o diário como expirado (pelo relógio injetado).
- A concessão com alvo vence a sem alvo; entre várias válidas, vale qualquer uma (todas liberam).
- Também guarda as aprovações por (chave, alvo) dos últimos 30 dias e o bloqueio da oferta.

## Temporária: `conceder_permissao(chave, alvo?, ate, evidencia)`
O servidor só grava se:
1. canal `telegram`/`hud`, usuário autenticado (> 0; no Telegram só em chat privado) e `idPedido`
   presente — a mensagem atual, nunca uma tarefa, gatilho ou o histórico;
2. `evidencia` é trecho literal de `pedidoOriginal` pela `Texto.normalizar` (a nota do servidor do
   AJUSTE é cortada antes de comparar);
3. a chave existe no catálogo, aceita alvo quando há alvo, e o piso permite `faz_e_avisa`;
4. o `ClassificadorDeConcessao` (apoio, sem ferramentas, system mínimo, JSON
   `{"concede":"sim|nao|duvida","ate":"AAAA-MM-DDTHH:MM"}`) responde `sim` com `ate` legível;
   qualquer outra coisa — rede, prazo, JSON torto, sem `ate` — não grava;
5. `ate` final = menor entre o do modelo, o do classificador e `agora + CONCESSAO_TEMPORARIA_MAX_DIAS`;
   no passado → recusa.
Uma concessão por (pedido, chave, alvo): repetir a chamada no mesmo turno não duplica.
Resposta montada em Java: "Combinado: até <quando>, <ação> sem perguntar. Diga 'volta a perguntar'
para cancelar." Sem pergunta extra.

## Aprendida
- Em `Aprovacoes.rodar` com `perguntada=true` e pendência aberta por `encaminhar` (tem acao/alvo):
  conta SIM. NÃO explícito (classificador ou "cancelar") zera a sequência.
- Na 3ª SIM seguida dentro de 30 dias, chave com piso `faz_e_avisa`, sem bloqueio, sem concessão
  em vigor: depois de executar, a resposta ganha "É a terceira vez que você aprova <ação>. Quer que
  eu pare de perguntar?" e nasce uma pendência COMUM `conceder_permanente`. Oferecer zera a sequência.
- SIM grava PERMANENTE (origem `aprendida`, evidência = a resposta do dono). NÃO bloqueia a oferta
  por 30 dias. Ignorada (outro assunto, prazo): volta depois de mais 3 aprovações.

## Revogar e consultar
- `revogar_permissao(chave?, alvo?)`: sem chave revoga todas; sem aprovação (reduzir é seguro).
- `minhas_permissoes` acrescenta "Permissões que você me deu: …, até <quando> / sem prazo".
- Resumo do prompt (bloco dinâmico) passa a refletir as concessões, pois sai da política.

## Diário
`TipoDeAcao` ganha `CONCESSAO_CRIADA`, `CONCESSAO_USADA` (primeiro uso), `CONCESSAO_EXPIRADA` e
`CONCESSAO_REVOGADA`, todos não autônomos (a ação liberada já entra como "sem pergunta").

## Configuração
`CONCESSAO_TEMPORARIA_MAX_DIAS` (padrão 7): `JarvisProperties` (bloco `Autonomia`),
`application.yml` e `.env.example`.

## Testes
Evidência fora da mensagem recusada; classificador em dúvida não grava; prazo acima do máximo
cortado; expiração com relógio fixo; piso CRITICO; oferta na 3ª e não na 2ª; NÃO bloqueando 30 dias;
revogação.
