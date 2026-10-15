# CLAUDE.md

Jarvis: assistente pessoal no Telegram que usa o Claude para entender pedidos em linguagem
natural e executar ferramentas Java reais. Roda 24h numa VPS, dentro de um container Docker.

## Stack

- **Java 21** e **Spring Boot 4.1** (`spring-boot-starter-parent` 4.1.0). Só o starter base:
  injeção de dependência, configuração, agendamento e logs.
- **Jackson 3** — pacote `tools.jackson`, **não** `com.fasterxml.jackson`. O mapper é
  `tools.jackson.databind.json.JsonMapper`; os nós são `JsonNode`, `ObjectNode`, `ArrayNode`
  de `tools.jackson.databind`.
- **Telegram por long polling** (`getUpdates` com `timeout=30`): sem domínio, sem webhook,
  sem porta aberta. Quem roda o loop é o `TelegramPoller`, numa thread de plataforma
  não-daemon.
- **Claude chamado por `HttpClient` puro** — `ClaudeClient` faz `POST` em
  `https://api.anthropic.com/v1/messages` com `x-api-key` e `anthropic-version: 2023-06-01`,
  e repete a chamada em erros temporários (429, 500, 502, 503, 529). Sem SDK.
- **O `system` vai em dois blocos**: o fixo é a personalidade de `config/personalidade.md`, com
  `cache_control` quando passa de `ANTHROPIC_CACHE_MINIMO_TOKENS` (o mínimo muda por modelo); o
  dinâmico vem depois, com data, canal e regras. O cache casa **prefixo exato**, então nada que
  mude por pedido pode entrar no bloco fixo — nem o relógio, nem o nome do usuário.
- **Servidor web só para a HUD** (`spring-boot-starter-web`, porta `JARVIS_PORTA`, padrão 8090).
  O Telegram **não** depende dele: continua por long polling, sem porta aberta nem domínio.
  A API vive em `dev.jarvis.web`, exige `Authorization: Bearer <HUD_TOKEN>` em tudo menos
  `/api/saude`, e o `docker-compose` publica a porta só em `127.0.0.1` (o nginx faz o HTTPS).

## Mapa do código

```
Telegram ──► TelegramPoller ──► Assistente ──► Claude (Messages API)
                                   │   ▲             │
                                   ▼   │  "chame a ferramenta X"
                              Ferramentas ◄──────────┘
```

| Pacote | Responsabilidade |
|---|---|
| `dev.jarvis.telegram` | `TelegramClient` (Bot API) e `TelegramPoller` (loop + filtro de usuário) |
| `dev.jarvis.ia` | `Assistente` (loop de agente e histórico), `ClaudeClient`, `PersonalidadeService` (lê e recarrega o arquivo), `SistemaDoJarvis` (monta o `system` de qualquer chamada), `PromptDeSistema`, `Canal` e `BuscaWebServidor` (a definição da busca na web, que a Anthropic executa) |
| `dev.jarvis.ferramentas` | A interface `Ferramenta`, o registro `Ferramentas` e cada ferramenta |
| `dev.jarvis.briefing` | `BriefingService` (junta as fontes e monta o texto do dia) e `NarradorDeBriefing` (lê o briefing pronto com a personalidade) |
| `dev.jarvis.briefing.fontes` | As fontes: `ClimaService`, `NoticiasService`, `SitesService`, `ServidorService` |
| `dev.jarvis.lembretes` | `LembreteService`: agenda e dispara lembretes (em memória) |
| `dev.jarvis.agenda` | `ResumoDiario`: tarefa `@Scheduled` do bom dia |
| `dev.jarvis.config` | `BeansConfig` (beans compartilhados) e `JarvisProperties` (toda a config) |
| `dev.jarvis.voz` | A voz da HUD: `ProvedorVoz`/`ElevenLabsVoz`, `CacheDeVoz` (MP3 em disco), `MonitorDeCotaVoz` (reserva e volta ao navegador), `TextoParaVoz` e `VozService` |
| `dev.jarvis.tela` | A tela dirigida pelo Jarvis: `CardTela`/`DadosDoCard`/`ComandoTela`, `TelaEvento`, `TelaEstadoService` (o retrato da HUD), `MontadorDeCardDePesquisa`, `YoutubeService`, `PaginaService`, `GuardaSsrf` e a porta `PresencaDaHud` |
| `dev.jarvis.pc` | O PC Windows: `PcService` (fila de comandos), `ComandoPc`/`ResultadoPc` e `GuardaDeConfirmacao` (o código das ações perigosas) |
| `dev.jarvis.web` | API da HUD e do agente do PC: controllers, SSE (`EventosHud`), os dois filtros de token e CORS |
| `dev.jarvis.util` | `Json` (leitura defensiva de campos que podem não existir) e `ComparadorDeToken` (comparação de segredo em tempo constante) |

