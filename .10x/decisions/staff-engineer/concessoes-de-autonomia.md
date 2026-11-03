# Staff Engineer — concessoes-de-autonomia
- Construtores antigos ficam; o novo campo entra anulável (padrão do Freio/diário).
- Política sem concessões (`soPadroes()`, testes) se comporta como antes.
- Persistência só pelo `ArquivoAtomico`; JSON com o `JsonMapper` injetado; `Clock` injetado.
- Textos ao dono montados em Java a partir do catálogo (`AcaoAutonoma.oQue`), nunca do modelo.
- Descrições das ferramentas novas constantes (prefixo de cache de `tools`).
- Configuração nova nos três lugares: `JarvisProperties`, `application.yml`, `.env.example`.
