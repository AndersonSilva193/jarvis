# Handoff

Para o SDE: implementar ADR-001 conforme `specs/2026-11-03-politica-de-autonomia-design.md` e
`decisions/architect/politica-de-autonomia.md`. Pontos sensíveis: não mexer nas `descricao()` além do
texto neutro (cache), manter o `rodar` do `Aprovacoes` como único ponto que anota no diário, e não
quebrar os construtores usados por testes.