## Personalidade e canais

O jeito do Jarvis mora em `config/personalidade.md` — texto, não código. Quem lê é o
`PersonalidadeService`, que recarrega sozinho quando a data de modificação do arquivo muda, então
ajustar o tom na VPS não pede rebuild.

**Toda chamada nova ao Claude passa pelo `SistemaDoJarvis`**, pedindo os blocos de `system` com o
canal de onde veio o pedido:

```java
ArrayNode system = sistema.blocos(Canal.TELEGRAM);   // telegram | hud | briefing | tarefa
```

Não monte prompt de sistema à mão em lugar nenhum: a personalidade tem de valer igual em todos os
caminhos, e é ali que se decide o `cache_control`. O canal muda só o formato da resposta —
`telegram` aceita markdown simples, `hud` e `briefing` viram voz (sem markdown, link ou emoji) e
`tarefa` começa pelo resultado.

Monte os blocos **uma vez por pedido** e repita o mesmo objeto em todas as idas e vindas de
ferramenta. O bloco dinâmico carrega a hora, e remontá-lo no meio do turno invalida os blocos de
pensamento que o modelo já devolveu — a API recusa com HTTP 400. Pelo mesmo motivo, o histórico é
guardado sem os blocos de pensamento.

## A tela dirigida pelo Jarvis

Quatro ferramentas abrem e comandam janelas na HUD: `mostrar_na_tela` (card de pesquisa com
ranking, ficha, lista, linha do tempo ou destaque), `tocar_youtube`, `abrir_pagina` e
`controlar_tela` (as 20 ações de `ComandoTela.ACOES`). Valem para pedido vindo da HUD **e** do
Telegram — "mostra na tela o vídeo X" pelo celular abre o card do mesmo jeito, e é o
`CardTela.origem` que registra de onde veio.

O caminho é sempre o mesmo:

```
ferramenta ──► ResultadoFerramenta.cards/comandos ──► Ferramentas publica TelaEvento
                                                            │
                                               EventosHud ──┴──► SSE "tela.card" / "tela.comando"
```

Quem publica é o **registro** `Ferramentas`, logo depois de `executarDetalhado` — não o
`Assistente`, que só processa anexos no fim do turno. O card aparece na tela enquanto o modelo
ainda está escrevendo a fala. Ferramenta de tela, então, só devolve `cards`/`comandos` no
`ResultadoFerramenta`; publicar não é trabalho dela.

Sem HUD conectada (`PresencaDaHud.conectada()` falso) a ferramenta devolve `SEM_TELA` ao modelo e
**não publica nada** — abrir card para ninguém deixaria o Jarvis prometendo o que não aconteceu.
A porta `PresencaDaHud` vive em `dev.jarvis.tela` e é implementada pelo `EventosHud`, porque a
camada web não entra nos serviços.

A HUD conta o que está aberto em `POST /api/tela/estado`, e o `TelaEstadoService` guarda só o
último retrato (validade de 10 minutos). Daí saem até 12 linhas para o bloco **dinâmico** do
system — `[c7k2] vídeo 'Lo-fi para estudar' (tocando)` —, que é como o modelo escolhe um alvo
real em vez de chutar um id. Nunca no bloco fixo: a tela muda a cada pedido e levaria o cache de
prompt embora.

O id da janela é do servidor (`CardTela.novoId()`: `c` mais quatro caracteres sorteados). Não é
sequencial de propósito — o container reinicia com a HUD aberta, e um contador voltaria a `c1`
colidindo com janela que ainda está na tela.

## O PC Windows pelo Telegram

O PC roda um agente (projeto irmão `../jarvis-agente-pc`: Java 21, sem Spring, um jar) que
**busca** comandos na VPS por long polling — `GET /api/pc/comandos` segura a requisição por 25 s.
Nenhuma porta aberta na máquina, nenhum domínio: toda conexão é de dentro para fora, igual ao
`TelegramPoller`.

```
controlar_pc ──► PcService (fila) ──► GET /api/pc/comandos ──► agente no PC
                     ▲                                             │
                     └──────── POST /api/pc/resultado ◄────────────┘
```

- **`PC_TOKEN` é separado do `HUD_TOKEN`**, e quem o exige é o `TokenPcFiltro` — um filtro só
  para `/api/pc`. O `TokenHudFiltro` desiste dessa rota pelo **mesmo predicado**
  (`TokenPcFiltro.rotaDoPc`), para os dois conjuntos serem disjuntos por construção; um
  `startsWith("/api/pc")` solto em cada lado deixaria `/api/pcalgo` sem token nenhum. O
  `?token=` da query, que existe para `EventSource` e `<audio>`, **não vale em `/api/pc`**.
