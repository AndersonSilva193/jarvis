# Jarvis comandando a empresa no Hermes3D

Data: 2026-11-10. Repositórios afetados: `jarvis`, `jarvis-agente-pc`, `Hermes3D`.

## Por que existe

O dono usa o Jarvis como assistente pessoal e tem uma "empresa" de agentes no Hermes3D. Hoje, para
saber o que os agentes estão fazendo ou passar trabalho a eles, precisa abrir o escritório e operar
lá. O objetivo é fazer tudo isso conversando com o Jarvis, pelo Telegram ou pela HUD.

Pedido real que guia o desenho:

> "Jarvis, pede pro time de marketing montar uma campanha de dois conteúdos por semana para as
> próximas 2 semanas para mim gravar e postar no Instagram"

## Objetivos

1. **Abrir a empresa.** O Jarvis abre o escritório 3D no navegador do PC.
2. **Saber o que está acontecendo.** "Como está a empresa?" responde com tarefas em andamento e
   tarefas concluídas, com o agente de cada uma.
3. **Passar tarefas.** O Jarvis repassa o pedido ao agente principal do Hermes3D, que distribui o
   trabalho e cria agentes se precisar.
4. **Receber o resultado.** Quando a tarefa termina, o Jarvis avisa e entrega o que foi produzido.
5. **Tudo liga junto.** O Hermes3D sobe junto com o agente do PC.
6. **Agentes conversam entre si.** Qualquer agente consegue pedir algo a outro e receber a resposta.
   Hoje nenhum consegue, nem o agente principal (ver "Comunicação entre agentes").

## Fora do escopo

- Servidor MCP. O dono escolheu a conversa direta entre o Hermes3D e o `jarvis-agente-pc`.
- Comandar a empresa pelo Claude Code ou por outros clientes.
- Tela nova na HUD. A resposta usa texto e voz, como o Jarvis já faz.
- Conceito de times ou departamentos. "Time de marketing" é só linguagem do pedido; quem decide
  quem trabalha é o agente principal.
- Publicar no Instagram. O dono grava e posta.
- Acesso ao Hermes3D pela internet.

## O que seria um fracasso, mesmo funcionando

- O Jarvis dizer "concluído" sem ter entregue nada.
- O Jarvis dizer que algo está parado quando já terminou, por o escritório estar fechado.
- Passar tarefa aos agentes sem o dono confirmar.
- Abrir qualquer porta do PC para fora.
- Um agente ser desativado ou apagado sem o dono pedir.
- Um agente dizer que outro não existe, ou que repassou algo que não repassou.
- Agentes presos num vaivém de pedidos entre si, gastando sem entregar.

## Como funciona

```
Dono ──► Jarvis (VPS) ──fila existente──► jarvis-agente-pc (PC)
                                                 │ localhost
                                                 ▼
                                         Hermes3D (adaptador 18789)
                                                 │
                                                 ▼
                                         Agente principal "hermes"
                                         distribui, cria e reaproveita agentes
```

O agente do PC continua no meio porque é a única forma de a VPS alcançar o PC: ele busca comandos
por long polling e nada no PC escuta conexões de fora. Toda a conversa com o Hermes3D acontece em
`localhost`.

### Fluxo da tarefa

1. O dono pede. O Jarvis confirma o que entendeu, usando `Aprovacoes`.
2. Aprovado, o Jarvis enfileira o comando e o agente do PC envia o pedido ao agente principal
   (`chat.send`, sessão do agente `hermes`).
3. Um cartão entra no quadro, ligado ao `runId` dessa execução.
4. O agente principal distribui o trabalho e cria agentes se precisar, usando as ferramentas de
   equipe descritas em "Comunicação entre agentes".
5. O servidor do Hermes3D move o cartão conforme a execução avança.
6. Ao terminar, o Jarvis avisa e entrega o resultado, seguindo o padrão "inicia e avisa quando
   terminar" da `rodar_rotina`.

## Decisões do dono

- Sem MCP: o Hermes3D conversa direto com o `jarvis-agente-pc`.
- O Jarvis fala só com o agente principal, que repassa as tarefas e cria agentes quando preciso.
- Os agentes criados continuam disponíveis para tarefas futuras.
- Só o dono desativa agentes. Não existe desativação automática.
- Os agentes precisam conseguir se comunicar entre si.
- O servidor do Hermes3D inicia junto com o agente do PC.
- "Como está a empresa" responde com tarefas em andamento e concluídas.
- O andamento das tarefas passa a ser acompanhado no servidor, para valer com o escritório fechado.

