# Modo Holograma

O dono pede um objeto ("mostra um motor V8"), um modelo descreve o objeto em NDJSON de peças
primitivas, o servidor valida linha a linha e manda cada peça pelo SSE, e a HUD (Three.js) monta
peça por peça com visual de holograma neon. Depois o objeto é controlado pelo mouse e por gestos da
mão na webcam (MediaPipe, tudo dentro do navegador): girar, ampliar e explodir.

Restrições: nenhuma API paga de geração 3D; a imagem da webcam nunca sai do navegador; o mouse
continua funcionando; os padrões do projeto valem (pacotes, estilo, `ProvedorDeModelo`, canal
`TelaEvento` → SSE).

---

## 1. O que existe hoje (levantamento da Fase 0)

### Versões

| Peça | Versão | Onde |
|---|---|---|
| Java | 21 | `pom.xml` (`java.version`), imagem `eclipse-temurin:21-jre` |
| Spring Boot | 4.1.0 | `spring-boot-starter-parent` |
| Jackson | 3 (`tools.jackson`) | via Boot |
| Angular | ^22.1.0 (CLI/build ^22.1.8) | `jarvis-hud/package.json` |
| TypeScript | ~6.0.2 | idem |
| Node | 24.19.0 na máquina de desenvolvimento; o projeto **não fixa** versão (sem `.nvmrc`, sem `engines`); `packageManager: npm@12.0.2` | — |
| Testes HUD | Vitest 4 + Playwright 1.64 + axe-core | idem |

A HUD **não tem** Three.js nem MediaPipe hoje: as dependências de runtime são só Angular, RxJS e
tslib. O orçamento de build é `initial` 500 kB (aviso) / 1 MB (erro) em `angular.json`.

### Como o backend chama o modelo

- **`ProvedorDeModelo`** (`dev.jarvis.ia.provedor`) é a interface: `responder(PedidoAoModelo)` e o
  `default responder(pedido, Predicate<RespostaDoModelo> aceita)`. Formato neutro: `Mensagem`,
  `Bloco` (`Texto`, `Chamada`, `Resultado`, `Opaco`), `RespostaDoModelo`, `OpcoesDoPedido`
  (`curta(origem, teto, prazo)`, `de(origem)`, `de(origem, perfil)`).
- Três implementações:
  - **`ProvedorAnthropic`** — `@Primary`, só tradução para o `ClaudeClient` (`HttpClient` puro em
    `/v1/messages`, repetição, cache, TTL, esforço e custo).
  - **`ProvedorLocal`** — Ollama do PC pelo comando `inferir` do agente (`PcService.inferir` → fila
    E2 → `jarvis-agente-pc` → `/api/chat`), com conferência de chamada de ferramenta contra o esquema.
  - **`ProvedorDeApoio`** (`@Qualifier("apoio")`) — pergunta ao `SeletorDeModelo` (modo
    `AUTO`/`LOCAL`/`CLAUDE`, chave mestra `IA_LOCAL_ATIVA`, `DisponibilidadeLocal` pelo cabeçalho
    `X-Pc-Ia`), tenta o local e cai no Claude quando o local está indisponível, falha ou o
    predicado reprova.
- **Atenção à premissa:** hoje o Qwen **não** é o principal da conversa. A conversa e o laço de
  ferramentas (`CicloDeFerramentas`) vão ao `ProvedorAnthropic`; o local só atende as cinco tarefas
  de apoio (classificador, anotador, revisor de procedimentos, narrador do briefing, introdução), e
  só com `IA_LOCAL_ATIVA=true` e o PC online. A entrada da conversa no seletor está prevista
  ("9.7"), não feita.
- **Streaming: não existe em lugar nenhum.** O `ClaudeClient` não manda `stream`; o agente do PC
  manda `"stream": false` ao Ollama (`InferenciaLocal`) e devolve o resultado inteiro num único
  `POST /api/pc/resultado`. Toda chamada é pedido → resposta completa.
- Limites relevantes: `ANTHROPIC_MAX_TOKENS` padrão 1024 no `application.yml` (2048 no
  `.env.example`), prazo padrão de 120 s no `ClaudeClient`; no PC, `qwen3.5:9b`,
  `ollama.contexto=8192`, `ollama.timeoutSegundos=90`; na VPS, `IA_LOCAL_TIMEOUT_SEGUNDOS=60` (e o
  prazo do local nunca passa disso). O `think` só vai `true` na `Origem.CONVERSA`.
