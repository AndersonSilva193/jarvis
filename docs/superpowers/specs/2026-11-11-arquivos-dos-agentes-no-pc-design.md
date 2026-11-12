# Arquivos dos agentes do Hermes3D na pasta do PC — design

Data: 2026-11-11 · Repositórios: `jarvis` (VPS), `jarvis-agente-pc` (PC), arquivos dos agentes do
Hermes (`~/.hermes/hermes3d-agents.json`). O Hermes3D em si quase não muda.

## Por quê

O agente Ledger do escritório tentou lançar um gasto de R$ 120,00 (Claude Pro) com
`registrar_gasto` e o Jarvis respondeu com erro de gravação em `/data/jarvis/diario/` (arquivo
temporário). Causa: o livro financeiro mora em `DIARIO_PASTA/financas.json` num volume nomeado da
VPS, e o `Dockerfile` não cria `/data/jarvis/diario` (nem `custo`, `proativo`, `/data/google`)
com dono `jarvis` antes do `USER jarvis`; o Docker criou o ponto de montagem como root e o uid 1001
não grava.

Além do erro, o dono quer uma regra simples: **o que os agentes do Hermes3D mexem ou leem fica na
pasta do Jarvis no PC** (`%USERPROFILE%\OneDrive\Documentos\Jarvis`, a `pastaArquivos` do agente
do PC), num lugar só, "para que eles não se batam pra achar". Hoje a instrução do Closer diz
literalmente "pasta do Jarvis (`<a pastaArquivos do agente do PC>`)" — o caminho nunca foi
preenchido.

## A regra

- **Só o Jarvis usa → fica na VPS, como está.** Lembretes (`/data/jarvis/lembretes`, a ferramenta
  de lembretes atual, que dispara no Telegram mesmo com o PC desligado), histórico, memória, custo,
  diário de ações, proativo, Google. Decisão do dono: lembretes não mudam.
- **Os agentes do Hermes3D usam → fica na pasta do PC.** Três arquivos:

| Arquivo (raiz da pasta do PC) | Quem escreve | Quem lê |
|---|---|---|
| `leads.csv` (já existe) | Closer, acrescentando no fim; o escritório escreve o Status | `leads_do_dia` (Jarvis e MCP), escritório |
| `agenda.csv` (novo) | Ledger e Jarvis, por `registrar_compromisso` (novo) | `agenda_de_hoje` (Jarvis e MCP) |
| `financas.csv` (novo; substitui `financas.json` da VPS) | Ledger e Jarvis, por `registrar_lancamento`/`registrar_gasto`/`registrar_ganho`/`remover_lancamento` | `resumo_financeiro` (Jarvis, MCP, card de dinheiro do escritório) |

## Ponto de partida

Partir do branch `origin/feat/servidor-mcp-escritorio` do `jarvis` (commit `14be7bb`, não
mergeado no `main`): ele já faz `leads_do_dia` e `agenda_de_hoje` lerem planilhas da pasta do PC
pelo agente (`PlanilhaDoPc`, `escritorio/AgendaService`, `escritorio/LeadsService`), com
`LEADS_ARQUIVO`, `AGENDA_ARQUIVO`. Trocar o padrão `AGENDA_ARQUIVO=agenda.xlsx` por `agenda.csv`
(decisão minha: CSV aceita acréscimo de linha sem brigar com o Excel aberto e é o mesmo formato
dos leads; o leitor já aceita XLSX ou CSV pelo nome).

## Como os dados andam

**Uma porta de escrita por arquivo de agenda e finanças: o agente do PC.** Ledger e Jarvis não
escrevem `agenda.csv` nem `financas.csv` direto; escrevem pelas ferramentas do Jarvis (conversa e
MCP), e o Jarvis manda ao agente do PC uma ação nova de acréscimo. Assim a validação continua num
lugar só (`FinancasService` já confere valor, data, categoria — "três portas, uma regra") e um só
processo grava o arquivo, sem corrida. Decisão minha; o dono não precisa saber caminho nenhum.

