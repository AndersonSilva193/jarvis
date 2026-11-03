# Handoff

Para DevOps/dono: branch local `feat/politica-de-autonomia` (7.3 + concessões), não enviada.
Entregar: merge, `docker compose up -d --build`. Sem volume novo: `concessoes.json` nasce em
`DIARIO_PASTA`. `CONCESSAO_TEMPORARIA_MAX_DIAS` tem padrão 7 (não precisa estar no `.env`).
Atenção: se o `config/autonomia.md` da VPS ainda listar as 7 ações em `pergunta_antes`, nenhuma
concessão vale até tirá-las (o versionado já vem sem). Ferramentas novas: o primeiro pedido reescreve
o cache de `tools`.