- Custo e esforço são por `Origem` (`dev.jarvis.custo.Origem`: `CONVERSA`, `BRIEFING`,
  `INICIALIZACAO`, `ANOTADOR`, `CLASSIFICADOR`, `BUSCA`, `COMPUTER_USE`), resolvidos uma vez no
  construtor do `ClaudeClient`.

### Como o servidor manda comandos para a HUD

- **SSE**, uma conexão só: `GET /api/eventos` (`EventosController` → `EventosHud`, `SseEmitter`
  sem timeout, keep-alive `:ping` a cada 25 s, envio num executor de thread virtual única para uma
  HUD lenta não segurar o turno).
- Caminho da tela: a ferramenta devolve `cards`/`comandos` no `ResultadoFerramenta` → o registro
  `Ferramentas` publica `TelaEvento` (`ApplicationEventPublisher`) → `EventosHud.aoTela` repassa
  **pelo nome do evento** (`tela.card`, `tela.comando`, `voz.falar`). Tipo novo de `TelaEvento`
  chega à HUD sem fiação nova no backend.
- Sem HUD conectada (`PresencaDaHud.conectada()` falso) a ferramenta devolve `SEM_TELA` e não publica.
- Quem conecta depois **não recebe** eventos antigos: recupera o estado por HTTP.
- A HUD conta o que está aberto em `POST /api/tela/estado` (`TelaEstadoService`, até 12 linhas no
  bloco dinâmico do system).
- **Na HUD**, `JarvisEventosService` abre o `EventSource` com `?token=`, registra um
  `addEventListener` por nome (`receber<T>(nome, tratar)`), interpreta o JSON e chama os
  `OuvintesDoBriefing` que a `HudPage` passou. Cada payload passa por um validador antes de usar
  (`lerCardTela`, `lerComandoTela`, `lerFalaDaTela` em `nucleo/tela.modelos.ts`). Evento com nome
  desconhecido é simplesmente ignorado — HUD velha não quebra com evento novo.
- O `TelaService` aplica: `receber(card)` abre janela, `executar(comando)` trata as 20 ações de
  `ACOES_TELA` (espelho do `ComandoTela.ACOES` do Java).

### Como as ferramentas são registradas e executadas

- Ferramenta = `@Component` que implementa `dev.jarvis.ferramentas.Ferramenta` (`nome`,
  `descricao`, `esquemaEntrada`, `executar`/`executarDetalhado(entrada, OuvinteProgresso)`,
  `grupo`, `simples`, `oferecida`, `progresso`). O Spring injeta a lista no registro `Ferramentas`
  — não há lista manual.
- `Ferramentas` mantém um `TreeMap` (ordem alfabética, prefixo de cache estável), marca
  `defer_loading: true` fora de `FERRAMENTAS_SEMPRE_CARREGADAS` (a busca BM25 encontra o resto),
  confere impressões digitais das definições e avisa em WARN quando mudam.
- O `CicloDeFerramentas` roda o laço dentro do lock justo do `Assistente.responder` (um pedido por
  vez), com o portão de repetição (terceira chamada idêntica recusada), a checagem de
  `PedidoDeParada` e o teto `RESULTADO_MAX_CARACTERES`.
- Toda ferramenta cai num dos três níveis de autonomia (`PromptDeSistema.regrasDeAprovacao`). Tela
  é *faz e avisa*.

### Como a HUD organiza a tela e os cards

- Duas rotas lazy: `configuracao` e `''` (`HudPage`). Componentes standalone, signals, `@Service()`.
- `HudPage`: camadas decorativas (vinheta, grade, cantos, varredura), cabeçalho, `app-orbe` (dois
  canvas 2D — o reator e o campo de partículas, com `prefers-reduced-motion`), `app-sondas`, o
  `palco` com as janelas, a fileira do briefing, a barra de conversa e a `app-inicializacao`
  (sobreposição de tela cheia da abertura).
