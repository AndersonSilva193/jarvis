# Architect — concessoes-de-autonomia

Ver ADR-002 e specs/2026-11-03-concessoes-de-autonomia-design.md.

Componentes:
- `autonomia.Concessoes` (estado + JSON), `Concessao`, `EscopoDaConcessao`, `OrigemDaConcessao`.
- `autonomia.ClassificadorDeConcessao` (apoio, `Origem.CLASSIFICADOR`, 5 s, sem ferramentas).
- `PoliticaDeAutonomia.decidir(acao, alvo)` → `Decisao(nivel, concessao)`; `nivel()` delega.
- `Aprovacoes`: `Pendente` ganha `acao`, `alvo`, `aoRecusar`; `rodar` conta SIM e acrescenta a oferta;
  `recusarPendente` (NÃO explícito) roda `aoRecusar`; `encaminhar` marca primeiro uso.
- Ferramentas `conceder_permissao`, `revogar_permissao`; `minhas_permissoes` lista concessões.

Falhas: JSON ilegível → começa vazio a partir do `.bak`; se os dois falham, sem concessões (seguro:
volta a perguntar). Falha ao gravar uma concessão → não concede (a resposta diz que não gravou).