- **O long poll bloqueia a thread da requisição**, sem `DeferredResult`: há um agente só. Nenhum
  método do `PcService` é `synchronized` — segurar monitor por 25 s travaria o `enfileirar`, que
  roda dentro do lock do `Assistente`, e congelaria o bot a cada pedido.
- **A lista de apps liberados mora só no PC.** O agente a reporta no cabeçalho `X-Pc-Apps` de
  cada consulta, higienizada antes de entrar no schema (ela vai para o prompt do modelo). O
  caminho do `.exe` nunca sai do `config.properties`: o modelo escolhe um nome da lista, ou nada.
- **PC offline responde na hora** (`PcService.online()`, 90 s sem contato), antes de enfileirar:
  travar o turno 30 s para no fim dizer "offline" seria o pior dos mundos. Com o PC online, a
  ferramenta espera até 30 s pelo resultado; o comando segue valendo 60 s na fila.
- **`desligar` e `reiniciar` pedem código de 4 dígitos** (`GuardaDeConfirmacao`): uso único,
  2 minutos, amarrado a ação e argumento, e **tentativa errada queima a confirmação** — é isso
  que torna 10.000 possibilidades suficientes. O agente ainda espera 60 s (`shutdown /t 60`),
  então dá para desistir com `cancelar_desligamento`.
- **`captura_tela` vai aos dois destinos**: `sendPhoto` multipart no Telegram (o único POST da Bot
  API que não é JSON, montado pelo `MultipartTelegram`) e um `Artefato` no `ResultadoFerramenta`,
  que o `AnexosService` publica para a HUD. O mime é farejado dos bytes no `PcDto`, nunca aceito
  do agente — um `image/svg+xml` declarado chegaria à tela como imagem, e SVG é scriptável.

## Convenções

- **Tudo em português do Brasil**: nomes de classes, métodos, variáveis, comentários e
  Javadoc (`CriarLembreteFerramenta`, `executarLoop`, `esquemaEntrada`). Só ficam em inglês
  as chaves dos JSONs das APIs (`tool_use`, `max_tokens`, `chat_id`) e o que vem do Spring.
- **HTTP sempre com um `HttpClient` do `BeansConfig`**, recebido no construtor. São dois:
  o `@Primary` (segue redirecionamento), que é o de todo mundo, e o `httpSemRedirecionar`, que
  existe só para o `PaginaService` — o `abrir_pagina` precisa revalidar cada salto de
  `Location:` contra o `GuardaSsrf` antes de segui-lo. Nunca crie um `HttpClient` dentro de uma
  classe; defina o `timeout` por requisição no `HttpRequest`.
- **JSON sempre com o `JsonMapper` do projeto**, também injetado. Para ler campos vindos de
  fora, use `dev.jarvis.util.Json.texto(...)` / `Json.numero(...)`, que devolvem vazio ou
  zero em vez de estourar exceção.
- **Sem SDK pesado quando a API HTTP é simples.** Telegram e Claude são duas chamadas de
  `POST` com JSON; manter isso explícito deixa o código legível e a imagem leve. Uma
  dependência nova precisa de motivo forte.
- **Ferramenta nova = uma classe `@Component` que implementa `Ferramenta`** em
  `dev.jarvis.ferramentas`. O Spring injeta a lista no registro `Ferramentas` e o Claude
  passa a poder usá-la — não há lista manual para atualizar.
- **A `descricao()` é o que faz o modelo escolher certo**, então capriche nela. E devolva
  texto curto: tudo que a ferramenta retorna vira tokens, e tokens custam dinheiro.
- **O formato da resposta depende do canal**, e quem diz isso ao modelo é o `Canal` no bloco
  dinâmico do system: `telegram` aceita markdown simples, `hud` e `briefing` viram áudio (frases
  curtas, sem markdown) e `tarefa` começa pelo resultado. No Telegram, o `enviarMensagem` converte
  o markdown para o HTML da Bot API (`MarkdownTelegram`), escapando `< > &`; se o Telegram recusar
  a formatação (HTTP 400), o mesmo pedaço é reenviado em texto puro.
- **A camada web não entra nos serviços.** O `BriefingService` publica evento de domínio
  (`ApplicationEventPublisher`) e quem traduz para HTTP/SSE é `dev.jarvis.web`. E o DTO manda
  data como string ISO-8601: o `JsonMapper` do `BeansConfig` é cru, então não dependemos de
  como ele serializaria um `ZonedDateTime`.

## Configuração nova

Toda configuração entra nos **três** lugares, na mesma ordem:

1. `JarvisProperties` (record com `@ConfigurationProperties(prefix = "jarvis")`);
2. `src/main/resources/application.yml`, lendo a variável com default: `${MINHA_VAR:valor}`;
3. `.env.example`, com um comentário curto explicando para que serve.

