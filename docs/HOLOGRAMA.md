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

*Este é o texto da Fase 0. O que vale é o arquivo `src/main/resources/holograma/holograma-prompt.txt`,
que ganhou desde então `pts` relativos, `rad.a`, `ct` e, na Fase 7, a seção REPETIÇÃO, a escala e o
eixo da altura (seção 16).*

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

ECONOMIA (o objeto tem de caber na resposta)
- Números com no máximo 2 casas decimais: 1.25, nunca 1.2500 nem 1.24999.
- ids curtos (até 16 caracteres, ex.: "pe_ne", "arco_1") e nomes (n) curtos, de até 3 palavras.
- "pts" com no máximo 8 pontos.
- Omita "r" quando for [0,0,0].

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
- [x] **Cena na HUD.** Three.js carregado sob demanda, camada `app-holograma-view` no palco,
  validador `lerPecaHolograma`, montagem peça por peça, visual neon, mouse, fechar.
- [x] **Geração com streaming (backend + agente + HUD).** `HologramaService` e
  `GET /api/holograma/gerar`, `ProvedorDeModelo.transmitir` na Anthropic e no local, parciais do
  `inferir` pelo E2, campo e Cancelar na HUD, varredura de "escaneando". Contrato na seção 8.
- [x] ~~**Ferramenta e publicação.**~~ Absorvida pela Fase 6 (a ferramenta `holograma`, com
  `carregar` e `gerar_na_galeria`, e o `holograma.comando` no SSE geral) e pela galeria
  (`GET /api/holograma/{id}/stream` reabre um objeto). Não existe `mostrar_holograma`.
- [x] ~~**Fase 4 — Interação com as peças.**~~ Absorvida pela cena e pela Fase 6: vista explodida
  pelo `e` (slider, botão, voz e gesto), destaque por grupo ao passar o mouse, nome da peça no painel,
  seleção, Esc fecha e `prefers-reduced-motion` desliga entrada, giro e voo.
- [x] **Fase 5 — Gestos pela webcam e toque.** MediaPipe Hand Landmarker no navegador, arquivos
  servidos pela própria HUD, liga/desliga explícito, motor de gestos em TS puro, toque no celular,
  perfil leve, ajustes e gravação de landmarks. Só HUD. Detalhes na seção 10.
- [x] **Fase 5B — Celular como controle (backend + HUD).** QR de uso único no PC, página
  `/controle` no celular (câmera no perfil leve ou touchpad), WebSocket `/ws/holograma` com
  validação e limite de taxa, queda e retomada em 30 s, Wake Lock, medida de ida e volta. Contrato
  na seção 11.
- [x] **Fase 6 — Controle por voz/conversa (backend + HUD).** Uma ferramenta `holograma` com
  `acao` em enum, estado da cena no bloco dinâmico (`POST /api/holograma/estado`), caminho rápido
  sem modelo, porta única da cena na HUD. Contrato na seção 12.
- [x] **Galeria (backend + HUD).** Objeto completo guardado em disco, cache pelo pedido
  normalizado, reprodução pelo mesmo SSE, lista/abrir/apagar/"Gerar de novo" e miniatura capturada
  pela HUD. Os exemplos viraram itens fixos da galeria. Contrato na seção 9.
- [x] **Fase 7 — Acabamento.** Lotes instanciados acima de 120 peças com FPS medido antes e depois
  (seção 13), benchmark dos dez objetos de referência nos dois geradores (seção 14), suíte de falhas
  (seção 15), ajuste do prompt pelo benchmark (seção 16), configuração e testes (seções 17 e 18).

---

## 5. Decisões de arquitetura propostas

1. ~~**Sem streaming na primeira versão.**~~ **Substituída na geração (seção 8): há streaming
   real nos dois provedores.** A espera pela resposta inteira de um objeto `detalhado` passava de
   um minuto com a tela parada; agora cada linha completa vira peça enquanto o modelo escreve. O
   caminho é o que esta decisão já previa: um método `default` novo na interface
   (`ProvedorDeModelo.transmitir`), não um caminho paralelo.

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

11. ~~**Comandos por voz entram no `ComandoTela.ACOES`**~~ **Substituída na Fase 6 (seção 12):**
    uma ferramenta `holograma` própria, com evento `holograma.comando` pelo mesmo `EventosHud.aoTela`,
    e estado em `POST /api/holograma/estado` — o corpo (até 250 peças) e a cadência (300 ms) são
    outros, e o `EstadoTela` da HUD velha continua igual.

12. **Biblioteca em disco** (Fase 7) com `ArquivoAtomico`, chave pelo `Texto.normalizar` do objeto
    mais o nível — "mostra o motor V8 de novo" não gasta modelo. Pasta derivada de um volume
    existente ou um volume `holograma` novo: decidir na fase, pela regra de esticar bloco aninhado
    do `JarvisProperties`.

---

## 6. Riscos e bloqueios

| # | Risco | Efeito | Saída proposta |
|---|---|---|---|
| 1 | **Nenhum provedor faz streaming** (Claude sem `stream`, Ollama `stream:false`, E2 com resultado num POST só) | Primeira peça só aparece depois da geração inteira | **Resolvido**: streaming nos dois provedores (seção 8) |
| 2 | **Premissa do Qwen como principal não confere** | A conversa é Claude; o local só atende apoio, e só com `IA_LOCAL_ATIVA=true` e PC ligado | Decisão 2: holograma como apoio; com o PC desligado, quem gera é o Claude (custo) |
| 3 | **Teto de tokens**: `max_tokens` padrão 1024; uma peça tem ~50–70 tokens, `detalhado` (110 linhas) passa de 6 mil | Resposta cortada | Teto explícito por nível na chamada curta |
| 4 | **Local lento demais para `detalhado`**: Qwen 9B gerando milhares de tokens não cabe em `IA_LOCAL_TIMEOUT_SEGUNDOS=60` nem em `ollama.timeoutSegundos=90`; `num_ctx=8192` divide espaço entre prompt e saída | Cai no Claude quase sempre no `detalhado` | Com stream, os dois prazos do local viraram **inatividade** (seção 8), e o total é o da geração (180 s). Falta medir o Qwen no `detalhado`; o `num_ctx` continua dividindo espaço |
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
| 18 | **QR fotografado ou visto por outra pessoa** | Quem abrir a URL em 5 min controla o holograma | O código é de uso único e morre ao entrar; a sessão do celular só manda gestos (nunca chama `/api`); "Desconectar" corta na hora; um celular por vez |
| 19 | **Rede do celular (4G, Wi-Fi fraco)** | Ida e volta passa dos 150 ms, giro atrasado | A medida está na tela do `/controle` e no log; acima de 150 ms, WebRTC DataChannel (seção 11) |

---

## 7. Contrato fixado na Fase 1

### Eventos do stream

`GET /api/holograma/exemplo/{nome}` (`motor_v8`, `dna`, `atomo`), `Authorization: Bearer
<HUD_TOKEN>`, `text/event-stream`, 40 ms entre peças, fecha depois do `fim`. 404 para nome
desconhecido (o nome casa `[a-z0-9_]{1,40}` antes de virar caminho de recurso).

| Evento | `data` |
|---|---|
| `meta` | `{"nome","categoria","descricao"}` — sempre o primeiro, uma vez só. Na geração real leva também `id` (`h` + 8 caracteres), o que o "Continuar" devolve |
| `peca` | `{"id","nome","grupo","forma","posicao":[3],"rotacao":[3],"tamanho":[..],"cor","explosao":[3],"opacidade","contorno","pontos":[[3]..],"serie"?}` — `serie` só nas cópias de um `rep`/`rad` (Fase 7, seção 13) |
| `aviso` | `{"texto"}` — o que foi consertado ou descartado |
| `fim` | `{"pecas","avisos","status","motivo"?}` — sempre o último. `status` é `completo` quando o modelo escreveu a linha de fim (ou o limite de 250 peças fechou) e `incompleto` quando quem fechou foi o servidor; aí vem o `motivo`, frase fixa em Java |

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
- Cópias recebem ids `id_1..id_n` e o campo `serie` com o id da primeira (`id_1`, ou o nome
  desvirado, se o id cru se repetiu). A peça escrita uma vez só não leva o campo — o JSON fica igual
  ao de antes, e a galeria gravada antes dele continua lendo.

### Geometria que a HUD tem de seguir (Fase 3)

- Euler na ordem `XYZ` (a padrão do Three.js) — a do `Rotacoes`.
- `cyl`, `cap` e `cone` nascem em pé, no eixo `y`, centrados em `p`; `tor` e `ring` nascem no
  plano `XY` (padrão do Three.js), por isso deitar um anel é `r: [90,0,0]`.
- `helix [raio, alt, voltas, espessura]`: centrada em `p`, de `y = -alt/2` a `+alt/2`, o ponto na
  fração `t` é `Ry(360·voltas·t) · (raio, 0, 0)` — ou seja, começa em `+x` e gira no sentido
  positivo de `y`. É isso que faz os pares do `dna.ndjson` encostarem nas fitas.
- `tube`/`line`: pontos relativos a `p`, com `r` aplicado.

---

## 8. A geração com streaming

### A rota

`GET /api/holograma/gerar?objeto=<texto>&detalhe=rapido|normal|detalhado` (padrão `normal`),
`Authorization: Bearer <HUD_TOKEN>`, `text/event-stream` com `X-Accel-Buffering: no`. Objeto vazio,
acima de 120 caracteres ou detalhe desconhecido → 400. Os eventos são os da seção 7, mais um:

| Evento | `data` |
|---|---|
| `erro` | `{"texto"}` — a geração parou antes da hora. Sai **antes** do `fim`, que continua sendo o último; as peças que já foram ficam |