## Decisões técnicas tomadas por mim (Claude), para o dono conferir

**Na conversa**

- O status mostra as concluídas dos últimos sete dias.
- Ler o status não pede confirmação. Passar tarefa pede, por uma ação nova em `AcaoAutonoma` com
  padrão `PERGUNTA`, que o dono pode relaxar como as outras.
- O resultado vem como resumo na mensagem. Se for longo demais para o Telegram, o texto completo vai
  como arquivo pelo caminho que `criar_arquivo` já usa.
- Com o PC desligado, as ferramentas recusam e dizem isso, usando `ResultadoFerramenta.pcOffline`.
  Tarefa não entra em fila para depois.

**Inicialização**

- O agente do PC sobe e vigia três processos, reiniciando quem cair:
  1. O backend: `python -m hermes_cli.main --profile default gateway run --quiet`, com o Python em
     `%LOCALAPPDATA%\hermes\tools` (porta 8642). Hoje ele não inicia sozinho com o Windows; se já
     estiver no ar, o agente não sobe outro.
  2. O adaptador: `node server/hermes-gateway-adapter.js` (porta 18789).
  3. O Studio em produção: `node server/index.js` (porta 3000).
- Sem o Studio aberto o quadro não recebe a escrita pela rota `/api/task-store`; ele precisa subir
  mesmo quando ninguém abre o escritório.
- Não existe build de produção hoje (`.next/BUILD_ID` ausente). O `npm run build` é passo de
  instalação e precisa ser feito uma vez. Os caminhos vão no `config.properties`.
- O agente do PC encerra esses processos quando encerra, para não deixar cópias órfãs.

**Abrir a empresa**

- Comando novo no `ExecutorDeAcoes`, com o endereço fixo `http://localhost:3000/office`. O
  `abrir_url` existente só aceita https e deve continuar assim. A VPS não envia o endereço.

**Ferramentas do Jarvis**

- Três ferramentas em `dev.jarvis.ferramentas`, grupo PC: abrir a empresa, ver o status e delegar.
- Nomes e descrições fixos, sem nomes de agentes nem estado variável, para não invalidar o cache do
  array `tools` (regra da seção "A lista de ferramentas tem de ser a mesma de ontem" do `CLAUDE.md`).
- O que vem dos agentes entra no modelo como dado, nunca como instrução.

**Acompanhamento no servidor**

- O acompanhamento fica no adaptador, onde as execuções acontecem. Ele trata início, fim e erro e
  grava pelo `/api/task-store`, usando o mesmo `runId` do navegador para os dois não criarem cartões
  duplicados.
- Estados usados: `inbox`, `working`, `done` e `needs_attention`, os que o quadro já conhece.

**Resultado vazio ou incompleto**

- Resposta final vazia, ou `finish_reason` diferente de `stop` (o backend usa `length` quando o
  orçamento da execução acaba), vira `needs_attention`, nunca `done`, com o motivo no cartão.
- Se o adaptador reiniciar no meio de uma execução, o cartão que estava em `working` vira
  `needs_attention` ao subir de novo.

**Comunicação entre agentes**

Causa do problema, confirmada no código, nos logs e na conversa com o Closer:

- O adaptador envia `TEAM_TOOLS` no campo `tools` de `/v1/chat/completions`, mas o backend Hermes
  não usa esse campo: `_handle_chat_completions` só o considera na impressão digital do pedido. O
  agente roda com as ferramentas do próprio backend (arquivos, terminal, web).
- Por isso `spawn_agent`, `delegate_task` e `list_team` nunca chegam ao modelo, apesar de o prompt do
  agente principal dizer que existem. Os logs não têm nenhuma chamada a elas, e o próprio Hermes
  respondeu que não as tinha.
- Os agentes da empresa têm instruções como "Passar o briefing aprovado para o Captain", sem nenhum
  meio de fazer isso.
- O `agents.handoff` do Hermes3D só existe na interface do navegador; agentes não o alcançam.

Solução:

