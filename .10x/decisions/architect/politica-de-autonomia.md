# Arquiteto — politica-de-autonomia

Ver ADR-001 e `specs/2026-11-03-politica-de-autonomia-design.md`.

## Componentes
- `dev.jarvis.autonomia.Nivel` — FAZ_E_AVISA < PERGUNTA < PROIBIDO (ordem = rigor).
- `dev.jarvis.autonomia.AcaoAutonoma` — catálogo fechado: chave, aceita alvo, padrão, piso, frase humana.
- `dev.jarvis.autonomia.PoliticaDeAutonomia` — `@Service`; `nivel(acao, alvo)`, `resumo()` (prompt),
  `explicar()` (ferramenta), recarga por mtime; `PROIBIDOS_FIXOS` é a fonte única do texto fixo.
- `Aprovacoes.encaminhar(...)` → `Encaminhamento(nivel, texto, confirmacao)`.
- `ResultadoFerramenta.de(Encaminhamento)` — conversão nas ferramentas.

## Fronteiras
- `autonomia` não depende de `aprovacao` nem de `ferramentas`; `aprovacao` depende de `autonomia`.
- Construtores antigos de `Aprovacoes`/`SistemaDoJarvis` usam a política só com padrões (testes intactos).

## Falhas
- Arquivo ausente/ilegível → padrões. Linha ruim → ignorada, um WARN por recarga. Nunca lança.