O texto do `erro` é fixo em Java: "O modelo não respondeu a tempo.", "Falha ao gerar o holograma." ou
"A geração foi substituída por outra.". O erro do provedor fica no log e não chega à tela.

Enquanto gera, o servidor manda um comentário SSE (`:gerando`) a cada 3 s. Não é para a HUD: é a
escrita que revela o cliente que foi embora, já que antes da primeira peça não há outra escrita
para falhar.

### O caminho de uma peça

```
modelo ──delta──► BufferDeLinhas ──linha──► MontagemDeHolograma ──► emitter.send
(Anthropic: text_delta do SSE; local: parcial do agente pelo E2)
```

O `HologramaService` (`dev.jarvis.holograma`) faz uma chamada com `system` = o prompt
(`src/main/resources/holograma/holograma-prompt.txt`, com `{{OBJETO}}` e `{{DETALHE}}`) e
`user` = `"Gere: <objeto>"`, sem histórico, `Origem.HOLOGRAMA` (balde de custo próprio, esforço do
perfil de apoio) e `max_tokens` por nível: `HOLOGRAMA_MAX_TOKENS_RAPIDO` (4000),
`HOLOGRAMA_MAX_TOKENS_NORMAL` (8000) e `HOLOGRAMA_MAX_TOKENS_DETALHADO` (16000). No preço configurado
(US$ 0,50 por milhão de saída), o teto do detalhado fica em US$ 0,008 por chamada.

*Até 29/10/2026 era um `HOLOGRAMA_MAX_TOKENS` só, de 8000, que **prendia** os três níveis: o
detalhado nunca recebia os 16000 e saía cortado ("Torre Eiffel": 72 peças, a última linha pela
metade e o aviso "A resposta acabou sem a linha de fim"). A variável antiga não é mais lida.*

### A continuação

Uma resposta que acaba **sem a linha de fim** — `stop_reason` `max_tokens`, ou o stream que fechou
sem `message_stop` (sem motivo) — ganha até **duas** chamadas novas
(`HologramaService.MAX_CONTINUACOES`) ao **mesmo** provedor, com três mensagens: o `"Gere: <objeto>"`
original, as linhas já aceitas como fala do assistente (só as que viraram meta ou peça; a meia linha
cortada fica de fora) e `PromptDeHolograma.CONTINUAR`, que pede só as peças que faltam, sem repetir
meta nem id. As peças novas saem **no mesmo SSE**, entre o que já estava na tela.

O servidor não confia no "sem repetir": na continuação a `MontagemDeHolograma` ignora calada a meta
repetida e **descarta** a peça cujo id já existe (o `id` cru, ou o `id_1` de um `rep`/`rad`), em vez
de renomeá-la como faz na primeira chamada — um modelo que recomeça do início desenharia o objeto de
novo por cima. Cada chamada tem o próprio prazo de 180 s, e os tokens somam no `fim`.

Esgotadas as continuações, o `fim` sai `incompleto` com o motivo: `o modelo bateu no teto de tokens`
ou `a resposta acabou sem a linha de fim`. Erro e prazo também fecham `incompleto`, com o texto do
`erro` como motivo. **Objeto incompleto não vai para cache nenhum**: a galeria (seção 9) guarda só
`status: completo`.

### A retomada

`GET /api/holograma/continuar?id=<id>&recebidas=<n>` (Bearer). A última geração com peça na tela
fica guardada por 30 minutos (`HologramaService.RETENCAO`): a montagem, a meta e as peças na ordem
em que saíram. A rota reenvia a meta e as peças a partir da `n`-ésima — as que a HUD não recebeu —
e, se o objeto não estava completo, roda o mesmo laço de continuação. Completo, só reenvia e manda
o `fim` (é o caso do `fim` que se perdeu na rede). 400 para id torto ou `recebidas` negativo; 404
quando outra geração tomou o lugar ou a retenção passou. Uma geração do mesmo id ainda rodando (a
HUD desistiu antes de o servidor perceber) é cortada e esperada até 5 s.

Na HUD, a fase `incompleto` mostra "Incompleto: <motivo> · N peças" e o botão **Continuar**, que
chama a rota com `recebidas` = peças na tela e **não** limpa a cena. A HUD também chega a
`incompleto` sozinha quando a conexão cai com peça na tela: o stream acabou sem `fim`, o `fetch`
falhou, ou nenhum byte chegou por 15 s (`SILENCIO_MAXIMO_MS`; o servidor manda `:gerando` a cada
3 s). É o celular que pôs a aba em segundo plano: na volta (`visibilitychange`) a conferência é
imediata, a tela mostra o que chegou e oferece Continuar em vez de perder tudo.

### Quem gera

`HOLOGRAMA_PROVEDOR=anthropic` (o padrão) usa o Haiku (`HOLOGRAMA_MODELO=claude-haiku-5-5`) e tem o
Ollama do PC de reserva; `local` faz o inverso. O pedido sobrescreve o padrão com
`?provedor=haiku|local` (outro valor → 400), e é o que o seletor "Gerador" da HUD manda.

A reserva só assume **antes de qualquer peça**: erro da API (sem crédito, 429/5xx), cabeçalhos que
não chegam em 45 s ou nenhum texto em 45 s (`HologramaService.PRIMEIRA_RESPOSTA`, só enquanto há local
para assumir — sem reserva, cortar um Haiku lento só trocaria demora por erro). A troca sai como
`aviso` com `"troca":true`. Depois da primeira peça não há reserva: outro modelo desenharia outro
objeto por cima do que já está na tela, então sai `erro` e as peças ficam. O seletor de modo
(`AUTO`/`LOCAL`/`CLAUDE`) não entra aqui.

`HOLOGRAMA_MODELO` é o modelo **da Anthropic** e não toca a conversa; no local quem escolhe é o
`ollama.modelo` do PC, como sempre. `HOLOGRAMA_TEMPERATURA` (0,4) só vai ao local, preso em 0..1
pelo agente: os modelos atuais da Anthropic recusam `temperature` com HTTP 400. O teto de saída é por
nível (`HOLOGRAMA_MAX_TOKENS_RAPIDO`/`_NORMAL`/`_DETALHADO`) e também só vale na Anthropic: o
agente não manda `num_predict`, e o local para no `ollama.contexto` (8192, prompt incluso).

### O gasto no `fim` e no log

O `fim` ganha `provedor`, `modelo`, `tokensEntrada`, `tokensSaida` e `custoUsd` — os tokens vêm do
`usage` da API e o custo é `HOLOGRAMA_PRECO_ENTRADA_USD`/`HOLOGRAMA_PRECO_SAIDA_USD` (0,10 e 0,50
por milhão). Campo que não se sabe **fica de fora**: stream cortado (limite de 250 peças, prazo,
cliente) não recebe o `usage`, e o local não cobra da API. A HUD mostra o selo "Haiku · 86 peças ·
US$0,002" com isso. O custo real continua no `MedidorDeCusto` (`Origem.HOLOGRAMA`); este é só a
estimativa por geração. O item da galeria guarda provedor e modelo, não o gasto.

### Quem para

Um `Cancelamento` só, que fecha o `InputStream` da Anthropic ou cancela o `inferir` no PC:

| Motivo | Quando | O que a HUD recebe |
|---|---|---|
| `limite` | a montagem chegou a 250 peças | `fim` |
| `tempo` | 180 s desde o pedido | `erro` + `fim` |
| `cliente` | o ping ou um `send` falhou, ou o emitter fechou | nada (não há quem ouça) |
| `substituido` | chegou outra geração (há uma cena e uma GPU só) | `erro` + `fim` |

### O stream do local

O agente chama o Ollama com `stream: true` só quando o pedido traz `stream` **e** ele tem canal de
parciais. Junta o `message.content` de cada linha NDJSON e posta o texto em
`POST /api/pc/v2/comandos/{id}/parcial` (`{"inicio","texto"}`) a cada quebra de linha ou 300 ms.
A VPS responde 204, ou **410** quando desistiu, e o 410 faz o agente fechar o stream, o que para o
Ollama e solta a GPU. O resultado final vem pelo `/resultado` de sempre, com o texto inteiro, e é a
fonte da verdade: a VPS emenda só o pedaço que começa onde o recebido termina e, no fim, completa
pelo `content` o que uma parcial perdida deixou para trás.

Os dois prazos do local mudam de sentido no stream: o `IA_LOCAL_TIMEOUT_SEGUNDOS` da VPS e o
`ollama.timeoutSegundos` do agente passam a ser de **inatividade** (sem texto novo nesse tempo, cai).
Uma geração longa que está escrevendo não é um PC travado. O total é o da geração.

### Log

Uma linha **por chamada** ao modelo, com o `stop_reason` como o provedor o escreve (`end_turn`,
`max_tokens`, `nenhum` para o stream que fechou sem dizer por quê; no local, `stop`/`length`), os
tokens de saída, o teto, se a linha de fim do modelo chegou e, quando a última linha veio pela
metade, o texto dela:

```
Holograma 'Torre Eiffel' (detalhado) chamada inicial: provedor=anthropic stop_reason=max_tokens
  tokens_saida=16000 teto=16000 linha_fim=não peças até aqui=118, última linha cortada: {"t":"p","id":"vi
Holograma 'Torre Eiffel' (detalhado) chamada continuação 1: provedor=anthropic stop_reason=end_turn
  tokens_saida=2310 teto=16000 linha_fim=sim peças até aqui=141
```

