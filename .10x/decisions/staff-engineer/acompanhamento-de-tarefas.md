# Staff Engineer — acompanhamento-de-tarefas

- Ferramentas seguem o padrão de `AguardarTarefaFerramenta`/`IniciarTarefaDeFundoFerramenta`:
  `ObjectProvider<TarefasDeFundo>` para evitar ciclo de beans; descrição e esquema sem estado
  volátil (cache de `tools`); filtro por canal no `Ferramentas.definicoes`.
- Config nova nos três lugares: `application.yml` (`jarvis.diario.tarefas.espera-pc-horas`),
  `JarvisProperties.Tarefas`, `.env.example`; e a linha no CLAUDE.md.
- Evento de domínio pelo `ApplicationEventPublisher` (como `TelaEvento`, `ModoDeIaEvento`).
- Texto do modelo (`motivo`, `tarefa`) é higienizado e limitado; nunca vira identidade.
- Identidade do dono vem de `OuvinteProgresso.usuarioAutenticado()`, nunca da entrada JSON.
