# ADR-001: Política de autonomia em arquivo, decidida numa classe só

**Status:** Accepted
**Date:** 2026-11-03
**Feature:** politica-de-autonomia
**Author:** 10x-Team (Architect + Staff Engineer)

## Context
Os níveis de autonomia viviam em dois lugares que podiam divergir: o texto à mão do
`PromptDeSistema` e um `aprovacoes.pedir(...)` espalhado em seis pontos. O dono quer ajustar o que o
Jarvis faz sozinho sem rebuild, mas sem abrir brecha para desligar a máquina ou mexer na tela sem
pergunta. Restrições: o `tools` e o bloco fixo do `system` são prefixo de cache; a aprovação só pode
ter um caminho; o diário só é anotado num ponto para ações vindas do `Aprovacoes`.

## Decision
- Catálogo fechado `AcaoAutonoma` (enum) com padrão e piso por chave.
- `PoliticaDeAutonomia` lê `config/autonomia.md` (irmão da personalidade, volume `:ro`), recarrega por
  data de modificação e aplica: alvo > chave > padrão, nunca abaixo do piso.
- `Aprovacoes.encaminhar` é a única porta das ferramentas: executa direto (FAZ_E_AVISA), pede
  (PERGUNTA) ou recusa (PROIBIDO), reutilizando o `rodar` central para anotar no diário.
- `AcaoRegistrada.perguntada` por linha, porque o mesmo tipo (`ROTINA`) pode agora ser feito com ou
  sem pergunta.
- O resumo da política entra só no bloco dinâmico do `system`; descrições de ferramenta continuam
  constantes.

## Alternatives Considered
| Alternativa | Prós | Contras | Por que não |
|---|---|---|---|
| Configuração no `.env` (`AUTONOMIA_FAZ_E_AVISA=...`) | Já tem molde no `JarvisProperties` | Pede restart; lista em variável é ilegível e sem comentário | O dono pediu arquivo editável e comentado, recarregado sem restart |
| Cada ferramenta consulta a política e decide o caminho | Mudança local | Seis cópias do `switch`; diário de FAZ_E_AVISA espalhado | Contradiz "uma única classe decide" e "um ponto anota" |
| Marcar "(aprovado)" por tipo, criando `ROTINA_SEM_PERGUNTA` etc. | Sem campo novo | Dobra o enum; briefing conta duas linhas para a mesma ação | Campo por linha é mais simples e honesto |
| Refletir a política nas `descricao()` das ferramentas | O modelo saberia por ferramenta | Editar o arquivo invalidaria o cache de `tools` + `system` + `messages` | Regra de cache do CLAUDE.md |

## Consequences

### Positive
- Ajustar autonomia = editar um `.md` na VPS, sem rebuild nem restart.
- Pisos em código: nenhum arquivo libera desligar, reiniciar ou computer use sem pergunta.
- O prompt deixa de poder mentir sobre o que o código faz.

### Negative
- Um `stat` a mais por consulta à política (desprezível perto de uma chamada ao modelo).
- `AcaoRegistrada` ganha um componente; quem usa o construtor canônico (3 pontos) muda.

### Risks
- Arquivo apagado volta aos padrões (que perguntam): risco aceito, os padrões são o comportamento
  de hoje. Mitigação: WARN na transição.
- FAZ_E_AVISA em `rotina.rodar` faz o monitor de sites rodar a rotina sozinho; continua atrás do
  `Freio`.

## Dependencies
- `PersonalidadeService` (molde de recarga), `Aprovacoes.rodar`, `DiarioDeAcoes`.
- Permissões gravadas pelo próprio Jarvis (futuro) terão de passar pela mesma `PoliticaDeAutonomia`
  e respeitar os mesmos pisos.