E uma por geração, com o total: peças, chamadas, linhas descartadas, repetidas, avisos, tokens
(entrada/saída, somados), custo estimado, `status` (com o motivo, se incompleto), se o **evento
`fim`** chegou a ser enviado à HUD (`não enviado` = o cliente tinha saído) e o fim interno. Havendo
linha descartada, mais uma com o texto bruto das cinco primeiras (até 300 caracteres cada). A troca
de provedor continua numa linha à parte (`Holograma: local → anthropic: pc_offline`).

### nginx

Nada a mudar, conferido em 29/10/2026 no `deploy/nginx-jarvis.conf`: `/api/holograma/gerar` e
`/api/holograma/continuar` caem em `location /api/`, que tem `proxy_buffering off`,
`proxy_read_timeout 1h`, `proxy_send_timeout 1h` e `proxy_http_version 1.1`. O pior caso de um
pedido é 3 × 180 s mais folga, abaixo da hora. O `X-Accel-Buffering: no` só protege a rota se ela
mudar de bloco um dia. *(O arquivo da VPS é reescrito pelo certbot; confira lá com
`sudo nginx -T | grep -A12 'location /api/ '`.)*

---

## 9. A galeria

### O que entra

Quando o `fim` sai **completo** e com pelo menos **8 peças** (`GaleriaDeHologramas.MINIMO_DE_PECAS`),
o `HologramaService` guarda o objeto normalizado — meta, peças já expandidas, provedor, modelo,
detalhe, pedido e data — **antes** de mandar o `fim`. A ordem importa: a HUD manda a miniatura logo
que recebe o `fim`, e o id já tem de existir. Incompleto não entra (é meio objeto, e o cache o
serviria pela metade para sempre); a retomada que fecha completa entra.

Sem banco, como o resto do Jarvis: um `<id>.json` por objeto e o `<id>.png` da miniatura em
`HOLOGRAMA_PASTA` (padrão `/data/jarvis/holograma`, volume `holograma`), gravados com a troca
atômica do `ArquivoAtomico`. Em memória fica só o índice; as peças são lidas do disco ao abrir. Teto
de 60 objetos: acima disso o mais velho sai, com a miniatura.

### A chave

`GaleriaDeHologramas.chave(objeto, detalhe)`: o **pedido** do dono (não o nome que o modelo deu)
pelo `Texto.normalizar` — sem acento, minúsculo, espaço simples —, sem os artigos `o a os as um uma
uns umas`, mais `|` e o nível. "o Motor V8" e "motor v8" são o mesmo; o mesmo objeto em outro nível é
outro. Salvar com a chave de um objeto que já existe **substitui** o anterior (JSON e miniatura).

### As rotas

Todas com `Authorization: Bearer <HUD_TOKEN>`, sem exceção de `?token=`.

| Rota | O que faz |
|---|---|
| `GET /api/holograma/gerar?...&novo=true` | Sem `novo`, consulta a galeria primeiro: achou, reproduz pelo mesmo SSE com 15 ms entre peças e `fim` com `origem: cache`. `novo=true` vai direto ao modelo e o objeto novo substitui o salvo |
| `GET /api/holograma/galeria` | `[{id, nome, pecas, data, provedor, detalhe, objeto, fixo, miniatura}]` — exemplos primeiro, depois do mais novo ao mais velho |
| `GET /api/holograma/{id}/stream` | Reproduz um item (inclusive exemplo) pelo SSE; 400 id torto, 404 inexistente |
| `DELETE /api/holograma/{id}` | 204; 404 inexistente; **409** para exemplo |
| `POST /api/holograma/{id}/miniatura` | `{"imagem":"data:image/png;base64,..."}`; só PNG **conferido pelos bytes** (`PcDto.mimeDe`), até 300 kB; 204, 400 ou 404 |
| `GET /api/holograma/{id}/miniatura` | O PNG; 404 sem miniatura. A HUD lê com `fetch` e Bearer e mostra por `blob:` |

O id casa `[a-z0-9_]{1,40}` antes de virar caminho. O CORS passou a liberar `DELETE`.

O `fim` ganhou três campos: `origem` (`modelo` ou `cache`) e, só quando o objeto está na galeria,
`salvo: true` e `miniatura` (se a imagem já existe). `salvo` com `miniatura: false` é o pedido para a
HUD capturar.

### Os exemplos

Os três de `src/main/resources/holograma/exemplos` entram como itens **fixos** (`exemplo_motor_v8`,
`exemplo_dna`, `exemplo_atomo`, provedor `exemplo`), montados uma vez pela `MontagemDeHolograma`. Não
se apagam e não respondem a pedido de geração — são vitrine, não cache. Aceitam miniatura. A rota
`/api/holograma/exemplo/{nome}` continua existindo, mas a HUD não a usa mais: os botões
temporários deram lugar à galeria.

### Na HUD

`app-galeria-holograma` (painel na coluna da direita; no celular, uma fileira que rola de lado):
miniatura ou ◇, nome, "Haiku · 86 peças", abrir (o item inteiro), ↻ "Gerar de novo" (repete o
`objeto` e o `detalhe` do item com `novo=true` e o gerador escolhido agora) e ✕ apagar — em dois
cliques, porque não há volta, e ausente nos fixos. As chamadas moram no `HologramaGaleriaService`.

A miniatura: com a fase `pronto` e um `fim` `salvo` sem `miniatura`, a view espera 1,5 s (a última
peça acender e a câmera chegar), chama `CenaHolograma.miniatura(256)` — um `render` e a leitura na
mesma tarefa, porque o WebGL não preserva o buffer — com o quadrado central da tela, e manda o
`toDataURL('image/png')`. A imagem entra na lista na hora; trocar de objeto antes dos 1,5 s desarma.

---

## 10. Gestos e toque (Fase 5, só HUD)

### Peças

| Onde (em `jarvis-hud/src/app`) | O quê |
|---|---|
| `gestos/` | **TS puro, sem Angular nem Three.js** (a Fase 5B reaproveita): `MotorDeGestos` (landmarks → eventos), `FiltroUmEuro`, `geometria-da-mao`, modelos e o leitor de gravação |
| `nucleo/rastreio-de-mao.service.ts` | Câmera + Hand Landmarker. Botão "Gestos" (`ativo`), `status`, `aviso`, `delegate`, `gravar()` |
| `nucleo/perfil-do-aparelho.service.ts` | `pc` ou `leve` por `(pointer: coarse)` + menor lado da tela ≤ 1024 px; a escolha manual passa por cima |
| `nucleo/ajustes-de-gestos.service.ts` | Perfil, sensibilidades (0,25–3), inverter eixos, preview — `localStorage` `jarvis-hud.gestos.v1` |
| `componentes/holograma/controle-por-gestos.ts` | Único ponto que conhece motor e cena |
| `componentes/holograma/controle-de-toque.ts` | `ReconhecedorDeToque` (puro) + a casca de pointer events |
| `preview-gestos`, `botoes-gestos`, `ajustes-gestos` | Canto da câmera, botões Gestos/Ajustes com o aviso, `<dialog>` de ajustes |

### MediaPipe sem CDN

`@mediapipe/tasks-vision` 1.1.1 (versão fixa). O `angular.json` copia `vision_wasm_internal.*` e
`vision_wasm_nosimd_internal.*` do pacote para `mediapipe/wasm/`; o modelo `hand_landmarker.task`
(float16, 7,8 MB) mora em `public/mediapipe/`. O JS do MediaPipe é `import()` dinâmico — chunk
próprio de ~155 kB que só baixa ao ligar os gestos. Delegate `GPU`, e `CPU` se a criação falhar.

### Câmera

Só com o holograma aberto **e** "Gestos" ligado; fechar o holograma desliga e solta o botão.
`facingMode: 'user'`, `<video>` com `playsinline` e `muted` (iPhone). Detecção por
`requestVideoFrameCallback` (ou rAF) a no máximo 30/s (PC) ou 15/s (leve), fora da detecção de
mudanças: os quadros vão por callback, e signals só mudam com o estado. Desliga sozinha após 2 min
sem mão; aba oculta solta a câmera e a volta religa sem perguntar. Sem HTTPS, sem câmera, permissão
negada ou câmera ocupada → frase curta acima da barra de controles, botão solto, toque e mouse
seguem. Nenhuma imagem é enviada; a gravação guarda só pontos.

**Pouca luz** (Fase 7): sem mão à vista, uma vez por segundo o quadro é reduzido a 32×24 num canvas
fora da página e o brilho médio (luma Rec. 709) é medido; abaixo de 40 de 255 em duas amostras
seguidas, o indicador troca "Câmera ligada" por "Pouca luz · acenda uma luz" (`role=status`), e volta
acima de 55. Com mão detectada não se mede: a luz basta. A amostra é a única cópia do quadro, é
sobrescrita no segundo seguinte e nunca é codificada. Regras em `gestos/luz.ts`.

| Perfil | Câmera | Detecções/s | Mãos | Bloom | pixelRatio | Segmentos |
|---|---|---|---|---|---|---|
| PC | 640×480 | 30 | 2 | completo | ≤ 1,5 | ×1 |
| Leve | 480×360 | 15 | 1 | meia resolução, força menor | 1 | ×0,5 (com piso) |

### A máquina de estados do motor

Antes da máquina, a cada quadro: a mão é seguida pelo `lado` do MediaPipe (ou pelo pulso, se o
rótulo trocar), cada ponto passa pelo One Euro (minCutoff 1, beta 5 — o 0,007 do artigo é para
pixels), e toda distância é dividida pelo **comprimento da mão** (pulso → ponta do médio somando os
ossos, que não muda quando os dedos dobram). Pinça com histerese: entra < 0,05, sai > 0,08 (pontas
encostadas dão ≈ 0,03). Pose por prioridade punho > pinça > apontar > aberta > outra, e só vale
depois de 150 ms igual.