- Janelas: `JanelasService` (até 6 visíveis, minimizar/maximizar/fixar, geometria no
  `localStorage`), `JanelaComponent` (moldura) e `ConteudoJanelaComponent` (um `@switch` por
  `tipo`: pesquisa, vídeo, música, página, notícia, resposta, briefing).
- Estilo: tokens SCSS em `estilos/_hud.scss` (`$ciano #5ff2ff`, `$azul-energia`, `$serie-violeta`,
  `$serie-magenta`, `$mono`, o `@mixin moldura` de canto cortado).
- **Onde o Holograma se encaixa:** não como janela — card 2D de 640×440 dentro de um limite de
  seis não comporta uma cena 3D controlada por gesto. Ele é uma **camada do palco**, irmã da
  `app-inicializacao`: um componente `componentes/holograma/` que ocupa o `palco` e recua o orbe
  (como o `orbe--livre`/`orbe--formando` já faz), com um serviço `nucleo/holograma.service.ts`,
  modelos e validador em `nucleo/holograma.modelos.ts` e um ouvinte novo no
  `JarvisEventosService`.

### Autenticação da HUD e nginx

- `HUD_TOKEN` guardado no `localStorage` do navegador (`ConfigStore`). Toda chamada HTTP manda
  `Authorization: Bearer <token>`; o `TokenHudFiltro` exige o token em tudo sob `/api` menos
  `/api/saude`.
- `?token=` só é aceito em **GET** de `/api/eventos` (e o alias do briefing), `/api/anexos/{uuid}`
  e `/api/voz/audio/{sha256}` — os casos em que `EventSource`/`<audio>` não mandam cabeçalho.
- CORS por filtro (`WebConfig`), só para `HUD_ORIGEM`, sem credenciais.
- **Há nginx na frente**, `deploy/nginx-jarvis.conf` em `jarvis.barberagendaonline.store`: só
  `/api/` é exposto, com `proxy_buffering off`, `proxy_read_timeout 1h` (SSE) e `access_log off`
  (token na query). `/api/pc/` exige HTTPS. O container publica a porta 8090 só em `127.0.0.1`. A
  HUD é servida pela **Vercel** (`vercel.json`, sem CSP configurada).

### Onde o Jarvis guarda dados

Sem banco. Tudo é arquivo JSON/Markdown em volumes Docker, gravado com `ArquivoAtomico` (troca
atômica + `.bak`): `voz` (`/data/voz`, MP3 e `/data/voz/uso`), `memoria`, `memoria-exclusoes`
(e `procedimentos` dentro de `MEMORIA_PASTA`), `lembretes`, `historico`, `custo`, `diario`,
`proativo`, `pc-operacoes` (`/data/pc`: `operacoes.json`, `catalogo.json`, `modo-ia.json`) e
`google`. Configuração em `JarvisProperties` → `application.yml` → `.env.example`; texto editável
sem rebuild em `config/` (montado `:ro`, como a `personalidade.md`).

---

## 2. Formato NDJSON

Um objeto JSON por linha. Exemplo:

```
{"t":"meta","nome":"Motor V8","cat":"mecanica","desc":"Motor V8 em corte esquemático"}
{"t":"p","id":"bloco","n":"Bloco do motor","g":"bloco","f":"box","p":[0,0,0],"r":[0,0,0],"s":[3,1.6,1.4],"c":"#38bdf8","e":[0,-1,0]}
{"t":"p","id":"pistao_e","n":"Pistão esquerdo","g":"pistoes","f":"cyl","p":[-1.05,0.9,-0.45],"r":[0,0,-45],"s":[0.28,0.28,0.5],"c":"#f472b6","e":[-1,1,0],"rep":{"n":4,"d":[0.7,0,0]}}
{"t":"fim"}
```

| Campo | Linha | Significado |
|---|---|---|
| `t` | todas | `meta`, `p` (peça) ou `fim` |
| `nome`, `cat`, `desc` | meta | nome do objeto, categoria, uma frase |
| `id` | p | snake_case sem acento, único |
| `n` | p | nome da peça em português |
| `g` | p | grupo (subconjunto lógico) |
| `f` | p | forma: `box`, `sph`, `cyl`, `cone`, `tor`, `ring`, `cap`, `tube`, `line`, `helix` |
| `p`, `r` | p | posição; rotação em graus (X, Y, Z) |
| `s` | p | tamanho, com aridade por forma (ver o prompt) |
| `c` | p | cor `#rrggbb` |
| `e` | p | direção da vista explodida |
| `o` | p (opcional) | opacidade, ex. `0.25` em carcaças |
| `pts` | p (`tube`, `line`) | lista de pontos `[x,y,z]` |
| `rep` | p (opcional) | `{"n":4,"d":[dx,dy,dz]}` — repetição em linha |
| `rad` | p (opcional) | `{"n":12,"r":2,"ax":"y"}` — repetição em círculo |