- Um plugin do backend Hermes em `plugins/`, ao lado do `hermes3d-office-bridge`, registra as
  ferramentas com `ctx.register_tool`. O toolset do plugin precisa ser habilitado para a plataforma
  `api_server`, e a instalação tem de funcionar no Windows (o `install.sh` atual é para shell).
- O plugin chama o adaptador em `127.0.0.1`, que continua sendo o dono do registro de agentes e faz
  a execução aparecer no escritório como já acontece com `delegate_task`.
- Todo agente recebe: ver a equipe e enviar pedido a outro agente, aguardando a resposta.
- Só o agente principal recebe: criar agente e alterar agente. Agentes criados ficam disponíveis.
- Nenhum agente recebe ferramenta para desativar ou apagar outro. O `dismiss_agent` sai.
- O `TEAM_TOOLS` e o laço de ferramentas do adaptador, que nunca funcionaram com este backend, saem
  para não enganar quem ler o código. O prompt do agente principal passa a citar só o que existe.

Proteções:

- Uma cadeia de pedidos tem no máximo três níveis (A pede a B, B a C, C a D recusado).
- Um agente não pode receber pedido de uma cadeia em que já está esperando resposta.
- Recusas voltam como resultado claro, para o agente explicar em vez de inventar.
- O limite padrão do backend é de 10 execuções simultâneas, e cada nível da cadeia ocupa uma.
- O agente nunca diz que repassou algo sem ter recebido confirmação da ferramenta.

**Desativação de agentes**

- Não há desativação automática. O dono desativa pelo que o Hermes3D já oferece no escritório.

## O que fica a critério de quem construir

- Se o agente do PC fala com o adaptador por WebSocket (o JDK já tem cliente) ou por uma rota HTTP
  local nova. O adaptador exige o `connect` antes de qualquer método e só escuta em `127.0.0.1`.
- Como a VPS fica sabendo do fim da tarefa, reaproveitando o caminho de resultado das rotinas.
- Como evitar escrita simultânea no `tasks.json` entre o adaptador e o Studio.
- Nomes finais de ferramentas, comandos e chaves de configuração.
- Como o plugin identifica qual agente está chamando e a qual cadeia o pedido pertence. O adaptador
  conhece a sessão, e o backend aceita o cabeçalho `X-Hermes-Session-Key`.

## Riscos conhecidos

- `next build` nunca rodou nesta máquina, e o repositório tem erros de lint e de tipos já
  conhecidos em alguns arquivos. Validar o build logo no início, porque é pré-requisito do Studio.
- O adaptador aceita conexão de qualquer origem, e isso passa a ser perigoso com ele sempre ligado:
  uma página aberta no navegador poderia falar com agentes que têm terminal. O adaptador e o Studio
  devem recusar origens não locais, e as rotas novas exigem o token do backend.
- O caminho do plugin foi lido no código do backend, mas ainda não foi testado. A primeira etapa é
  provar uma ida e volta entre dois agentes, antes de construir o resto.
- Todos os agentes têm as ferramentas do backend, incluindo terminal e arquivos no PC. Uma tarefa
  passada pelo Jarvis pode acabar executando comandos na máquina, e por isso delegar pede confirmação.
- O Hermes3D fica dentro do OneDrive, e a sincronização pode interferir em `node_modules` e `.next`.

## Como saber que deu certo

1. Com o PC recém-ligado e sem abrir nada, os três processos estão no ar.
2. "Abre a minha empresa" mostra o escritório no navegador do PC.
3. O pedido da campanha pede confirmação, gera o cartão e termina com os quatro conteúdos entregues
   no Telegram, tudo com o escritório fechado.
4. Durante essa execução, "como está a empresa?" mostra a tarefa em andamento e, depois, concluída.
5. Com uma resposta vazia ou incompleta, o cartão fica em "precisa de atenção" e o Jarvis avisa, sem
   dizer "concluído".
6. Pedindo ao Closer "revise esses leads e repasse para o Hermes revisar", o Hermes recebe o pedido
   e a resposta dele volta ao Closer.
7. Perguntando ao Hermes quem está na equipe, ele lista todos os agentes.
8. Uma cadeia acima de três níveis, ou que volte a quem já está esperando, é recusada com motivo.
9. Com o PC desligado, as ferramentas dizem isso claramente e nada fica pendurado.