```
SEM_MÃO ──mão──► OCIOSO
  └ 3 s sem mão → autoRotacao (uma vez)
OCIOSO
  pinça (PC) ─────────► GIRANDO ── soltou (histerese) → soltarGiro(v)  ─┐
  pinça nas 2 mãos (PC) ► ZOOM_2_MÃOS ── uma soltou ────────────────────┤
  pinça (leve) ───────► PINÇA_DECIDINDO: andou 0,03 → GIRANDO;           │
                         |Δln tamanho| 0,12 → ZOOM_PROFUNDIDADE ────────┤
  aberta + parada 0,6 s ► EXPLOSÃO: abertura → fator 0..1;               │
                         punho → trava o fator de 300 ms antes do punho  │
                         começar → PUNHO_SEGURO; sem mão 1 s trava o último
  apontar ────────────► APONTANDO: cursor; parado 0,8 s → apontarParado │
  punho ──────────────► PUNHO_ARMANDO: 1,2 s → resetar → PUNHO_SEGURO   │
PUNHO_SEGURO ── pose ≠ punho ───────────────────────────────────────────► OCIOSO
qualquer estado ── sem mão 200 ms ── fecha o gesto (soltarGiro, cursorFim, explosaoFim) ─► SEM_MÃO
```

O motor não conhece a cena: `apontarParado` só diz onde o dedo parou, e quem faz o raycast e
seleciona o grupo é o `ControlePorGestos`. Mão presente segura a rotação automática.

### Toque

Escutado na **captura** do host da cena e parado ali: o OrbitControls fica só com mouse e caneta
(pan desligado). 1 dedo gira (inércia ao soltar); 2 dedos decidem uma vez — distância mudando
(|ln d/d₀| > 0,08) é zoom, os dois subindo/descendo juntos (4% da altura) explodem/montam — e ficam
nisso até soltar; toque duplo (< 250 ms cada, < 300 ms entre eles, < 30 px) sobre peça seleciona o
grupo, no vazio reseta.

### Gravações e testes

"Gravar 5 s" nos ajustes baixa `gestos-<data>.json` (`GravacaoDeGestos`: `versao`, `perfil`,
`deteccoesPorSegundo`, `quadros` com os landmarks crus). Para virar teste: salve em
`src/app/gestos/fixtures/`, importe no `motor-de-gestos.spec.ts` e acrescente uma linha em
`GRAVACOES` com a sequência de eventos esperada. Hoje só há uma gravação **sintética**
(`pinca-arrastar-direita.sintetico.json`); as poses de teste vêm de `sintetizar-mao.ts`.
Os limiares (pinça, parada, decisões) foram calibrados na mão sintética e devem ser conferidos
nas primeiras gravações reais.

---

## 11. Celular como controle (Fase 5B)

O celular vira o controle remoto do holograma que está na tela do PC: o PC mostra um QR, o celular
abre `/controle`, e os gestos de lá (câmera ou touchpad) mexem na cena daqui pelo **mesmo**
`ControlePorGestos` dos gestos locais.

### Por que um WebSocket novo

O SSE de `/api/eventos` só vai do servidor à HUD, roda num executor de thread única junto com a
conversa e o briefing, e o `location /api/` do nginx força `Connection ""` (sem Upgrade). Do celular
ao PC seriam ~30 `POST`/s, cada um uma requisição HTTP com CORS, e não haveria como medir ida e
volta. Por isso `/ws/holograma`: WebSocket puro (Spring, sem STOMP), fora de `/api`.

| Onde | O quê |
|---|---|
| `dev.jarvis.holograma.controle` | `BilhetesDoCanal`, `PareamentosDoControle` (só ids de sessão, testável sem rede), `ValidadorDeMensagemDoControle`, `LimiteDeTaxa` |
| `dev.jarvis.web` | `CanalDoHologramaHandler` (papéis, repasse, códigos de fechamento, log da latência), `CanalDoHologramaConfig` (rota, origens, cópia da query), `CanalDoHologramaController` (bilhete) |
| `jarvis-hud/src/app/controle/` | TS puro: `controle.modelos.ts` (formato, conversões, leitura), `agregador.ts`, `conexao.ts` (reconexão), `latencia.ts` |
| `jarvis-hud/src/app/nucleo/` | `CanalDaHudService` (PC) e `CanalDoControleService` (celular) |
| `jarvis-hud/src/app/componentes/holograma/` | `parear-celular.ts` (botão, QR, estado) e `controle-remoto.ts` (mensagem → mesma cena) |
| `jarvis-hud/src/app/paginas/controle/` | A página do celular, rota lazy **sem** o guard de configuração |

### Pareamento

```
PC: POST /api/holograma/controle/bilhete (Bearer)  → {"bilhete","validade":30}
PC: wss://…/ws/holograma?bilhete=…                 → papel HUD
PC: {"t":"parear"}                                 → {"t":"par","codigo","expira":300}
PC mostra o QR: <origem da HUD>/controle?par=<codigo>&api=<URL da API>
Celular: wss://…/ws/holograma?par=<codigo>         → {"t":"pareado","retomada"}
PC recebe {"t":"controle","e":"conectado","retomada"}
```

- **Sem conta de usuário**: o Jarvis tem um dono e o "login" é o `HUD_TOKEN`. Decidido com o dono:
  o celular **não** precisa do token. O código do QR é a credencial — 32 bytes sorteados,
  base64url, **uso único**, 5 min, preso à sessão WebSocket da HUD que o pediu (um código novo
  derruba o anterior). A sessão do celular só manda gestos; nada nela chama `/api`.
- O `HUD_TOKEN` nunca vai em URL de socket: a HUD troca o Bearer por um bilhete de 30 s, uso único.
- O `api=` vai no QR porque o celular não tem nada configurado. A página confere com `urlValida` e
  recusa conteúdo misto. O `par` sai da barra de endereço logo ao entrar (`replaceUrl`): recarregar
  usa a chave de retomada guardada no `sessionStorage` da aba.
- **Um celular por vez**: com par, a HUD não recebe código novo (`{"t":"erro","e":"ocupado"}`).
- Origens do handshake: as de `HUD_ORIGEM`, como o CORS. Sem origem configurada, só a mesma origem.

### Mensagens

Do celular, um objeto — ou um array de até 8, quando o mesmo intervalo de 33 ms juntou mais de um
tipo. O servidor confere campo a campo (campo extra ou fora da faixa reprova o quadro inteiro) e
**reescreve** antes de repassar. Nunca imagem, nunca landmarks.

| Mensagem | Significado | Faixa |
|---|---|---|
| `{"t":"girar","dx","dy"}` | soma dos deslocamentos, frações da tela | ±1 |
| `{"t":"soltar","vx","vy"}` | soltou o giro: inércia, frações/s | ±20 |
| `{"t":"zoom","f"}` | produto dos fatores; > 1 aproxima | 0,5..2 |
| `{"t":"explodir","v"}` | fator absoluto (câmera) | 0..1 |
| `{"t":"explodir","dv"}` | soma ao fator atual (touchpad) | ±1 |
| `{"t":"cursor","x","y"}` / `{"t":"cursorFim"}` | indicador apontando / parou | 0..1 |
| `{"t":"selecionar"}` | indicador parado: a HUD seleciona na última posição do cursor | — |
| `{"t":"resetar"}` | punho; toque duplo no touchpad | — |
| `{"t":"mao","v"}` | mão entrou (1) / saiu (0): segura ou solta a rotação automática | 0, 1 |
| `{"t":"toque","v"}` | dedo na tela (1) / fora (0): `comecar/terminarInteracao` | 0, 1 |
| `{"t":"gesto","n"}` | rótulo do preview; letras, números e `.,…·%()-` | até 24 |
| `{"t":"eco","n"}` | medida de ida e volta, a cada 2 s | inteiro |
| `{"t":"latencia","p50","p95","k"}` | resumo a cada 30 ecos; **fica no log**, não vai à HUD | — |

Da HUD: `parear`, `desconectar` e a resposta do `eco`. Do servidor: `par`, `controle` (`conectado`
com `retomada` · `caiu` · `voltou` · `encerrado`) e `erro` à HUD; `pareado`, `hud` (`caiu` ·
`voltou` · `encerrado`) e o `eco` devolvido ao celular.

**Taxa:** o celular junta os deltas no `AgregadorDeEventos` (`girar` soma, `zoom` multiplica,
`cursor` e `explodir v` ficam com o último; o discreto não fura a fila) e manda no máximo 30
mensagens/s; o primeiro evento depois de um silêncio sai na hora. O servidor tem um balde de 40/s
por conexão: o excesso é descartado e contado, e mais de 100 descartes (taxa ou formato) em 10 s
fecham com 1008. Texto acima de 2 kB nem é montado (limite da sessão).

### Queda, retomada e fechamento

O servidor segura o par por **30 s** depois que qualquer lado cai. O celular volta sozinho com
`?retomada=` (esperas de 0,5 → 1 → 2 → 4 s, repetindo 4 s, até 30 s da queda); a HUD volta com
bilhete novo **e** a sua retomada. Passado o prazo, o par acaba (`encerrado`) e o celular é fechado
com 4404. O "Sair" do celular fecha com 1000, e isso acaba o par na hora.

| Código | Quando | O celular |
|---|---|---|
| 4401 | bilhete, código ou retomada inválido, usado ou vencido | "Este QR code já foi usado ou expirou." |
| 4404 | a HUD não voltou em 30 s | "A HUD do PC não voltou." |
| 4409 | já há celular | "Outro celular já está controlando o PC." |
| 4000 | "Desconectar" no PC | "O PC desconectou este celular." |
| 1008 | formato ou taxa | "O servidor recusou as mensagens deste aparelho." |