**Ação nova no agente do PC: acrescentar linhas a um CSV** (e, só para a agenda, alterar e
remover a linha de um `Id` — ver "O que o dono faz à mão"). Pelo nome exato do arquivo na raiz de
`pastaArquivos`, nunca caminho; só arquivos de uma lista permitida (`agenda.csv`, `financas.csv`;
configurável); cria o arquivo com o cabeçalho se não existir; separador `;`, UTF-8, mesmo
tratamento anti-fórmula do `criar_arquivo` CSV. **Idempotente por ID**: cada linha tem uma
coluna `Id`; se o ID já está no arquivo, não acrescenta de novo e responde sucesso. Isso permite
reenviar a fila sem duplicar.

**PC desligado → fila de pendentes na VPS.** Escrita com o PC offline é aceita, guardada numa fila
persistente na VPS (arquivo num volume, com `ArquivoAtomico`) e respondida como "anotado, lanço no
livro quando o PC voltar". No evento `PcVoltou` (e a cada reconexão), a fila é enviada em ordem de
chegada; item confirmado sai da fila; falha mantém o item e tenta de novo depois. Com o PC ligado,
grava direto e só confirma depois do PC confirmar. (Decisão do dono: "registra quando o PC
voltar".) Validação acontece **antes** de entrar na fila: entrada inválida é recusada na hora, PC
ligado ou não.

**Leitura do livro.** O livro pode passar do que `PlanilhaDoPc` lê numa consulta (8 idas, ~800
linhas), e o resumo precisa de tudo do período. Decisão minha: a VPS mantém uma **cópia de
leitura** do livro (o `financas.json` atual, em `DIARIO_PASTA`), atualizada a cada lançamento
aceito. A fonte oficial é o arquivo do PC. Como o livro é só para olhar (ver abaixo), tudo o que
entra no `financas.csv` passa antes pela cópia, então ela não precisa reler o PC — exceto quando
estiver vazia (volume perdido): no `PcVoltou`, cópia vazia e `financas.csv` existente, ela importa
o arquivo do PC. O resumo sempre sai da cópia, com o PC ligado ou não.

**Formato do `financas.csv`.** Uma linha por evento, só acréscimo:
`Id;Data;Tipo;Valor;Categoria;Descricao;Origem;RegistradoEm`, com `Tipo` = `gasto`, `ganho` ou
`remocao` (a remoção aponta o `Id` removido em `Descricao` ou numa coluna própria — o builder
escolhe). Valor em reais com vírgula (`120,00`) para abrir certo no Excel em português; por dentro
continua em centavos. Remover nunca apaga linha, como já é hoje ("tira das contas, sem apagar do
arquivo").

**Formato do `agenda.csv`.** As colunas que o `AgendaService` do branch já lê — `Data`, `Início`,
`Fim`, `Compromisso`, `Local` — mais `Id` na frente.

## Ferramentas

- `registrar_compromisso` (nova, conversa e MCP): data, início/fim opcionais, compromisso, local
  opcional. Mesmas regras de entrada limpa e corte de tamanho das outras.
- `registrar_lancamento`, `registrar_gasto`, `registrar_ganho`, `remover_lancamento`,
  `resumo_financeiro`: mesma assinatura e mesmo `structuredContent` de hoje (o card do Hermes3D
  depende disso, `src/lib/office/operation/jarvisFinance.ts`). Muda só onde gravam e de onde leem.
- `leads_do_dia`, `agenda_de_hoje`: como no branch de partida.
- `alterar_compromisso` e `cancelar_compromisso` (novas, conversa e MCP), pelo `Id`: a agenda
  pode ser editada pelos agentes e à mão (decisão do dono).

## O que o dono faz à mão (decisão do dono)

- **`financas.csv`: só olhar.** Correção é sempre pelo Jarvis ou pelo Ledger
  (`remover_lancamento` + novo lançamento). O leitor do livro pode ser estrito; linha que não
  passa na validação é ignorada na reconstrução e o dono é avisado uma vez, sem derrubar o resumo.
- **`agenda.csv`: pode editar à mão e pelos agentes.** Por isso a agenda **não** é só acréscimo:
  é uma tabela comum, uma linha por compromisso. O agente do PC ganha, além de acrescentar,
  alterar e remover a linha de um `Id` nos arquivos da lista permitida, com a mesma troca atômica
  e backup das edições atuais. O leitor da agenda é tolerante: aceita UTF-8 e o ANSI que o Excel
  em português grava, datas `dd/MM/aaaa` e `AAAA-MM-DD`, e linha sem `Id` (escrita à mão) — o
  agente do PC preenche um `Id` nela na próxima escrita, para que ela também possa ser alterada
  ou cancelada pelos agentes.

## Instruções dos agentes do Hermes

Em `~/.hermes/hermes3d-agents.json` (arquivos `AGENTS.md`/`TOOLS.md` de cada agente), pela tela
de edição de agentes do Hermes3D ou direto no JSON:

- Closer: trocar `<a pastaArquivos do agente do PC>` pelo caminho real
  `%USERPROFILE%\OneDrive\Documentos\Jarvis\leads.csv`.
- Ledger: dizer que agenda e finanças ficam na pasta do Jarvis no PC, que ele usa
  `registrar_compromisso` / `alterar_compromisso` / `cancelar_compromisso` / `agenda_de_hoje` e
  `registrar_gasto` / `registrar_ganho` / `remover_lancamento` / `resumo_financeiro`, e que não
  deve tentar abrir esses arquivos por caminho da VPS nem editar o `financas.csv` direto.
- Nada de dado pessoal novo nos repositórios: o caminho real só vai nos arquivos dos agentes, que
  ficam fora dos repositórios.

## VPS: permissões (vale com ou sem o resto)

- `Dockerfile`: criar `/data/jarvis/diario`, `/data/jarvis/custo`, `/data/jarvis/proativo` e
  `/data/google` no mesmo `mkdir`/`chown` das outras pastas.
- Volumes que já existem não herdam o dono novo. Documentar no README/deploy o comando único para
  acertar o dono deles (por exemplo um container temporário com os volumes montados rodando
  `chown -R 1001:1001`). O builder não roda nada na VPS: entrega o comando para o dono.
- A fila de pendentes mora num desses volumes (ex.: `DIARIO_PASTA`), então depende desta correção.

## Migração

Se a VPS tiver um `financas.json` com lançamentos (provavelmente vazio, já que a gravação falhava),
eles entram no `financas.csv` do PC na primeira sincronização, com os IDs originais. O
`financas.json` deixa de ser escrito.

## Objetivos

- O gasto de R$ 120,00 (Claude Pro) entra no livro.
- Ledger e Closer acham seus arquivos sem adivinhar caminho.
- Gasto ou compromisso mandado pelo Telegram com o PC desligado não se perde.
- O card de dinheiro do escritório continua funcionando sem mudança no Hermes3D.

## Anti-objetivos

- Lançamento confirmado que não chega ao livro, ou que chega duas vezes.
- Lembretes deixarem de disparar com o PC desligado.
- O agente do PC aceitar caminho vindo da VPS ou escrever fora de `pastaArquivos`.

## Decisões do builder check (minhas)

- **Partir da ponta do branch** `origin/feat/servidor-mcp-escritorio` (hoje `225f2cc`, que traz
  `14be7bb` e mais um commit nos leads), não do `14be7bb` isolado.
- **Arquivo mudado por fora.** O catálogo do agente do PC marca "alterado" quando tamanho, data ou
  hash mudam, e aí ler e editar falham. Para os arquivos da lista permitida (`leads.csv`,
  `agenda.csv`, `financas.csv`) a mudança por fora é esperada — o Closer acrescenta no
  `leads.csv` direto —, então nesses o agente **recataloga** e segue, em vez de recusar. Fora
  da lista, a regra atual continua.
- **Arquivo travado (aberto no Excel) com o PC ligado** é tratado como PC desligado: vai para a
  fila e a resposta diz "anotado; o arquivo está aberto no PC, lanço quando liberar". A fila
  tenta de novo a cada `PcVoltou` e periodicamente enquanto houver pendente.
- **Agente do PC antigo.** A VPS só usa a ação nova se o agente disser que a conhece (anunciar
  as ações suportadas, como já faz com `X-Pc-Apps`). Agente antigo é um estado próprio: o item
  fica na fila, o dono recebe uma vez "atualize o agente do PC", e não há repetição infinita.
  Ordem de entrega: agente do PC novo primeiro, VPS depois.

## Fica com o builder

Nomes de classes e da ação nova, formato exato da fila, como reconstruir a cópia de leitura,
coluna da remoção, testes. Merge, versão nova do agente do PC e deploy na VPS são passos que o
dono confirma.
