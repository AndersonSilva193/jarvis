# QA — concessoes-de-autonomia

Testes novos (34): `ConcessoesTest` (11), `PoliticaComConcessoesTest` (5),
`ConcederPermissaoFerramentaTest` (9, inclui revogar), `AprovacoesConcessoesTest` (7), +1 em
`PoliticaDeAutonomiaTest` (arquivo versionado não trava nada), +1 em `ContextoDaAplicacaoTest`
(binding do yml).

Cobertura pedida: evidência fora da mensagem; classificador em dúvida/não/sem prazo/sem JSON;
prazo acima do máximo cortado e menor prazo vence; expiração com relógio fixo (um segundo antes vale,
no instante não); piso CRITICO (conceder, JSON editado, oferta); oferta na 3ª e não na 2ª; NÃO
bloqueando 30 dias; revogação (por alvo, todas, volta a perguntar).

Extra: nota do servidor do AJUSTE não serve de evidência; grupo/tarefa/sem id recusados; sem
duplicar no mesmo pedido; primeiro uso uma vez; arquivo trava oferta; "cancelar" zera sequência.

Achado durante QA: o `Diario` com dois construtores ficava nulo no binding (corrigido); o mesmo bug
já existe no `Historico` (aberto).

Build: `mvn -q -B package` — 1279 testes, 1 falha (HologramaServiceTest, intermitente, pré-existente),
1 pulado.