Na HUD, celular conectado pausa a câmera do PC (o mouse segue sempre). Na queda — do celular ou do
próprio canal —, o `ControleRemoto` desfaz o que ficou pela metade (cursor, rotação segurada, toque
aberto), a câmera volta se estava ligada e a região viva diz "Celular desconectado — de volta ao
mouse e à câmera."

### O mesmo suavizamento

O One Euro é do `MotorDeGestos`, que roda no celular exatamente como roda no PC (perfil leve: uma
mão, zoom pela profundidade, 15 detecções/s). O resto é igual por construção: a HUD transforma cada
mensagem de volta no `EventoDeGesto` (ou na intenção de toque) e chama o mesmo `ControlePorGestos`,
com a sensibilidade e os eixos dos ajustes **do PC**, a mesma inércia e os mesmos limites da cena.
O teste `controle.modelos.spec.ts` confere que a sequência local e a que passa pelo canal produzem
as mesmas chamadas na cena, na câmera e no touchpad.

### No celular

`/controle` em tela cheia, estilo da HUD, dois modos (`aria-pressed`): **Touchpad** (o
`ReconhecedorDeToque` da Fase 5 numa área sem cena; toque duplo reseta) e **Câmera**
(`RastreioDeMaoService` + `MotorDeGestos`, com o perfil leve forçado por
`PerfilDoAparelhoService.forcado` sem gravar nos ajustes do aparelho; a imagem não sai do
celular). **Wake Lock** (`screen`) enquanto o controle está ativo, pedido de novo no
`visibilitychange`; sem suporte, uma linha avisa.

### nginx

Precisa de bloco próprio, já em `deploy/nginx-jarvis.conf` (`location /ws/`): `proxy_http_version
1.1`, `Upgrade $http_upgrade`, `Connection "upgrade"`, `proxy_read_timeout`/`proxy_send_timeout 1h`
(o servidor manda ping a cada 25 s), `access_log off` (bilhete, código e retomada vão na query) e
`403` fora de HTTPS. Na VPS, como o certbot reescreve o arquivo, o bloco tem de ser colado também no
`server` de 443: `sudo nginx -t && sudo systemctl reload nginx`.

### Latência

Medida no caminho inteiro, celular → VPS → HUD → VPS → celular, pelo `eco`.

| Onde | p50 | p95 | Como |
|---|---|---|---|
| Local (teste de integração, mesmo processo) | 1,06 ms | 1,49 ms | `CanalDoHologramaWsTest`, 200 ecos, 29/10/2026 |
| VPS | *a medir depois do deploy* | | `HUD_TOKEN=… node scripts/medir-latencia-controle.mjs https://jarvis.barberagendaonline.store` e a linha "Ida e volta" do `/controle` |

Estimativa enquanto o deploy não sai: o TCP até a VPS (`/api/saude`, do PC de desenvolvimento,
29/10/2026) levou de 22 a 48 ms. O eco atravessa a VPS duas vezes, então o esperado é **~50–100 ms**
no Wi-Fi de casa — abaixo do teto de 150 ms. Em 4G cada perna soma de 30 a 60 ms e pode passar.

**Melhoria futura, se a medida da VPS passar de 150 ms:** WebRTC DataChannel. O WebSocket vira só
a sinalização (oferta, resposta e candidatos ICE pelo mesmo par), e os gestos vão direto do celular
ao PC pela rede local, sem passar pela VPS — com `ordered: false` e `maxRetransmits: 0` para giro e
zoom, que valem mais novos do que completos.

---

## 12. Voz e conversa (Fase 6)

O Jarvis mexe no holograma pela conversa — HUD ou Telegram — com **uma** ferramenta, `holograma`, e
enxerga a cena por uma linha no bloco dinâmico do system. Na HUD, tudo o que mexe na cena passa por
uma porta só, o `ComandosDoHologramaService`.

### A ferramenta

`HologramaFerramenta` (`dev.jarvis.ferramentas`), *faz e avisa*, adiada (`defer_loading`; a linha de
estado nomeia a ferramenta para a busca BM25). Esquema plano, sem `oneOf`, para o Qwen local errar
menos: `acao` (enum), `objeto`, `detalhe`, `novo`, `provedor` (`haiku|local`), `alvo`, `valor`
(string: fator, `mais`/`menos`, graus, `aproximar`/`afastar`, a vista), `eixo` e `ligar`.

| `acao` | O que manda à HUD | O que volta ao modelo |
|---|---|---|
| `abrir` / `fechar` | idem | frase curta |
| `carregar` | já na galeria: `abrir_galeria {id, nome}`; senão `carregar` com objeto, detalhe, novo, provedor e o molde `falaPronto` | "Diga só: 'Átomo de carbono na tela.'" ou "Diga só: 'Construindo motor V8.'"; dois itens casando voltam como pergunta |
| `explodir` | `explodir {fator}`, já resolvido: `mais`/`menos` = ±0,25 sobre o atual, `80%` = 0,8 | o fator |
| `montar` | `explodir {fator: 0}` | — |
| `girar` | `girar {eixo, graus}` (padrão `y`, 90°, preso a ±720) | — |
| `girar_continuo` / `parar` | `girar_continuo {ligar, velocidade}` / `parar` | — |
| `zoom` | `zoom {fator}` (`aproximar` 1,5, `afastar` 1/1,5, número 0,25..4) | — |
| `vista` | `vista {frente\|lado\|cima\|isometrica}` | — |
| `destacar` / `isolar` / `esconder` | `{grupos, pecas}` em ids | o nome resolvido, ou as opções do empate (e nada vai à tela) |
| `mostrar_tudo` / `resetar` / `cancelar_geracao` | idem | — |
| `selecionar` | `selecionar {peca}` | nome e grupo |
| `explicar` | nada | nome, grupo e objeto da peça (ou da selecionada) para o modelo explicar |
| `galeria` | nada | até 8 itens, "nome (N peças)" |
| `gerar_na_galeria` | nada (sem HUD) | "Comecei a montar…"; o fim chega pelo Telegram |

O comando vai no `ResultadoFerramenta.holograma` e o registro `Ferramentas` o publica como
`TelaEvento.HOLOGRAMA` (`holograma.comando`), pelo mesmo `EventosHud.aoTela` — o canal servidor→HUD
de sempre. Corpo: `{"acao", "dados": {...}, "rapido"}`.

**Sem HUD**: ação de cena responde "O holograma precisa da HUD aberta."; `carregar` manda o modelo
oferecer gerar e guardar na galeria; aceito, `gerar_na_galeria` roda a geração sem cliente
(`HologramaService.gerarSemTela`, um `DestinoDaGeracao.mudo` no lugar do SSE) e, completo, o
`AvisosProativos` manda "motor V8 está na galeria do holograma: 86 peças." (`FalasService`,
`holograma_na_galeria` / `holograma_na_galeria_falhou`).

**Pedido composto** ("abre o holograma e carrega o motor V8 explodido") são três `tool_use` na mesma
resposta, executados em ordem pelo `CicloDeFerramentas`. O retrato da HUD ainda não mudou quando o
`explodir` roda, então o `EstadoDoHologramaService` lembra o que o **servidor** mandou (abrir,
carregar, o último fator) e vale o mais novo entre isso e o retrato. A `holograma` está no limite
folgado de repetição (5), com `controlar_tela`: "mais, mais, mais" é de propósito.

### A galeria antes de gerar

`carregar` e `gerar_na_galeria` procuram primeiro na galeria pela `BuscaNaGaleria`, e não pela chave
exata do cache: a chave decide a reprodução do `/gerar` e é exata de propósito, mas "abre o átomo de
carbono da galeria" não é a chave de nada, outro nível é outra chave, e os exemplos fixos nem
respondem a pedido. A busca compara palavras (as do `ResolvedorDeAlvo`, sem "abre", "galeria",
"holograma") contra o **nome** e o **pedido** de cada item, exemplos inclusive: exato, o pedido
contido no item ("carbono" acha "Átomo de carbono") ou uma letra de diferença. Só nessa direção —
"motor V8 turbo" não abre o "Motor V8", porque a palavra a mais é o pedido. O mesmo objeto em dois
níveis fica com o pedido; objetos diferentes no topo voltam ao modelo para ele perguntar. Achado, vai
`abrir_galeria` pelo id e nada é gerado; sem HUD, a resposta é que ele já está na galeria. Só
`novo=true` pula a busca.

### O alvo

`ResolvedorDeAlvo` (puro): sem acento, minúsculo, `_` como espaço, sem artigo nem preposição, cada
palavra no singular (`ões→ão`, `ais→al`, `eis→el`, `ns→m`, `res→r`, `s→`). Nota 3 para as mesmas
palavras, 2 para o alvo contido no nome, 1 com uma letra de diferença em palavra de 5 ou mais.
Grupo e peça concorrem juntos, **exato vence**: com `pistoes` e `pinos_do_pistao`, "pistão" é o
primeiro; "pino" empata com "pino da biela", e o empate volta ao modelo com as opções para ele
perguntar. Peça com o nome do próprio grupo não vira segunda opção.

### O estado, do lado do modelo

`POST /api/holograma/estado` (Bearer, `HologramaEstadoController`, `EstadoDoHologramaDto` validado à
mão — ids e grupos `[a-z0-9_]{1,40}`, até 250 peças, explosão 0..1, vista conhecida). O retrato vale
10 minutos. Com o modo aberto e a HUD conectada, o `SistemaDoJarvis` põe uma linha no bloco
**dinâmico** (teto de 420 caracteres, 12 grupos):

