# ADR-002: Concessões de autonomia gravadas pelo servidor, abaixo do arquivo e acima do padrão

**Status:** Accepted
**Date:** 2026-11-03
**Feature:** concessoes-de-autonomia
**Author:** 10x-Team (Architect + Staff Engineer)

## Context
O ADR-001 deixou a autonomia num arquivo só leitura. O dono quer conceder permissão por voz ("até
sexta pode apagar o que quiser") e parar de ser perguntado sobre o que ele sempre aprova, sem abrir
caminho para o modelo se autoconceder poder (injeção vinda de página, arquivo ou memória) e sem tocar
nos pisos de desligar e reiniciar.

## Decision
- Estado gravável separado: `Concessoes` em `DIARIO_PASTA/concessoes.json`, troca atômica.
- `PoliticaDeAutonomia` consulta concessões entre o arquivo e o padrão; piso continua por cima.
- Concessão temporária exige quatro travas no servidor: mensagem atual autenticada e privada,
  evidência literal da mensagem, confirmação de um classificador curto independente (sem
  ferramentas, sem o contexto do turno), prazo máximo configurável.
- Concessão aprendida nasce de uma pendência própria no `Aprovacoes`, decidida pelo
  `ClassificadorDeResposta` como qualquer outra — o único caminho de confirmação continua um só.
- Revogar não pede aprovação.

## Alternatives Considered
| Alternativa | Prós | Contras | Por que não |
|---|---|---|---|
| Escrever no `config/autonomia.md` | Uma fonte só | Volume é `:ro`; o Jarvis editar a própria política misturaria dono e máquina | O arquivo é do dono; ADR-001 o fez só leitura de propósito |
| Concessão temporária via pendência ("Posso parar de perguntar até sexta?") | Reusa o `Aprovacoes` | Pergunta extra para algo que o dono acabou de dizer | O dono pediu: a frase dele já é a aprovação |
| Confiar só na evidência literal | Barato | "não pode rodar rotina hoje" contém "pode rodar rotina" | O classificador lê o sentido; a evidência ancora na mensagem |
| Concessão acima do arquivo | Concessão sempre vale | O arquivo do dono deixaria de mandar | O arquivo é a palavra final do dono; para travar, ele escreve a linha |

## Consequences
### Positive
- Menos perguntas repetidas; tudo visível em `minhas_permissoes` e no diário.
### Negative
- As 7 linhas de `pergunta_antes` saem do `autonomia.md` versionado; quem tinha cópia própria com elas
  não verá concessões valerem até tirá-las.
- Uma chamada curta de modelo a mais por concessão temporária.
### Risks
- Injeção que faça o modelo chamar `conceder_permissao`: barrada pela evidência literal da mensagem
  do dono + classificador sem contexto de ferramenta. Pior caso: ação de piso `faz_e_avisa` por até
  7 dias, auditada e revogável.

## Dependencies
- ADR-001 (catálogo, pisos, `Aprovacoes.encaminhar`). O escopo `TAREFA` fica para o 7.5.
