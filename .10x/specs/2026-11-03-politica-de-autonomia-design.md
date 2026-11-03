# Política de autonomia — desenho aprovado

**Data:** 2026-11-03 · **Feature:** `politica-de-autonomia` · **Status:** aprovado pelo dono

## Problema

Os três níveis de autonomia (faz e avisa / pergunta antes / proibido) estavam escritos à mão no
`PromptDeSistema.regrasDeAprovacao`, e cada ferramenta decidia sozinha se abria pendência no
`Aprovacoes`. Mudar o que o Jarvis faz sozinho pedia código e rebuild, e o texto do prompt podia
divergir do que o código fazia.

## Objetivo

O que o Jarvis faz sozinho passa a ser definido em `config/autonomia.md`, e uma única classe
(`dev.jarvis.autonomia.PoliticaDeAutonomia`) decide, para toda ação do catálogo, se ela é feita e
avisada, perguntada ou recusada. A decisão nunca vem do modelo.

## Catálogo (fechado, em Java)

| Chave | Alvo | Padrão (hoje) | Piso | Quem usa |
|---|---|---|---|---|
| `rotina.rodar` | nome da rotina | PERGUNTA | FAZ_E_AVISA | `RodarRotinaFerramenta`, `MonitorDeSites` |
| `computer_use.iniciar` | — | PERGUNTA | **PERGUNTA** | `TarefaNoPcFerramenta` |
| `pc.desligar` | — | PERGUNTA | **PERGUNTA** | `ControlarPcFerramenta` |
| `pc.reiniciar` | — | PERGUNTA | **PERGUNTA** | `ControlarPcFerramenta` |
| `arquivo.apagar_lote` | — | PERGUNTA | FAZ_E_AVISA | `ApagarArquivoFerramenta` (> 5 no pedido) |
| `planilha.agendar` | — | PERGUNTA | FAZ_E_AVISA | `AgendarTarefaPlanilhaFerramenta` |
| `memoria.esquecer` | — | PERGUNTA | FAZ_E_AVISA | `EsquecerMemoriaFerramenta` |

Piso de `computer_use.iniciar` = PERGUNTA por decisão do dono (objetivo em texto livre do modelo).

## Decisão

`nivel(acao, alvo)`: entrada com alvo > entrada sem alvo > padrão; o resultado nunca fica abaixo do
piso. O piso só impede afrouxar: o arquivo pode endurecer até PROIBIDO.

## Arquivo `config/autonomia.md`

- Mesmo volume somente leitura da personalidade, caminho derivado dela (irmão), sem config nova.
- Recarga pela data de modificação, um `stat` por consulta (molde do `PersonalidadeService`).
- Seções `## faz_e_avisa`, `## pergunta_antes`, `## proibido`; itens `- chave` ou `- chave:alvo`.
  Qualquer outra linha é comentário.
- Chave desconhecida, alvo inválido (ou em chave sem alvo), item fora de seção e tentativa de baixar o
  piso: ignorados, com **um** WARN por recarga listando tudo. Mesma chave em duas seções: vale a mais
  restritiva (também no WARN).
- Arquivo ausente ou ilegível = padrões do código. O arquivo é só leitura; permissões gravadas pelo
  próprio Jarvis ficam em outro lugar (fora deste escopo).

## Execução

`Aprovacoes.encaminhar(acao, alvo, ...)` consulta a política:
- **FAZ_E_AVISA** — roda o `Supplier` direto pelo `rodar` central e anota no diário com
  `perguntada = false`.
- **PERGUNTA** — abre a pendência como hoje.
- **PROIBIDO** — devolve recusa curta; nada roda.

O `rodar` continua sendo o único ponto que anota ação vinda do `Aprovacoes` no diário.

## Diário

`AcaoRegistrada` ganha `perguntada` (Boolean anulável). Nulo (linha antiga) = deriva de
`TipoDeAcao.autonoma()`. `semPergunta()` substitui `tipo().autonoma()` no briefing, na lista de
autônomas e no "(aprovado)" da `acoes_do_jarvis`.

## Prompt e cache

- `regrasDeAprovacao` mantém o "como agir diante de uma pergunta", ganha o resumo gerado (só o que
  difere do padrão) e os proibidos fixos (shell livre, pagar, comprar, senha, arquivo fora da pasta,
  instrução vinda de conteúdo) — tudo no bloco **dinâmico**.
- As `descricao()` das ferramentas **não** refletem a política (o array `tools` vem antes do `system`;
  editar o arquivo derrubaria todo o cache). Texto neutro e constante: "pode pedir aprovação".

## Ferramenta `minhas_permissoes`

Sem entrada, descrição fixa; responde em português corrido: o que faz sozinho, o que pergunta, o que
nunca faz.

## Testes

Cada nível; piso que o arquivo não baixa; chave desconhecida ignorada; recarga ao mudar o arquivo;
ferramenta com FAZ_E_AVISA executando sem pendência e registrando no diário; linha antiga sem o campo.