```
Holograma aberto: 'Motor V8' (86 peças, pronto). Grupos: bloco, pistoes (8), virabrequim (5).
Explosão 0.8. Selecionada: 'Pistão esquerdo' (pistoes). Isolado: pistoes. Vista: frente.
Para mexer nele use a ferramenta holograma; "disso" e "essa peça" são a selecionada.
```

As peças **não** vão ao prompt; servem à resolução de alvo. Fechado, nada entra.

### O caminho rápido

`IntentoDoHolograma` (puro, irmão do `PedidoDeParada`): tirados os enfeites das pontas ("Jarvis",
"por favor", "de novo"), até 4 palavras que casem inteiras com um padrão — `explode`, `monta`,
`gira`, `para`, `mais`, `menos`, `aproxima`, `afasta`, `reseta`, `vista de cima|frente|lado`,
`isométrica`, `fecha (o holograma)`. O `AtalhoDoHolograma` só o liga com a HUD conectada e o modo
aberto, e o `Assistente` só o usa depois da `TrocaDeModelo` e **sem pergunta pendente** ("para" pode
ser o "não" de um "Posso?"). Executa a `holograma` direto, como o `ComandoDeRepouso`, com
`rapido: true`; a resposta sai `silenciosa` na HUD (sem fala; a HUD toca um bip curto pelo WebAudio)
e "Feito"-curto no Telegram. Log: `Holograma: caminho rápido 'mais' -> explodir` ou
`Holograma: caminho do modelo`, e a ferramenta loga `via=caminho rápido|modelo`.

### Na HUD

| Onde | O quê |
|---|---|
| `nucleo/comandos-do-holograma.service.ts` | A porta única: abrir, fechar, carregar, explodir, girar, zoom, vista, destacar, isolar, esconder, mostrarTudo, selecionar, resetar, cancelar. Implementa o `AlvoDoControle` que gestos, toque e celular já usavam |
| `nucleo/animador.ts` | 600 ms, ease-in-out, relógio injetável; `prefers-reduced-motion` pula ao fim |
| `nucleo/holograma-estado.service.ts` | O retrato, debounce de 300 ms, um POST por vez, reenvio a cada 5 min e na reconexão; fechado manda uma vez |
| `nucleo/holograma.modelos.ts` | `ComandoHolograma`, `lerComandoHolograma`, filtros |
| `nucleo/som-de-confirmacao.ts` | O bip do caminho rápido |

- **Voz anima, gesto é relativo.** O comando de voz anda até o valor; a explosão da mão vale
  `base + (abertura − abertura no início)`. Comando de voz no meio do gesto **rebaseia** (a base vira
  o alvo da voz, a referência vira a abertura atual): a voz vence e a mão segue do novo valor. A mão
  mexendo durante a animação muda o destino, não a corta. Giro e zoom por voz somam deltas — dois
  "gira 90" dão 180.
- **Objeto em construção.** `carregar` zera explosão, seleção e filtros **na hora do pedido** (o
  `effect` da view que zerava ao ver a geração nova saiu: desfaria o "explodido" que chega logo
  depois). Explosão é da cena inteira; destaque, isolamento e esconder são filtros por grupo e peça,
  e o `CenaHolograma.adicionar` aplica o filtro do momento a quem chega.
- **O fim falado.** O `carregar` traz o molde `"Pronto: {n} peças."` escolhido no servidor
  (`FalasDoHolograma`, variações sem repetir a anterior); ao `fim` completo **do modelo**, a HUD troca
  o `{n}` e fala. Do cache não fala de novo — o "na tela" já foi dito.
- O arrasto do mouse no canvas continua no OrbitControls, dentro da cena; ele só avisa
  (`aoArrastar`) para a vista nomeada virar `livre`.
- A HUD passou a narrar `resposta.fala || resposta.texto` e a calar a `silenciosa`.

---

## 13. Desempenho: lotes instanciados (Fase 7)

Cada peça avulsa custa **duas** chamadas de desenho (a malha translúcida e o contorno). Um objeto de
250 peças são 500 chamadas por quadro só na cena, antes dos passes do bloom — é o que pesa num
celular, e a maior parte dessas peças são cópias de um `rep`/`rad` (dentes, parafusos, elos, aletas).

**A regra:** passou de `LIMIAR_DE_INSTANCIAS` (**120**) peças, as cópias de uma mesma série viram um
`LoteDaSerie` (`componentes/holograma/lote-instanciado.ts`): a malha é um `InstancedMesh` e o
contorno, um `LineSegments` sobre `InstancedBufferGeometry` que lê **a mesma** matriz por instância.
Duas chamadas por série, em vez de duas por cópia. Abaixo de 120 nada muda.

- **A série vem do servidor**, no campo `serie` da peça (seção 7), e não de um palpite pelo id. A HUD
  não sabe o total de peças de antemão (o objeto chega em stream), então ao cruzar o limiar as séries
  que já estão na tela trocam a peça avulsa pelo lote no meio da montagem, e as cópias seguintes já
  nascem no lote. O estado da animação é da `PecaNaCena`, não do desenho: a entrada não reinicia.
- **Nada do comportamento muda.** Entrada, flash, explosão, destaque, filtros (isolar, esconder),
  hover, seleção, toque duplo e miniatura valem por cópia. A cópia escondida vira escala zero; o raio
  devolve o `instanceId` e a cena o traduz para a peça.
- **Cor e opacidade por instância** num atributo RGBA `corDaInstancia` que o shader `basic` do
  Three.js (o mesmo da malha e da linha) multiplica no `diffuse` e no `opacity`. Multiplicar só a cor
  daria a mesma luz no blending aditivo, mas não a mesma névoa: cada carcaça translúcida somaria a
  cor de fundo inteira, e um motor com dezenas delas ficaria azulado. Os trechos de shader
  substituídos são do Three.js 0.186; o `lote-instanciado.spec.ts` os confere contra o pacote
  instalado, para uma atualização que os renomeie quebrar o teste e não a tela.
- **Fica avulsa:** a `line` (linha contínua, que o `InstancedMesh` não desenha) e a peça sem série.
- **Enquadramento:** as matrizes do lote só são escritas no quadro, e o `centralizar` pode vir antes
  dele; ele escreve as matrizes primeiro, e mede peça a peça (a caixa do contorno instanciado é a de
  uma cópia na origem e puxaria o enquadramento para o centro).

### A medida

Abra a HUD com **`?medir=1`**: a cena publica, uma vez por segundo, "N fps · M chamadas · P peças ·
S séries instanciadas" no canto de baixo (fora do leitor de tela). **`?instancias=0`** desliga os
lotes — é o "antes". As chamadas somam o quadro inteiro (cena, bloom e saída).

`e2e/desempenho.spec.ts` monta um objeto sintético de **250 peças, 218 delas em seis séries**, e
mede os dois modos no Chrome sem vsync (`--disable-gpu-vsync --disable-frame-rate-limit`; com vsync
os dois batem no teto do monitor e a comparação não diz nada), com a CPU cheia e com a CPU 4× mais
lenta (CDP `Emulation.setCPUThrottlingRate`, aproximando um celular). Medido em 30/10/2026 no PC de
desenvolvimento (Windows 11, Chrome, 1440×1000, mediana de 6 segundos, três rodadas):

| CPU | Antes (`?instancias=0`) | Depois | Chamadas por quadro |
|---|---|---|---|
| cheia | 285–300 fps | 1042–1102 fps | 535 → 111 |
| 4× mais lenta | 53–58 fps | 128–141 fps | 535 → 111 |

As capturas dos dois modos (`cena-avulsa.png` e `cena-instanciada.png`, nos anexos do teste) são
iguais em cor, contorno, preenchimento e enquadramento. O número que vale para o celular de verdade
se mede nele: `?medir=1` funciona em qualquer aparelho.

---

## 14. Benchmark dos geradores (Fase 7)

Dez objetos de referência, que cobrem mecânica, biologia, química, arquitetura, anatomia, astronomia
e eletrônica (`BenchmarkDeHolograma.OBJETOS`): motor V8, DNA, átomo de carbono, torre Eiffel, coração
humano, sistema solar, drone quadricóptero, guitarra, prédio de 10 andares e teclado mecânico.

### Como roda

| Rota | O que faz |
|---|---|
| `POST /api/holograma/benchmark?detalhe=normal&provedores=local,anthropic` | Começa e volta **202** com o andamento; **409** se já há um rodando (com o andamento dele); 400 para nível ou gerador desconhecido (`haiku` vale como `anthropic`) |
| `GET /api/holograma/benchmark` | `{rodando, feitas, total, atual, relatorio, markdown}` — o último relatório pronto fica até o próximo |

Bearer como o resto de `/api`. Quem dispara é o `scripts/benchmark-holograma.mjs`
(`HUD_TOKEN=... node scripts/benchmark-holograma.mjs <url-da-api> [--detalhe normal] [--provedores local,anthropic]`),
que acompanha a cada 10 s e grava o Markdown na pasta atual. Na VPS ficam `<data>.json` e `<data>.md`
em `HOLOGRAMA_PASTA/benchmark/`.

- **O caminho é o da HUD**: `HologramaService.medir` usa o mesmo prompt, provedor, stream, montagem e
  continuação. Tira só três coisas: a **reserva** (o local que caiu seria medido como Haiku), a
  **galeria** (medir não pode trocar o objeto que o dono guardou) e a **retomada**.
- **Um de cada vez**, objeto por objeto, alternando os geradores: há uma GPU só no PC. Gerar pela HUD
  no meio corta a medição em andamento, que entra no relatório como `substituido`.
