# EM — concessoes-de-autonomia
1. Config `CONCESSAO_TEMPORARIA_MAX_DIAS` (3 lugares) + `autonomia.md` sem as 7 linhas.
2. `Concessoes` + modelos + persistência + expiração; `TipoDeAcao` novos.
3. `PoliticaDeAutonomia` consultando concessões (ordem e piso).
4. `ClassificadorDeConcessao` + `ConcederPermissaoFerramenta`.
5. `Aprovacoes`: contagem, oferta, pendência `conceder_permanente`, recusa; `Assistente` chama
   `recusarPendente` no NÃO.
6. `RevogarPermissaoFerramenta`, `minhas_permissoes` com concessões.
7. Testes; CLAUDE.md; `mvn -q -B package`.
Risco: construtores do `Aprovacoes`/política usados em muitos testes — só acrescentar.