## 3. Prompt de geração

```
Você é o módulo de hologramas do JARVIS. Sua tarefa é descrever um objeto 3D esquemático usando apenas formas primitivas, no formato NDJSON (um objeto JSON por linha).

OBJETO: {{OBJETO}}
NÍVEL DE DETALHE: {{DETALHE}}  (rapido = 15 a 30 linhas | normal = 30 a 60 linhas | detalhado = 60 a 110 linhas)

REGRAS DE SAÍDA
- Responda SOMENTE com linhas JSON. Nada de markdown, crases, comentários ou texto antes/depois.
- Linha 1: {"t":"meta","nome":"...","cat":"...","desc":"uma frase"}
- Depois, uma peça por linha: {"t":"p","id":"...","n":"...","g":"...","f":"...","p":[x,y,z],"r":[rx,ry,rz],"s":[...],"c":"#hex","e":[x,y,z]}
- Última linha: {"t":"fim"}
- Cada linha precisa ser JSON válido e completo por si só.

FORMAS (campo f) E TAMANHO (campo s)
box [larg,alt,prof] | sph [raio] | cyl [raioTopo,raioBase,alt] | cone [raio,alt] | tor [raio,espessura] | ring [raioInt,raioExt] | cap [raio,comprimento] | tube [raio] + "pts":[[x,y,z],...] | line + "pts" | helix [raio,alt,voltas,espessura]

COORDENADAS
- Y aponta para cima. O objeto inteiro cabe no cubo de -5 a 5, centralizado na origem e apoiado perto de y = -2.
- Rotação em graus (X, Y, Z). Cilindros, cápsulas e cones nascem em pé (eixo Y).
- Mantenha proporções plausíveis entre as peças.

ORDEM E ESTRUTURA
- Comece pelas peças grandes e estruturais (o contorno do objeto aparece primeiro), depois os subconjuntos, por último os detalhes pequenos.
- Use "g" para agrupar subconjuntos lógicos (ex.: bloco, pistoes, virabrequim, cabecotes).
- Use "rep" ({"n":4,"d":[dx,dy,dz]}) para peças repetidas em linha e "rad" ({"n":12,"r":2,"ax":"y"}) para peças em círculo. Nunca escreva a mesma peça várias vezes à mão.
- "e" é a direção para onde a peça se afasta na vista explodida: aponte para fora do centro do objeto, com peças do mesmo grupo na mesma direção.
- Nomes (n) em português; ids em snake_case sem acento, únicos.

ESTILO
- Paleta neon: #38bdf8 (ciano), #22d3ee, #a78bfa (violeta), #f472b6 (rosa), #34d399 (verde), #fbbf24 (âmbar), #f87171 (vermelho). Use uma cor por grupo.
- Carcaças e blocos externos: adicione "o":0.25 para deixar o interior visível.

Se o objeto for abstrato ou não tiver forma física, represente-o de forma simbólica com as mesmas regras.
```

---

## 4. Fases (proposta — ajuste à vontade)

- [x] **Fase 0** — Levantamento e este documento. Nenhum código alterado.
- [x] **Fase 1 — Formato e validação no Java, sem modelo.** `dev.jarvis.holograma`:
  `HologramaMeta`, `HologramaPeca`, `HologramaLineParser` (uma linha → `Optional<EventoHolograma>`),
  `HologramaValidator` (compacta → peças, com `rep`/`rad` e limites), `MontagemDeHolograma`
  (ordem do que sai), três exemplos em `src/main/resources/holograma/exemplos` e
  `GET /api/holograma/exemplo/{nome}` por SSE. Contrato fixado na seção 7.