- Local fora de alcance (PC desligado, `IA_LOCAL_ATIVA=false`) não é tentado: a linha sai
  "indisponível (motivo)" e fica fora dos piores — é ambiente, não resultado do prompt.
- **Os números são os da linha de log de fim de geração** (seção 8): 1ª peça, total, peças (cópias
  expandidas), linhas aceitas (como o modelo as escreveu), linhas com `rep`/`rad`, descartadas,
  chamadas (inicial + continuações), tokens, custo, status e motivo. O JSON guarda também o NDJSON
  aceito de cada geração — é o que se lê para ajustar o prompt — e até cinco linhas descartadas.
- **Piores**, do mais grave ao menos: sem peça; incompleto; com linha descartada (pela fração);
  objeto pobre (menos linhas de peça do que o piso do nível: 13, 28 ou 58).

Sem a VPS, o gerador local se mede nesta máquina com `BenchmarkComOllamaTest` (seção 18): o mesmo
`BenchmarkDeHolograma` e o mesmo `HologramaService`, com um provedor de teste (`OllamaDireto`) que
manda ao Ollama o corpo que o agente do PC monta. Falta só o salto do E2, de milissegundos.

### Resultados de 30/10/2026

**Local** (`qwen3.5:9b`, Q4_K_M, `num_ctx` 8192, temperatura 0,4, nível `normal`, Ollama do PC de
desenvolvimento pelo `BenchmarkComOllamaTest`), com o prompt antes e depois do ajuste da seção 16. Uma
amostra por objeto — o modelo varia de uma geração para outra, então diferença de poucas linhas não é
sinal:

| Objeto | 1ª peça antes → depois | Total | Peças | Linhas (com rep/rad) | Status depois |
|---|---|---|---|---|---|
| motor V8 | 64,5 s* → 5,2 s | 121,5 → 56,3 s | 29 → 28 | 29 → 28 (0) | completo |
| DNA | 4,1 → 5,2 s | 35,4 → 67,2 s | 16 → 32 | 16 → 32 (0) | completo |
| átomo de carbono | 3,6 → 4,8 s | 41,0 → 27,3 s | 23 → 13 | 23 → 13 (0) | completo |
| torre Eiffel | 3,9 → 4,4 s | 54,6 → 132,6 s | 24 → **151** | 24 → 61 (**30**) | completo |
| coração humano | 4,1 → 4,8 s | 58,4 → 24,4 s | 21 → 15 | 21 → 10 (2) | completo |
| sistema solar | 3,4 → 4,3 s | 32,5 → 31,0 s | 18 → 18 | 18 → 18 (0) | completo |
| drone quadricóptero | 4,8 → 2,7 s | 33,2 → 29,3 s | 13 → 16 | 13 → 16 (0) | completo |
| guitarra | 5,2 → 4,6 s | 162,0 → **55,3 s** | 64 → **122** | 64 → 29 (**23**) | completo |
| prédio de 10 andares | 5,4 → 2,7 s | 76,5 → 432,0 s | 27 → 99 | 27 → 99 (0), 3 chamadas | completo |
| teclado mecânico | 4,2 → 2,9 s | 180,0 → 174,0 s | 78 → 77 | 78 → 77 (0), 3 chamadas | incompleto (contexto) |
| **soma / mediana** | 4,2 → 4,6 s | 58,4 → 56,3 s | **313 → 571** | rep/rad: 0 → 55 linhas | 9/10 → 9/10 |

\* O primeiro pedido carregou o modelo na GPU.

Antes, **peças = linhas em todas as gerações**: o Qwen nunca usava `rep`/`rad`. Zero linhas descartadas
nas duas rodadas — o formato não é o problema dele; a forma é.

**Anthropic (Haiku): não medido aqui.** A chave mora no `.env` da VPS; a medida é o
`scripts/benchmark-holograma.mjs` contra a API depois do deploy (seção 18), e a tabela acima vira
comparação dos dois geradores no mesmo relatório.

---

## 15. Falhas que não podem travar a HUD (Fase 7)

| Falha | Onde é tratada | Teste |
|---|---|---|
| Texto (ou cerca de markdown) antes do JSON | `HologramaLineParser` descarta a linha e ela conta em `descartadas` | `HologramaServiceTest.textoECercaDeMarkdownAntesDoJsonNaoAtrapalham`, `BenchmarkDeHologramaTest`, e2e "lixo antes e no meio do stream" |
| Linha cortada no meio | No servidor, `BufferDeLinhas` junta os deltas até a quebra de linha; a meia linha do fim é descartada e a continuação segue sem ela. Na HUD, `LeitorSse` + `TextDecoder` em modo stream juntam o evento partido, até no meio de um caractere UTF-8 | `linhaPartidaEntreDeltasViraUmaPecaSo`, `oTetoDeTokensPedeAContinuacaoComOQueJaChegou`, e2e "evento partido em dois pacotes" |
| Stream interrompido | Servidor: `erro` + `fim` incompleto, as peças ficam. HUD: corpo que acaba sem `fim`, ou `fetch` que falha com peça na tela, vira `incompleto` com **Continuar** | `haikuQueCaiNoMeioMantemAsPecas…`, `streamQueAcabaSemFimTambemContinua`, e2e "o servidor derruba a conexão no meio" (com o Continuar mandando `recebidas=2`) |
| 0 peças | Servidor: `erro` "O modelo não devolveu um objeto válido." (com reserva, o outro gerador tenta antes). HUD: estado legível, sem Continuar, Gerar de volta | `respostaSemNenhumaPeca…` (os dois), e2e "objeto sem nenhuma peça" (também o stream que fecha sem nada) |
| HUD desconecta no meio | Servidor: a escrita que falha (ou o `:gerando` a cada 3 s) corta o modelo e guarda a retomada por 30 min. HUD: 15 s sem byte viram `incompleto`, e o `fetch` abortado fecha o socket do lado do servidor | `aHudQueCaiNoMeioCortaOModeloEARetomadaTerminaOObjeto`, `oClienteQueSaiCortaOModeloAntesDaPrimeiraPeca`, e2e "conexão muda" |
| Câmera negada ou ocupada | `RastreioDeMaoService.explicar`: frase curta, botão solto, mouse e toque seguem | e2e `holograma.spec.ts` (negada) e `falhas.spec.ts` (ocupada) |
| Pouca luz | `gestos/luz.ts`: "Pouca luz · acenda uma luz" em vez de "Sem mão" para sempre (seção 10) | `luz.spec.ts`, e2e "câmera escura" com um Y4M quase preto como webcam falsa |

Todo e2e de falha termina em `aindaResponde`: a cena ainda desenha um quadro, Explodir/Montar
responde e o botão Gerar (ou Cancelar) está habilitado, sem `pageerror`. O `falhas.spec.ts` sobe um
servidor HTTP de verdade no próprio teste, porque o `route.fulfill` do Playwright entrega o corpo
inteiro de uma vez e não sabe partir um evento nem derrubar o socket no meio.

---

## 16. O ajuste do prompt (Fase 7)

### O que o benchmark mostrou

Os piores da primeira rodada (prompt antigo, Qwen) e o que as linhas cruas diziam:

1. **Teclado mecânico: incompleto, 78 linhas em 180 s.** Escrevia `switch_1`, `switch_2`… à mão, uma
   linha por tecla, até estourar o prazo. Peças = linhas em todos os dez objetos: `rep`/`rad` nunca
   eram usados. A regra existia, mas era uma frase abstrata no meio de "ORDEM E ESTRUTURA".
2. **Drone (13), DNA (16), sistema solar (18), coração (21): objetos magros**, abaixo das 30 linhas do
   nível `normal`. O nível falava em "linhas" sem dizer que `rep` conta como uma.
3. **Escala real em vez de maquete**: base do teclado com `"s":[36,1,20]` num cubo de −5 a 5 — o
   validador prende em 8, e a base fica do tamanho das teclas.
4. **Altura no eixo errado**: a base saía em `"p":[0,0,-2.5]` em quase todas as amostras — o
   "apoiado em y = −2" ia para o z, e o objeto deitava contra o fundo.
5. **Nomes em snake_case** (`"n":"switch_azul"`, `"nome":"teclado_mecanico"`): é o que aparece na
   tela e o que a voz usa para achar a peça.

### O diff

