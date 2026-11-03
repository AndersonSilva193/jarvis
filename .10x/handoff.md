# Handoff

Para DevOps/dono: branch local `feat/politica-de-autonomia`, não enviada. Para entregar: merge,
`docker compose up -d --build`. O `config/autonomia.md` chega pelo volume `./config:/app/config:ro` já
existente — sem variável nova. Na partida, procurar no log "Autonomia carregada de /app/config/autonomia.md".
As descrições de 4 ferramentas mudaram: o primeiro pedido depois do deploy escreve o cache de novo.