- [ ] **Fase 2 — Geração e publicação no backend.** `GeradorDeHolograma` pelo `ProvedorDeApoio`,
  prompt em `config/`, ferramenta `mostrar_holograma`, execução assíncrona fora do lock do
  `Assistente`, eventos `holograma.*` cadenciados pelo SSE, `GET /api/holograma/{id}` para
  recuperação.
- [ ] **Fase 3 — Cena na HUD.** Three.js carregado sob demanda, camada `app-holograma` no palco,
  validador `lerPecaHolograma`, montagem peça por peça, visual neon (wireframe + bordas + bloom
  leve), mouse (girar, ampliar, arrastar), fechar.
- [ ] **Fase 4 — Interação com as peças.** Vista explodida pelo `e`, destaque por grupo, nome da
  peça ao passar/selecionar, teclado e `prefers-reduced-motion`.
- [ ] **Fase 5 — Gestos pela webcam.** MediaPipe Hand Landmarker no navegador, arquivos
  servidos pela própria HUD, liga/desliga explícito, gestos → as mesmas ações do mouse.
- [ ] **Fase 6 — Controle por voz/conversa.** Ações novas de tela para o holograma (girar,
  explodir, montar, fechar), estado do holograma no `POST /api/tela/estado`, frases do servidor.
- [ ] **Fase 7 — Biblioteca e acabamento.** Hologramas gerados guardados em disco (reabrir sem
  gastar modelo), medição de custo/tempo, desempenho, e2e e atualização do CLAUDE.md.

---

## 5. Decisões de arquitetura propostas

1. **Sem streaming na primeira versão; a montagem peça por peça é feita pelo servidor.** Nenhum
   provedor transmite hoje, e o local nem poderia sem mexer no agente (Ollama com `stream: false`,
   resultado num `POST` só). O gerador recebe o NDJSON inteiro, valida tudo e publica as peças
   **cadenciadas** (ex.: 60–120 ms entre elas, na ordem que o modelo escreveu — estrutura primeiro).
   Na tela o efeito é o mesmo. O `ProvedorDeModelo` e o `ClaudeClient` não mudam. Streaming
   real fica para depois, se a espera inicial incomodar — e aí seria um método `default` novo na
   interface, não um caminho paralelo.

2. **A geração é tarefa de apoio: `ProvedorDeApoio`, com o validador como predicado.** É uma
   chamada só, sem ferramentas, com `system` próprio — o desenho do `ClassificadorDeResposta`, não
   do laço da conversa. Assim o Qwen local é tentado primeiro (respeitando `AUTO`/`LOCAL`/`CLAUDE`,
   a chave mestra e o "pergunta pro Claude:") e o Claude é a reserva. O predicado aceita quando
   há `meta` e ao menos o mínimo de peças válidas do nível pedido; `fim` ausente não reprova
   (resposta cortada no teto ainda tem peças boas).

3. **`Origem.HOLOGRAMA` própria**, com esforço `low` derivado do `ESFORCO_APOIO` (sem variável
   nova) e teto de saída explícito por nível via `OpcoesDoPedido.curta(origem, teto, prazo)` — o
   `ANTHROPIC_MAX_TOKENS` de 1024 não comporta nem o nível `normal`. Balde próprio porque um
   holograma detalhado pode custar mais que uma conversa, e o dono precisa ver isso no
   `custo_do_jarvis`. *(Alternativa mais barata de código: reusar `ANOTADOR`, como o revisor de
   procedimentos — mas misturaria dois custos que se quer comparar.)*

4. **O prompt mora em `config/holograma.md`**, lido e recarregado como a `personalidade.md`
   (editável na VPS sem rebuild), com `{{OBJETO}}` e `{{DETALHE}}` substituídos em Java. O
   `{{OBJETO}}` é texto do dono e vai **cortado e sem quebras de linha**.

5. **Validação no Java, linha a linha, e a HUD revalida.** Linha inválida é **descartada**, não
   derruba o objeto. Regras: forma da lista fechada; aridade de `s` por forma; números finitos;
   posições presas ao cubo −5..5 e tamanhos a um teto; cor `#rrggbb`; `o` em 0..1; `id`
   `[a-z0-9_]{1,40}` único; `n`/`g` cortados; `pts` só em `tube`/`line`, até 32 pontos; `rep.n`
   e `rad.n` com teto; **teto total de peças já expandidas** (ex.: 300). Cercas de markdown e
   `<think>` são tiradas antes. É o mesmo cuidado de `MontadorDeCardDePesquisa` + `lerCardTela`.

