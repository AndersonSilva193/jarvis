# Senior Engineer — concessoes-de-autonomia
- Concessoes: `synchronized`; estado em memória carregado na construção; grava a cada mudança.
- Expiração preguiçosa em toda leitura (`ativas()`), registrando `CONCESSAO_EXPIRADA` uma vez.
- Primeiro uso: `marcarUso(id)` grava `usadaEm` e registra `CONCESSAO_USADA` só se estava nulo.
- Oferta: decidida em `Concessoes.registrarAprovacao` (retorna se deve oferecer e zera a sequência).
- Evidência: cortar `pedidoOriginal` em "\n\n(nota do servidor:" antes de comparar.
- Prazo: `ate` aceito como `AAAA-MM-DDTHH:MM[:SS]` local (fuso do Jarvis) ou ISO com offset.