```diff
-NÍVEL DE DETALHE: {{DETALHE}}  (rapido = 15 a 30 linhas | normal = 30 a 60 linhas | detalhado = 60 a 110 linhas)
+NÍVEL DE DETALHE: {{DETALHE}}  (rapido = 15 a 30 linhas de peça | normal = 30 a 60 | detalhado = 60 a 110)
+Uma linha com "rep" ou "rad" conta como UMA linha, por mais cópias que gere. Chegue ao número do nível com peças DIFERENTES (subconjuntos, eixos, suportes, juntas, parafusos, encaixes), nunca com cópias escritas à mão.
@@ REGRAS DE SAÍDA
-- Cada linha precisa ser JSON válido e completo por si só.
+- Cada linha precisa ser JSON válido e completo por si só. Toda peça tem "p", "s" e "c".
+
+REPETIÇÃO (use sempre que houver peças iguais)
+- Iguais em fila (teclas, degraus, andares, janelas, trastes, dentes em linha): UMA linha com "rep":{"n":quantidade,"d":[dx,dy,dz]}, sendo "d" o passo de uma cópia para a seguinte.
+- Iguais em círculo ou em volta de um centro, mesmo que sejam só 3 ou 4 (pás, raios, braços, pernas, rodas, colunas, parafusos de um flange, elétrons): UMA linha com "rad":{"n":quantidade,"r":raio,"ax":"y"} em volta de "p"; "a" opcional é o ângulo inicial em graus.
+- "rep" e "rad" juntos formam uma hélice (escada caracol, pares de bases).
+- A linha com rep ou rad já é a primeira cópia: não escreva a peça avulsa antes dela.
+- "rep" com "d":[0,0,0] põe todas as cópias no mesmo lugar: em volta de um centro é "rad".
+- Nome terminado em número ("Braço 1", "Motor 2", "Tecla 3") ou id terminado em _1, _2, _3 é sinal de repetição à mão: troque por UMA linha com rep ou rad.
+- Exemplo, oito janelas de um vagão numa linha: {"t":"p","id":"janela",...,"rep":{"n":8,"d":[0.8,0,0]}}
+- Exemplo, as quatro pernas de uma mesa numa linha (em volta do centro, a 45° dos lados): {"t":"p","id":"perna",...,"rad":{"n":4,"r":1.7,"ax":"y","a":45}}
@@ ECONOMIA
-- ids curtos (até 16 caracteres, ex.: "pe_ne", "arco_1") e nomes (n) curtos, de até 3 palavras.
+- ids curtos (até 16 caracteres, ex.: "pe_ne", "arco") e nomes (n) curtos, de até 3 palavras.
@@
-COORDENADAS
+COORDENADAS E ESCALA
 - Y aponta para cima. O objeto inteiro cabe no cubo de -5 a 5, centralizado na origem e apoiado perto de y = -2.
+- "p" é [x, y, z] e a altura é o SEGUNDO número: a base de um objeto fica em "p":[0,-2,0], nunca em [0,0,-2]. O terceiro número é profundidade (frente e trás).
+- É uma maquete, não o tamanho real: a maior medida do objeto inteiro fica entre 6 e 8 unidades. Nenhum número de "s" passa de 8 (um teclado tem 8 de largura, não 12 nem 36), e as peças pequenas ficam proporcionais a ele.
@@ ORDEM E ESTRUTURA
-- Use "rep" ({"n":4,"d":[dx,dy,dz]}) para peças repetidas em linha e "rad" (...) para peças em círculo em volta de "p". (...) Nunca escreva a mesma peça várias vezes à mão.
-- Nomes (n) em português; ids em snake_case sem acento, únicos.
+- Nomes ("n" e o "nome" da meta) em português, como se fala, com espaços e acentos: "Tecla de espaço", "Pá da hélice". Nunca snake_case nos nomes.
+- ids e grupos em snake_case sem acento; ids únicos.
@@ ESTILO
-- Paleta neon: #38bdf8 (ciano), ... Use uma cor por grupo.
+- Use só esta paleta neon: #38bdf8 (ciano), ... Uma cor por grupo.
```

### Por quê, linha a linha

- **REPETIÇÃO virou seção com exemplos de linha inteira.** Modelo pequeno copia exemplo melhor do
  que segue regra; a regra antiga cabia numa frase e não tinha nenhum. Os exemplos são de objetos
  **fora** do benchmark (vagão, mesa), para não ensinar a resposta da prova. O "arco_1" saiu do exemplo
  de id porque ensinava exatamente o sufixo numérico que denuncia a cópia à mão.
- **O sinal do nome com número** ("Braço 1", `_1`) é a forma mais concreta de o modelo perceber a
  repetição no instante em que a está escrevendo — foi o padrão de todas as cópias à mão nas amostras.
- **O caso "3 ou 4 em volta do centro"** e o `d` nulo vieram das sondas do drone: braços e motores à
  mão, depois `rep` com `"d":[0,0,0]`, que empilha as quatro cópias no mesmo ponto.
- **"Linhas de peça" e "rep conta como uma"** separam o tamanho pedido do número de cópias, para o
  nível ser cumprido com partes diferentes.
- **Altura no segundo número, com o par certo e o errado**: foi o defeito mais sistemático das
  sondas, e o único que um exemplo só resolveu de vez.
- **Maquete de 6 a 8**: o teto de 8 já era do validador; dizer ao modelo evita a peça presa e
  desproporcional.
- **Nomes como se fala e só a paleta**: "Pistão esquerdo" é o que a voz resolve e a tela mostra.

### O que mudou, e o que não

Pelo benchmark (seção 14): peças de 313 para 571, linhas com `rep`/`rad` de **0 para 55**, a torre
Eiffel e a guitarra passaram a usar repetição (151 e 122 peças; a guitarra caiu de 162 para 55 s),
o DNA dobrou, os nomes saem em português e a base no chão. **Não resolveu** o teclado nem o prédio:
o prédio escreveu a mesma linha `"id":"janela"` noventa vezes (o validador renomeia para `janela_2`…
e conta cada uma como repetida) e o teclado escreveu `switch_coluna_1…73` — exatamente o padrão que a
regra nova descreve. Os dois agora usam as três chamadas e o teclado ainda fecha incompleto. Coração e
átomo saíram mais magros nesta amostra.

*Ressalva:* o exemplo de escala cita "teclado" e o de nome cita "Tecla de espaço", e o teclado é um
dos dez objetos do benchmark. Não ajudou o teclado nesta rodada, mas, se for comparar prompts no
futuro, troque esses dois exemplos por objetos fora da lista.

**Próximo passo, se o local virar o gerador padrão:** a repetição à mão é o que estoura prazo e
contexto, e prompt não a elimina num modelo de 9B. O servidor já a enxerga (a mesma forma e o mesmo
tamanho em linhas seguidas, ou ids com sufixo crescente) e poderia cortá-la — parar a geração depois de
N cópias à mão, ou reescrever o pedido de continuação dizendo qual peça repetir com `rep`. Isso muda o
contrato da montagem, e fica para decidir junto. O prompt vale também para o Haiku, que é o gerador
padrão: rode o `scripts/benchmark-holograma.mjs` logo depois do deploy. As regras novas são genéricas,
mas só a medida diz se não pioraram nada lá.

---

## 17. Como configurar

Tudo no `.env` da VPS (`JarvisProperties` → `application.yml` → `.env.example`), mais o agente do PC
para o gerador local:

| Variável | Padrão | Para quê |
|---|---|---|
| `HOLOGRAMA_PROVEDOR` | `anthropic` | Gerador padrão; o outro é a reserva antes da primeira peça. A HUD escolhe por pedido (`?provedor=haiku\|local`) |
| `HOLOGRAMA_MODELO` | `claude-haiku-5-5` | Modelo da Anthropic só do holograma; vazio usa o `ANTHROPIC_MODELO` |
| `HOLOGRAMA_TEMPERATURA` | `0.4` | Só o local; a Anthropic recusa o campo |
| `HOLOGRAMA_MAX_TOKENS_RAPIDO` / `_NORMAL` / `_DETALHADO` | 4000 / 8000 / 16000 | Teto de saída por chamada na Anthropic; acima dele vêm até duas continuações |
| `HOLOGRAMA_PRECO_ENTRADA_USD` / `_SAIDA_USD` | 0.10 / 0.50 | Só a estimativa do selo e do log; o custo real é o do `MedidorDeCusto` (`Origem.HOLOGRAMA`) |
| `HOLOGRAMA_PASTA` | `/data/jarvis/holograma` | Galeria (volume `holograma`) e, em `benchmark/`, os relatórios do benchmark |
| `IA_LOCAL_ATIVA` | `false` | Chave mestra do gerador local; desligada, o local nunca é tentado, nem como reserva |

No PC (`jarvis-agente-pc/config.properties`): `ollama.ativo=true`, `ollama.modelo` (o gerador local
é o que estiver ali), `ollama.contexto=8192` e `ollama.timeoutSegundos` (no stream, inatividade).

Na HUD não há configuração do holograma além da URL e do token da API. Parâmetros de URL só para
medir: `?medir=1` e `?instancias=0` (seção 13). nginx: nada a mudar (seção 8).

---

## 18. Como testar

```bash
# Backend: tudo, ou só o holograma
mvn -q -B package
mvn -B test -Dtest='Holograma*Test,BenchmarkDeHologramaTest,GaleriaDeHologramasTest,MontagemDeHologramaTest'

# HUD (no repositório irmão jarvis-hud)
npx ng test --watch=false                                  # unitários (Vitest)
npx playwright test e2e/falhas.spec.ts --reporter=list     # as falhas da seção 15
npx playwright test e2e/desempenho.spec.ts --reporter=list # FPS antes e depois (seção 13)
npx playwright test e2e/gestos.spec.ts                     # MediaPipe de verdade com câmera falsa

# Benchmark do gerador local nesta máquina, sem a VPS (precisa do Ollama com o modelo)
mvn -B test -Dtest=BenchmarkComOllamaTest -Dbenchmark.ollama=http://127.0.0.1:11434 -Dbenchmark.detalhe=normal

# Benchmark de verdade, na VPS, com os dois geradores (seção 14)
HUD_TOKEN=... node scripts/benchmark-holograma.mjs https://jarvis.barberagendaonline.store
```

Na mão: abrir a HUD, Holograma, gerar "motor V8" no nível Detalhado e conferir que as peças chegam
uma a uma, que o Explodir afasta cada grupo para o seu lado e que o selo mostra gerador, peças e
custo; com `?medir=1`, conferir o FPS e as séries instanciadas num objeto grande.

**Falhas conhecidas que não são desta fase:** dois e2e de `holograma.spec.ts` ("monta o exemplo
pelo stream" e "explica o token recusado") ainda procuram os botões de exemplo que a galeria
substituiu, e o `controle.spec.ts` "mostra o QR, aplica os gestos do celular…" para em "Vista
explodida = 0" esperando 60. Os três falham igual no código anterior à Fase 7 (conferido em
30/10/2026 com as mudanças da fase guardadas no `git stash`).