6. **`rep`/`rad` são expandidos no servidor**, com ids `pistao_e_1..n` (o `n` conta a peça
   original). O teto de peças conta o que de fato vira malha, e a HUD só recebe peças finais.

7. **Ferramenta `mostrar_holograma`** (`objeto` texto curto, `detalhe` enum
   `rapido|normal|detalhado`), nível *faz e avisa*, `SEM_TELA` sem HUD. Ela **não espera** a
   geração: cria o id, dispara num executor de threads virtuais próprio (o desenho do
   `InicializacaoService`/`ConversaService`) e devolve "Comecei a montar o holograma de X." — frase
   verdadeira no instante em que sai. Esperar dentro do turno seguraria o lock do `Assistente` por
   até dois minutos e congelaria o Telegram. Falha posterior vira `holograma.erro` e uma fala do
   catálogo fechado (`FalaDaTela`). Descrição e esquema sem nada volátil (lista estável de tools).

8. **Eventos `TelaEvento` novos**, pegando carona no `EventosHud.aoTela`: `holograma.inicio`
   (`id`, `meta`, total previsto), `holograma.peca` (`id` do holograma + a peça) e
   `holograma.fim` / `holograma.erro`. Id do holograma do servidor, sorteado como
   `CardTela.novoId()`. **Recuperação por HTTP** (`GET /api/holograma/{id}`, Bearer), porque quem
   reconecta no meio não recebe os eventos perdidos — a regra que o `EventosHud` já documenta.

9. **Na HUD, camada do palco e não janela.** `app-holograma` em `componentes/holograma/`,
   `HologramaService` (`@Service()`, signals) e `holograma.modelos.ts` com o validador. O
   `JarvisEventosService` ganha os `receber(...)` dos eventos novos. Três.js é importado com
   `import()` dinâmico dentro do componente, para o orçamento `initial` de 1 MB continuar folgado.

10. **Webcam só no navegador, com arquivos da própria HUD.** `@mediapipe/tasks-vision` (Hand
    Landmarker) com o `.wasm` e o modelo `.task` copiados para `public/` — sem CDN em tempo de
    execução. O quadro vai do `<video>` ao detector e para ali: nada de `canvas.toBlob`, nada de
    `fetch` com imagem. Câmera só liga por ação explícita (botão/frase), com indicador visível, e
    desliga ao fechar o holograma. Gestos chamam as **mesmas** funções do mouse, que continua
    funcionando o tempo todo.

11. **Comandos por voz entram no `ComandoTela.ACOES`** (Fase 6) e no espelho `ACOES_TELA` da HUD,
    em vez de um canal novo. O estado do holograma entra no `EstadoTela` para o modelo enxergar o
    alvo.

12. **Biblioteca em disco** (Fase 7) com `ArquivoAtomico`, chave pelo `Texto.normalizar` do objeto
    mais o nível — "mostra o motor V8 de novo" não gasta modelo. Pasta derivada de um volume
    existente ou um volume `holograma` novo: decidir na fase, pela regra de esticar bloco aninhado
    do `JarvisProperties`.

---

## 6. Riscos e bloqueios