Nada de `@Value` espalhado pelo código, e nada de ler `System.getenv` direto. O último exemplo é
o `PC_TOKEN` (`jarvis.pc.token`), usado pelo `TokenPcFiltro`: vazio, `/api/pc` recusa tudo com 401
e o controle do PC fica desligado.

Atenção ao `JarvisProperties`: é um record construído por posição em vários testes, então
acrescente componente **no fim** e atualize essas chamadas.

## Segurança

- **Não existe ferramenta de "executar comando qualquer" — e não crie uma.** Cada ferramenta
  faz **uma** coisa específica e limitada. `verificar_site` é o modelo a seguir: o schema
  expõe um `enum` com os nomes de `SITES_MONITORADOS`, então o modelo escolhe pelo nome e
  nunca passa uma URL livre.
- **Nunca logar token nem chave.** Isso inclui a URL da Bot API, que embute o token do bot:
  o `TelegramClient` loga só o nome do método e o motivo do erro. Também não registre o
  corpo de requisições que levem credenciais.
- O bot **só atende ao `TELEGRAM_USUARIO_PERMITIDO`**; mensagem de outro ID é ignorada e só
  o ID aparece no log.
- Para ações perigosas (apagar, reiniciar, pagar), peça confirmação antes de executar.
- **A VPS só busca URL que ela provou ser pública.** O `abrir_pagina` é a única ferramenta que
  acessa um endereço ditado pelo modelo, e ele passa pelo `GuardaSsrf`: só http/https, DNS
  resolvido e conferido contra loopback, faixas privadas, link-local (inclusive o
  `169.254.169.254` dos metadados de nuvem) e os endereços das interfaces da máquina; no máximo
  3 redirecionamentos, cada um revalidado; 8 s, 1 MB, e nenhum cookie ou cabeçalho de
  autenticação. Resta a janela de DNS rebinding, porque o `HttpClient` do Java não tem gancho de
  resolução — por isso da página só voltam quatro metadados `og:`, nunca o HTML cru.
- As URLs das fontes do `mostrar_na_tela` também são validadas (`MontadorDeCardDePesquisa`):
  elas viram link clicável na HUD, e um `javascript:` ali seria script rodando na tela.
- **O agente do PC nunca monta linha de shell.** Toda execução é `ProcessBuilder` com argumentos
  fixos, e onde o PowerShell é inevitável (teclas de mídia e bateria) o `-Command` é uma
  constante escolhida por `switch`. Concatenar ali o que a VPS mandou seria injeção de comando —
  e a VPS manda o que um modelo de linguagem escreveu.
- **O agente revalida o que a VPS validou** (https do `abrir_url`, app na lista). Os dois lados
  conferem de propósito: é o processo no PC que tem poder na máquina, e confiar na checagem do
  outro lado transforma um bug de lá num problema aqui. O `abrir_url` **não** passa pelo
  `GuardaSsrf`: ele protege a VPS de buscar endereço interno, mas aqui a página abre no navegador
  do usuário, onde `192.168.0.1` é o roteador da casa. O risco real é esquema que executa código,
  e o controle é a lista branca de esquema.
- O `.env` tem segredos e está no `.gitignore` e no `.dockerignore`. Mantenha assim.
- A `config/personalidade.md` é versionada (o repositório é privado), mas **não é lugar de segredo**:
  token, chave e senha ficam só no `.env`. Ela fica fora da imagem e chega ao container por volume
  somente leitura (`./config:/app/config:ro`) — é isso que permite editá-la sem rebuild.

## Como validar

```bash
mvn -q -B package
```

Requer Maven instalado (o projeto não tem wrapper `mvnw`). Compila, roda os testes de
`src/test` (JUnit 5) e empacota — é a checagem mínima de que a mudança está de pé. Teste
isolado: `mvn -B test -Dtest=NomeDoTeste`.

## Como subir

```bash
cp .env.example .env            # na primeira vez; preencha token, chave e seu ID
docker compose up -d --build
docker compose logs -f          # Ctrl+C para sair
```

Mudou o `.env` ou o código? Rode `docker compose up -d --build` de novo. Para rodar no PC sem
Docker, exporte as mesmas variáveis do `.env` e use `mvn spring-boot:run`.

## Limitações conhecidas

- **A fila de comandos do PC também é em memória**, com o código de confirmação: depois de um
  restart o Jarvis espera o agente reaparecer (até 25 s) e qualquer confirmação pendente se perde.
- **Lembretes e histórico ficam em memória**: somem quando o container reinicia. O cache de
  voz, não: ele vive no volume `/data/voz` e sobrevive ao restart.
- O retrato da tela e o cache de buscas no YouTube também são em memória: depois de um restart o
  Jarvis fica cego para a tela até a HUD repostar o estado.
- Só entende texto (nada de áudio ou foto).
- Atende **uma mensagem por vez** — `Assistente.responder` é `synchronized`.
