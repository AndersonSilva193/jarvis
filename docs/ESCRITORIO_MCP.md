# O escritório fala com o Jarvis: servidor MCP

Os agentes do escritório (Hermes3D com o Hermes Agent como cérebro, rodando no PC) usam o Jarvis
como um servidor MCP. A conexão sai do PC para a VPS, como a do agente do PC: nenhuma porta aberta em
casa, nenhum Tailscale.

```
Hermes Agent (PC) ──HTTPS + MCP_TOKEN──► nginx ──► /api/mcp ──► ServidorMcp ──► ferramentas MCP
                                                                         │
                                         Telegram ◄── AvisosProativos ◄──┤
                                         financas.json ◄── FinancasService ◄─┘
```

## O que os agentes podem fazer

| Ferramenta | O que faz | Escreve? |
|---|---|---|
| `avisar_aprovacoes` | Avisa no Telegram que há itens esperando aprovação no quadro do escritório. O agente escolhe o tipo (`abordagens`, `propostas`, `conteudos`, `tarefas`) e a quantidade; a frase é do Jarvis. | manda mensagem ao dono |
| `registrar_gasto` | Lança um gasto no livro financeiro do Jarvis. | sim |
| `registrar_ganho` | Lança um ganho no mesmo livro. | sim |
| `resumo_financeiro` | Ganhos, gastos, saldo, categorias e últimos lançamentos de um período. Também em `structuredContent`, em centavos, para o painel de operação do Hermes3D. | não |
| `agenda_de_hoje` | Compromissos do Google Agenda do dono para hoje ou amanhã. | não |
| `leads_do_dia` | Leads de hoje da planilha de prospecção (ou os últimos, se não houver de hoje). | não |

O que fica de fora **de propósito**: controle do PC, arquivos, memória, lembretes, rotinas e qualquer
ação que no Jarvis passa pelo "Posso?". Os agentes leem a internet; uma instrução escondida numa
página não pode chegar a nada disso. O catálogo MCP (`dev.jarvis.mcp.FerramentaMcp`) é separado das
ferramentas da conversa, e nenhuma ferramenta da conversa é exposta aqui.

As travas do aviso:

- a frase sai do catálogo do `FalasService` (`escritorio_aprovacoes`): o agente não escreve texto
  que chegue ao dono;
- passa pelo `AvisosProativos`, então respeita o silêncio (23h–7h, acumula para depois) e não repete
  o mesmo número;
- um aviso por tipo a cada 15 minutos, contra agente em laço.

Os lançamentos do escritório entram no diário do Jarvis com a origem `escritorio`, e o dono corrige
pela conversa ("remove o lançamento a1b2c3d4"): o MCP não remove nada.

## Ligar

1. Na VPS, gere o token e ponha no `.env`:

   ```bash
   openssl rand -hex 32     # copie o resultado
   # .env
   MCP_TOKEN=<o token>
   ```

   Depois `docker compose up -d --build`. O nginx já repassa `/api/` inteiro, então não muda nada lá.

2. No PC, ponha o mesmo token em `~/.hermes/.env`:

   ```bash
   JARVIS_MCP_TOKEN=<o token>
   ```

3. E o servidor em `~/.hermes/config.yaml`:

   ```yaml
   mcp_servers:
     jarvis:
       url: "https://jarvis.barberagendaonline.store/api/mcp"
       headers:
         Authorization: "Bearer ${JARVIS_MCP_TOKEN}"
   ```

   Numa conversa aberta, `/reload-mcp` recarrega sem reiniciar. As ferramentas aparecem para os
   agentes com o prefixo do servidor (por exemplo `mcp_jarvis_avisar_aprovacoes`).

4. Nas instruções do agente de abordagem, algo como: "Depois de deixar abordagens em revisão
   (`kanban_request_review`), chame `avisar_aprovacoes` com o tipo `abordagens` e o total que está
   esperando." No do financeiro: "Use `registrar_gasto`/`registrar_ganho` só com valores que o dono
   informou ou confirmou, e `resumo_financeiro` para relatórios."

### Agenda e leads

As duas leem planilhas na **pasta do Jarvis no PC** (a `pastaArquivos` do `config.properties` do
agente), não o Google. O PC precisa estar ligado; desligado, as duas avisam. No `.env` da VPS, só se
quiser outros nomes:

```bash
LEADS_ARQUIVO=leads.csv      # XLSX ou CSV, nome exato, na raiz da pasta
LEADS_ABA=                   # no XLSX; vazio = a primeira
AGENDA_ARQUIVO=agenda.xlsx
AGENDA_ABA=
```

**Leads** — quem escreve é o agente de prospecção do escritório, acrescentando uma linha por lead no
fim. CSV é o mais fácil para ele; o Excel em português abre com `;` e o Jarvis entende os dois. As
colunas são achadas pelo cabeçalho (nome/empresa, segmento, telefone/WhatsApp, cidade/bairro, rede
social/Instagram, status, data), e "do dia" são as linhas com a data de hoje (`dd/MM/aaaa` ou
`AAAA-MM-DD`):

```text
Data;Empresa;Segmento;WhatsApp;Bairro;Instagram;Status
11/11/2026;Academia Força;academia;+55 41 99999-0000;Batel;https://instagram.com/academiaforca;
```

O **Status** é escrito pelo escritório (Hermes3D, com `LEADS_CSV_PATH` apontando para este arquivo):
`abordado em 11/11/2026` quando o dono aprova a abordagem, `descartado em 11/11/2026` quando
descarta. O `leads_do_dia` abre com a contagem (`Status: abordado 6, descartado 2, sem status 7.`) e
mostra a rede como link curto (`instagram.com/academiaforca`); link longo demais para caber sai de
fora, porque cortado ele não abriria.

**Agenda** — uma linha por compromisso, com as colunas **Data**, **Início**, **Fim**,
**Compromisso** e **Local** (só Data é obrigatória; sem hora é o dia inteiro). A hora pode estar na
própria coluna de data (`11/11/2026 14:00`). Numa célula de hora do Excel, a hora só chega com o
agente do PC atualizado (o campo `dataHora`); com o agente antigo, escreva a hora como texto
(`14:00`).

## Conferir

```bash
curl -s https://jarvis.barberagendaonline.store/api/mcp \
  -H "Authorization: Bearer $MCP_TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Sem token, com o `HUD_TOKEN` ou com o `PC_TOKEN` a resposta é 401. Requisição com cabeçalho
`Origin` (navegador) é 403.

## O protocolo, no tamanho que o Jarvis usa

Transporte *Streamable HTTP* no modo mais estreito: um `POST` por mensagem JSON-RPC, resposta em
`application/json`, sem stream e sem sessão. Métodos: `initialize`, `ping`, `tools/list` e
`tools/call`. Notificação recebe 202 sem corpo; `GET` e `DELETE` recebem 405. Versões aceitas:
`2025-11-25`, `2025-06-18` e `2025-03-26` (pedido de outra recebe a mais nova).

## O que o MCP não faz

O caminho é de mão única: quem inicia é sempre o agente. O Jarvis não consegue perguntar ao
escritório "quantas pendências você tem?" — os agentes contam isso a ele pelo `avisar_aprovacoes`.
Se um dia o Jarvis precisar consultar o kanban do Hermes direto, aí entra uma rede privada entre VPS
e PC (Tailscale), que o Hermes3D já documenta.