| # | Risco | Efeito | Saída proposta |
|---|---|---|---|
| 1 | **Nenhum provedor faz streaming** (Claude sem `stream`, Ollama `stream:false`, E2 com resultado num POST só) | Primeira peça só aparece depois da geração inteira | Decisão 1: cadência no servidor; streaming depois, se necessário |
| 2 | **Premissa do Qwen como principal não confere** | A conversa é Claude; o local só atende apoio, e só com `IA_LOCAL_ATIVA=true` e PC ligado | Decisão 2: holograma como apoio; com o PC desligado, quem gera é o Claude (custo) |
| 3 | **Teto de tokens**: `max_tokens` padrão 1024; uma peça tem ~50–70 tokens, `detalhado` (110 linhas) passa de 6 mil | Resposta cortada | Teto explícito por nível na chamada curta |
| 4 | **Local lento demais para `detalhado`**: Qwen 9B gerando milhares de tokens não cabe em `IA_LOCAL_TIMEOUT_SEGUNDOS=60` nem em `ollama.timeoutSegundos=90`; `num_ctx=8192` divide espaço entre prompt e saída | Cai no Claude quase sempre no `detalhado` | Medir na Fase 2; talvez o local só faça `rapido`/`normal`. O CLAUDE.md proíbe prazo do local acima do configurado |
| 5 | **Predicado do `ProvedorDeApoio` é tudo ou nada** | Resposta local com 40 peças boas e 3 tortas pode ser trocada pelo Claude | Predicado conta peças válidas, não exige perfeição (Decisão 2) |
| 6 | **Lista de `tools` muda** (ferramenta nova; e de novo na Fase 6 com ações novas no enum da `controlar_tela`) | Uma invalidação de cache de prompt por deploy | Aceitável; juntar as duas mudanças num deploy se possível |
| 7 | **Lock do `Assistente`** | Gerar dentro da ferramenta congelaria o bot | Decisão 7: geração assíncrona |
| 8 | **Reconexão do SSE no meio da montagem** | Peças perdidas, sem replay | `GET /api/holograma/{id}` |
| 9 | **Auth com `EventSource`** | Não é bloqueio: os eventos novos vão pelo SSE que já existe, e a recuperação é `HttpClient` com Bearer. Não criar outro `EventSource` nem rota nova com `?token=` | — |
| 10 | **nginx** | Não é bloqueio: `/api/` já tem `proxy_buffering off` e 1 h de leitura | — |
| 11 | **Peso na HUD**: Three.js (~600 kB) e MediaPipe (wasm + modelo, vários MB) contra orçamento de 1 MB | Build falha ou tela lenta | `import()` dinâmico; MediaPipe só ao ligar a câmera |
| 12 | **Webcam no PC da sala**: `getUserMedia` exige HTTPS (Vercel ok) e permissão; o Chrome aberto pelo agente pode mostrar prompt sem ninguém para clicar; o MediaPipe busca wasm/modelo em CDN por padrão | Gestos não ligam; dependência externa | Self-host em `public/`; permissão concedida uma vez no perfil do Chrome; mouse sempre disponível |
| 13 | **Acessibilidade (AXE/WCAG AA obrigatórios na HUD)** | Canvas 3D é opaco para leitor de tela | `role`/`aria-label` com nome e peças, controles de teclado, `prefers-reduced-motion` desliga a cadência animada |
| 14 | **Texto do modelo na tela** (`n`, `desc`, `nome`) | Injeção visual | Só interpolação Angular (nunca `innerHTML`), cortes de tamanho; nada disso vai ao prompt de outras conversas |
| 15 | **Dois repositórios, dois deploys** (VPS e Vercel) | Backend novo com HUD velha | Sem problema: HUD ignora evento desconhecido; a ferramenta só promete o que a HUD conectada sabe mostrar — decidir se a HUD anuncia suporte no `POST /api/tela/estado` |
| 16 | ~~**Semântica ambígua no formato**~~ | Resolvido na Fase 1 — ver seção 7. Falta levar ao prompt (Fase 2) que `pts` é relativo a `p` e que `rad` aceita `a` | — |
| 17 | **O stream do exemplo exige `Bearer`** | `EventSource` não manda cabeçalho, e a rota ficou fora da exceção do `?token=` (pedido da Fase 1: "a mesma autenticação") | Fase 3 lê com `fetch` + `ReadableStream`; ou, se preferir `EventSource`, acrescentar a rota à exceção do `TokenHudFiltro` |

---

## 7. Contrato fixado na Fase 1

### Eventos do stream

`GET /api/holograma/exemplo/{nome}` (`motor_v8`, `dna`, `atomo`), `Authorization: Bearer
<HUD_TOKEN>`, `text/event-stream`, 40 ms entre peças, fecha depois do `fim`. 404 para nome
desconhecido (o nome casa `[a-z0-9_]{1,40}` antes de virar caminho de recurso).

| Evento | `data` |
|---|---|
| `meta` | `{"nome","categoria","descricao"}` — sempre o primeiro, uma vez só |
| `peca` | `{"id","nome","grupo","forma","posicao":[3],"rotacao":[3],"tamanho":[..],"cor","explosao":[3],"opacidade","contorno","pontos":[[3]..]}` |
| `aviso` | `{"texto"}` — o que foi consertado ou descartado |
| `fim` | `{"pecas","avisos"}` — sempre o último, mesmo que o NDJSON não tenha `fim` |

A geração real (Fase 2) passa pela **mesma** `MontagemDeHolograma`, então a HUD que entende o
exemplo entende o modelo.

### Leitura da linha (`HologramaLineParser`)

Nunca lança. Volta vazio para: linha vazia, cerca de markdown, texto solto, `<think>`, JSON
quebrado, JSON que não é objeto e `t` desconhecido. Tolera vírgula no fim e `t` em maiúsculas.

### Normalização da peça (`HologramaValidator`, uma instância por objeto)

| Campo | Regra |
|---|---|
| `f` | ausente → `box`; desconhecida → `box` **com aviso** |
| `s` | aridade da forma; posição faltando ou torta → padrão da forma; cada número em 0.01..8. Padrões: `box [1,1,1]`, `sph [0.5]`, `cyl [0.5,0.5,1]`, `cone [0.5,1]`, `tor [1,0.1]`, `ring [0.6,1]`, `cap [0.3,1]`, `tube [0.05]`, `line []`, `helix [0.5,2,3,0.05]`. `ring` com interno ≥ externo é desvirado |
| `p` | três números em ±6 (o que faltar vira 0) |
| `r` | graus, normalizados para (-180, 180] |
| `c` | `#rrggbb` minúsculo; `#rgb` é expandido; o resto → `#38bdf8` |
| `e` | vetor **unitário**; sem direção, sai pela própria posição; no centro, `[0,1,0]` |
| `o` | 0.05..1, padrão 1 |
| `ct` | **novo, opcional**: `false` desliga o contorno (brilhos, feixes); padrão `true` |
| `pts` | só em `tube`/`line`, **relativos a `p`**, cada ponto em ±6, até 32; menos de 2 válidos → peça descartada com aviso |
| `id` | snake_case sem acento, até 40; vazio → `peca`; repetido → `_2`, `_3`… com aviso |
| `n` | até 60 caracteres; vazio → o id |
| `g` | snake_case; vazio → `geral` |

Limite de **250 peças** por objeto, contando as cópias: a linha que estoura entra pela metade, e
um aviso só diz que o resto foi descartado.

### Repetição

- **`rep {"n":4,"d":[dx,dy,dz]}`** — `n` cópias **contando a original** (1..60), a cópia `i`
  deslocada `i·d`. Rotação e explosão iguais.
- **`rad {"n":12,"r":2,"ax":"y","a":0}`** — `n` cópias (1..60) num círculo de raio `r` (0..6) em
  volta de `p`, no plano perpendicular a `ax`. O ângulo zero é `+x` nos eixos `y` e `z` e `+y` no
  eixo `x`; `a` (**novo, opcional**) é o ângulo inicial em graus. Cada cópia **gira junto**: a
  rotação é composta (`R(eixo, θ) · R(r)`, em Euler XYZ do Three.js) e a direção de explosão é
  girada, para dentes e elétrons se afastarem para fora. `r: 0` gira a peça no lugar.
- **`rep` e `rad` juntos = hélice**: `rep.n` cópias, a cópia `i` deslocada `i·d` **e** girada
  `a + i·360/rad.n`. É como o DNA faz os pares de bases numa linha só.
- Cópias recebem ids `id_1..id_n`.

### Geometria que a HUD tem de seguir (Fase 3)

- Euler na ordem `XYZ` (a padrão do Three.js) — a do `Rotacoes`.
- `cyl`, `cap` e `cone` nascem em pé, no eixo `y`, centrados em `p`; `tor` e `ring` nascem no
  plano `XY` (padrão do Three.js), por isso deitar um anel é `r: [90,0,0]`.
- `helix [raio, alt, voltas, espessura]`: centrada em `p`, de `y = -alt/2` a `+alt/2`, o ponto na
  fração `t` é `Ry(360·voltas·t) · (raio, 0, 0)` — ou seja, começa em `+x` e gira no sentido
  positivo de `y`. É isso que faz os pares do `dna.ndjson` encostarem nas fitas.
- `tube`/`line`: pontos relativos a `p`, com `r` aplicado.
