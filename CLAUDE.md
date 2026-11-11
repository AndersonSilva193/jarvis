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
- **São dois breakpoints de cache, dos quatro que a API permite**: o explícito do bloco fixo e o
  **automático da conversa**, um `cache_control` na chave de topo do pedido que a API coloca no
  último bloco cacheável e avança sozinha. Quem o liga são `CONVERSA`, `TAREFA` e `GATILHO` no `ClaudeClient`,
  porque esses caminhos podem repetir o laço de ferramentas com o mesmo prefixo. Os dois levam o mesmo TTL (`ANTHROPIC_CACHE_TTL`) de
  propósito: entrada de TTL mais longo tem de aparecer antes da de TTL mais curto, e misturar é 400.
- **O esforço de saída vai no mesmo pedido** (`output_config.effort`) e é **decidido pela origem**,
  nunca pedido a pedido: conversa e tarefa pensam em `medium`, briefing, introdução e apoio em `low`.
  Quem resolve é o `ClaudeClient`, uma vez no construtor — nível que mudasse no meio de uma conversa
  invalidaria o cache de prompt que o passo anterior acabou de escrever.
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
| `dev.jarvis.telegram` | `TelegramClient` (Bot API) e `TelegramPoller` (leitura + filtro de usuário numa thread, fila e um trabalhador na outra) |
| `dev.jarvis.ia` | `Assistente` (histórico, aprovações e anexos), `CicloDeFerramentas` (o laço de ferramentas de um pedido), `ClaudeClient`, `CompactadorDeHistorico` (encolhe o turno que saiu da janela de turnos completos), `PersonalidadeService` (lê e recarrega o arquivo), `SistemaDoJarvis` (monta o `system` de qualquer chamada), `PromptDeSistema`, `Canal`, `ComandoDeRepouso`, `PedidoDeParada` (a frase curta que corta o pedido em andamento), `TrocaDeModelo` ("modo local", "qual modelo você está usando?" e o "pergunta pro Claude:" pontual), `ErroDaApiDoModelo` e `BuscaWebServidor` (a definição da busca na web, que a Anthropic executa) |
| `dev.jarvis.ia.provedor` | `ProvedorDeModelo` (como conversar com um modelo, sem dizer qual), o formato neutro (`PedidoAoModelo`, `Mensagem`, `Bloco`, `RespostaDoModelo`, `OpcoesDoPedido`, `InstrucoesDeSistema`, `ListaDeFerramentas`), `ProvedorAnthropic`/`ConversorAnthropic`, `FormatoNeutro` (o histórico em disco) e a IA local: `ProvedorLocal`/`ConversorOllama` (o Ollama do PC pelo `inferir`), `DisponibilidadeLocal` (o `X-Pc-Ia` lido), `ProvedorDeApoio` (local primeiro, Claude de reserva), `FalhaLocal` e o `SeletorDeModelo` (o modo AUTO/LOCAL/CLAUDE, `ModoDeIa`, `ModoDeIaEvento`) |
| `dev.jarvis.ferramentas` | A interface `Ferramenta`, o registro `Ferramentas` e cada ferramenta |
| `dev.jarvis.briefing` | `BriefingService` (junta as fontes e monta o texto do dia) e `NarradorDeBriefing` (lê o briefing pronto com a personalidade) |
| `dev.jarvis.inicializacao` | `InicializacaoService` e `Introducao`: a abertura falada da HUD quando a tela acende |
| `dev.jarvis.briefing.fontes` | As fontes: `ClimaService`, `NoticiasService`, `SitesService`, `ServidorService` |
| `dev.jarvis.lembretes` | `LembreteService` (agenda, dispara e reagenda na partida), `LembretesEmArquivo` (o JSON com troca atômica e `.bak`), `Recorrencia`/`Repeticao` |
| `dev.jarvis.agenda` | `ResumoDiario`: tarefa `@Scheduled` do bom dia |
| `dev.jarvis.memoria` | `MemoriaService` (registros Markdown por usuário, nos volumes `memoria` e `memoria-exclusoes`), `AnotadorDeMemoria` (o que o Jarvis anota sozinho), `ProcedimentosService`/`RevisorDeProcedimentos` (o que ele aprende fazendo, em `MEMORIA_PASTA/procedimentos`) e `EsquecimentosDeMemoria` (o recado de que um esquecimento aprovado aconteceu) |
| `dev.jarvis.conversa` | Execução assíncrona da HUD (`ConversaService`), retratos públicos do histórico (`RegistroConversas`), `HistoricoEmArquivo` (o JSON por chat), progresso, `Confirmacao`, `EscutaResposta` e `AnexosService` |
| `dev.jarvis.computer` | O computer use: `TarefasComputerUse`, o `DetectorDeTravamento` (encerra a tarefa que anda em círculos) e os provedores `ProvedorAnthropicComputerUse` / `ProvedorOpenAiComputerUse` por trás de `ProvedorComputerUse` |
| `dev.jarvis.holograma` | O modo Holograma: `HologramaLineParser`/`HologramaValidator`/`MontagemDeHolograma` (NDJSON → peças), `HologramaService` (a geração com stream, para o SSE ou sem cliente pelo `DestinoDaGeracao`), o controle por conversa (`EstadoDoHologramaService`, `ResolvedorDeAlvo`, `ComandoDoHolograma`, `IntentoDoHolograma`/`AtalhoDoHolograma`, `FalasDoHolograma`), `GaleriaDeHologramas` (os objetos completos em disco, a chave do cache e as miniaturas), `BenchmarkDeHolograma`/`MedidaDaGeracao` (os dez objetos de referência por gerador, relatório em `HOLOGRAMA_PASTA/benchmark`), `PromptDeHolograma`, `BufferDeLinhas`, `DetalheHolograma` e os exemplos. Contrato em `docs/HOLOGRAMA.md` |
| `dev.jarvis.google` | `GooglePlanilhasService`: OAuth e criação de planilha nativa |
| `dev.jarvis.escritorio` | `AgendaService` (os compromissos do dia) e `LeadsService` (os leads da prospecção), lidos de planilhas na pasta do Jarvis no PC pelo `PlanilhaDoPc` |
| `dev.jarvis.busca` | A busca configurável: `ProvedorDeBusca` e os três provedores (`TavilyBusca`, `SearxngBusca`, `AnthropicBusca`), `BuscaNaWeb` (ordem, reserva, filtro https e cache), `CotaDeBusca` (cota mensal do Tavily) e `UrlsDaBusca` (o que a `ler_pagina_web` pode abrir) |
| `dev.jarvis.config` | `BeansConfig` (beans compartilhados) e `JarvisProperties` (toda a config) |
| `dev.jarvis.custo` | `MedidorDeCusto` (soma por origem e modelo, alertas), `TabelaDePrecos` (faixas configuradas) e `CustoDiarioEmArquivo` (JSON diário no volume `custo`) |
| `dev.jarvis.agente` | `Freio` (pausa persistente e teto autônomo), `ComandoDoFreio` e `ControleDoFreio` (atalhos nas duas entradas, antes do modelo e do lock) e `RetratoDoMomento` (o "Agora" do bloco dinâmico) |
| `dev.jarvis.diario` | `DiarioDeAcoes` (o que o Jarvis fez, JSON por dia no volume `diario`), `DiarioEmArquivo`, `DiaDoDiario`, `AcaoRegistrada` e os enums `TipoDeAcao`/`OrigemDaAcao` |
| `dev.jarvis.proativo` | O Jarvis falando primeiro: `AvisosProativos` (o único caminho do aviso não pedido), `MonitorDeSites`, `FalasService` (o catálogo das frases, com marcadores), `JanelaDeSilencio` e `HistoricoDeDisponibilidade`/`DisponibilidadeEmArquivo`/`DiaDeDisponibilidade` (o JSON por dia no volume `proativo`) |
| `dev.jarvis.gatilhos` | As regras "quando X, faça Y": `FonteDeGatilho` (o catálogo fechado), `EventoDeGatilho`, `Filtro`, `RegraDeGatilho`, `Gatilhos`/`GatilhosEmArquivo` (o `gatilhos.json` e a migração do `SITES_ACOES`), `MotorDeGatilhos` (evento → tarefa de fundo), `RelogioDosGatilhos` (o horário), `VigiaDaEntrada` (a pasta `entrada` do PC) e `DadoDoEvento` (o evento como DADO) |
| `dev.jarvis.voz` | A voz da HUD: `ProvedorVoz`/`ElevenLabsVoz`, `CacheDeVoz` (MP3 em disco), `MonitorDeCotaVoz` (reserva, aviso antecipado e volta ao navegador), `DiarioDeVoz` (o uso por dia, de onde sai a projeção), `TextoParaVoz` e `VozService` |
| `dev.jarvis.tela` | A tela dirigida pelo Jarvis: `CardTela`/`DadosDoCard`/`ComandoTela`, `TelaEvento`, `EstadoDaTela`/`TelaEstadoService` (o retrato da HUD), `MontadorDeCardDePesquisa`, `YoutubeService`, `PaginaService`, `GuardaSsrf`, `FalaDaTela`/`FalaDaTelaService` (o catálogo fechado de frases) e a porta `PresencaDaHud` |
| `dev.jarvis.pc` | O PC Windows: `PcService` e `OperacoesPcService` (as filas de comando), `CatalogoDoPc` (os apps e as rotinas que o PC anunciou, em disco), `ComandoPc`/`ResultadoPc`, `ArquivosPcService` e `FluxoEdicaoPc` (arquivos e edição), `TarefasPlanilhaService` e `AvisosOperacoesPc` |
| `dev.jarvis.autonomia` | `PoliticaDeAutonomia` (lê `config/autonomia.md` e decide, para cada ação do catálogo, faz e avisa / pergunta / proibido), `AcaoAutonoma` (o catálogo fechado, com padrão e piso), `Nivel`, `Concessoes`/`Concessao` (as permissões que o dono deu, em `DIARIO_PASTA/concessoes.json`) e `ClassificadorDeConcessao` (a segunda leitura de "pode fazer X sem me perguntar") |
| `dev.jarvis.aprovacao` | `Aprovacoes`: a aprovação humana de uso único por onde passa **toda** confirmação, com o nível `Risco` que define o prazo — e o único ponto onde a ação aprovada roda, logo o único que a anota no diário |
| `dev.jarvis.financas` | `FinancasService` (o livro de gastos e ganhos em `DIARIO_PASTA/financas.json`, em centavos), `Lancamento` e `PeriodoFinanceiro` |
| `dev.jarvis.mcp` | O servidor MCP do escritório: `ServidorMcp` (o JSON-RPC à mão), `FerramentaMcp`/`ResultadoMcp` e as ferramentas que os agentes do Hermes podem chamar |
| `dev.jarvis.web` | API da HUD, do agente do PC e do MCP: controllers, SSE (`EventosHud`), os três filtros de token e CORS |
| `dev.jarvis.util` | `Json` (leitura defensiva de campos que podem não existir, e `canonico` para comparar dois JSONs), `ComparadorDeToken` (comparação de segredo em tempo constante), `ArquivoAtomico` (gravação com troca atômica e `.bak`), `ValidadorDeEsquema` (o subconjunto de JSON Schema que os `esquemaEntrada()` usam) e `Texto` |

### A mesma ferramenta, a mesma entrada, de novo

O `CicloDeFerramentas` conta as chamadas por **assinatura** — nome da ferramenta mais `Json.canonico` da
entrada, porque o `JsonMapper` é cru e `{"a":1,"b":2}` e `{"b":2,"a":1}` são o mesmo pedido. A
**terceira** idêntica no mesmo turno é recusada sem executar, com `tool_result` de `is_error`
dizendo ao modelo que use o resultado que já tem. São **cinco** para as `status_*` e para
`controlar_tela`/`controlar_pc`: "já terminou?" e quatro passos de volume são repetição de
propósito, não laço. **Dois bloqueios encerram o turno** com "Parei porque estava repetindo a mesma
consulta sem avançar." mais o último resultado útil.

O portão vem **depois** dos dois que já existiam — a lista branca da tarefa de planilha e o
`criar_arquivo` barrado por `falhaEdicao`. Quando dois se aplicam, a recusa específica diz ao modelo
algo que ele pode usar; "você repetiu" não. Pelo mesmo motivo o tratamento de `criar_arquivo`
continua lá: ele para na **segunda** tentativa e devolve o erro real ("Nome inválido…") em vez da
frase genérica.

### A lista de ferramentas tem de ser a mesma de ontem

As definições vão no array `tools`, que é renderizado **antes** do `system`. Um byte diferente ali
invalida, de uma vez, o cache de `tools`, o do `system` (a personalidade) e o das `messages` — e é a
única invalidação, junto com a troca de modelo, sem escapatória nenhuma. Três regras seguram isso:

- **Ordem alfabética.** O `porNome` do registro é um `TreeMap`. Era um `LinkedHashMap` na ordem em
  que o Spring injetou a lista, que é a da varredura do classpath: determinística para um artefato,
  **mas não contratual**, e podendo diferir entre `target/classes` e o jar. Ordem diferente é
  prefixo diferente, ou seja, cache perdido num deploy que não mudou código.
- **Nada de estado volátil na `descricao()` nem no `esquemaEntrada()`.** Quem precisa de estado usa
  o que é **de partida**, não o que oscila: a `tarefa_no_pc` olha `TarefasComputerUse.configurado()`
  e não `disponivel()` (este embute o `online()`, uma janela de 90 s — era uma invalidação por
  batida de coração perdida), e a `criar_planilha_google` olha `google.configurado()` e não
  `autorizado()` (que faz um `Files.isRegularFile`). Com o PC fora do ar ou sem autorização, quem
  recusa é a **execução** — é o desenho que a `rodar_rotina` já tinha.
- **O catálogo do PC vive em disco.** Os apps e as rotinas que o agente anuncia moram no
  `CatalogoDoPc`, num `catalogo.json` ao lado do `operacoes.json` (volume `pc-operacoes`, sem
  configuração nova — pasta derivada, como no `CacheDeVoz`). A regra é uma: **a lista muda quando o
  PC anuncia uma lista diferente.** Ficar offline não muda nada, anúncio ausente não muda nada, e
  anúncio igual nem grava. Anúncio **explicitamente vazio** muda, sim — é a assimetria que o
  `PcService.registrarApps` sempre documentou: cabeçalho ausente é falta de informação, cabeçalho
  vazio é informação. A higienização continua onde estava; o catálogo só guarda.

Mesmo com as três, o registro **confere e avisa**: o `definicoes(canal, tarefaPlanilha)` guarda uma
impressão digital por ferramenta (`Json.canonico` da definição) e solta um WARN nomeando o que
mudou. A chave inclui o `tarefaPlanilha`, senão cada tarefa de planilha — que reduz a lista a cinco
ferramentas — gritaria "removidas" e depois "acrescentadas"; são dois prefixos legítimos por canal,
não uma mudança. A primeira montagem de cada chave só registra. Dentro de uma chave o **conjunto de
nomes é fixo** (os dois filtros dependem só de canal e `tarefaPlanilha`, e os dois estão na chave),
então hoje só o ramo `alterada(s)` pode disparar; o de `+nome`/`-nome` existe para o dia em que
algum filtro passar a depender de estado, que é exatamente o bug que este aviso caça.

## O provedor de modelo

**Chamador novo fala com `ProvedorDeModelo`, nunca com `ClaudeClient`.** Hoje são oito:
`CicloDeFerramentas`, `NarradorDeBriefing`, `InicializacaoService`, `ClassificadorDeResposta`,
`ClassificadorDeConcessao`, `AnotadorDeMemoria`, `RevisorDeProcedimentos` e `AnthropicBusca` (esta pede o `@Primary`: busca
nativa é da Anthropic por construção). O `HologramaService` é o nono, e o único que transmite:
pede o `ProvedorAnthropic` e o `ProvedorLocal` pelo nome e escolhe entre eles (ver "O holograma").
O pedido e a resposta estão no formato neutro de `dev.jarvis.ia.provedor`, e
quem traduz é o `ProvedorAnthropic`. Ele é **só tradução**: repetição, cache da conversa, TTL,
esforço e custo continuam no `ClaudeClient`, que não mudou. O computer use segue com os provedores
próprios dele.

Um `Bloco` é `Texto`, `Chamada`, `Resultado` ou `Opaco`. Os três primeiros são o contrato de todo
modelo com ferramentas. O resto (busca na web, pensamento assinado, busca de ferramentas) vai num
`Opaco` marcado com o provedor, que nenhum outro recebe. Três decisões mantêm o corpo enviado
**byte a byte igual** ao de antes, que é o que o cache de prompt cobra:

- **Bloco que veio da API volta cru.** `Texto` e `Chamada` guardam o `original`, então
  `citations` e campo novo de `tool_use` atravessam. Texto nascido em Java não tem original, e
  mensagem de um texto só sem original sai com `content` em **string**.
- **O mesmo objeto neutro vira o mesmo `ArrayNode`.** `InstrucoesDeSistema` e
  `ListaDeFerramentas` guardam a forma que cada provedor fez delas. O system é lido do
  `SistemaDoJarvis.blocos(...)` por `SistemaDoJarvis.instrucoes(...)`, e a Anthropic recebe de
  volta o próprio objeto que saiu de lá.
- **`Opaco.soNoTurno`** marca o pensamento. O `semPensamentos` tira "o que vale só no turno" sem
  conhecer tipo de bloco, e o compactador lê a busca na web pela `consultaWeb`.

A sobrecarga do `ClaudeClient` é escolhida pelas `OpcoesDoPedido`, uma por tipo de chamada:
`curta(origem, teto, prazo)` → a de tentativa única; `de(origem, perfil)` → a de perfil explícito
(o laço); `de(origem)` → a que deriva o perfil da origem.

**O histórico está no formato neutro**, na memória e no disco
(`{"papel":"usuario","blocos":[{"tipo":"texto",...}]}`). O `HistoricoEmArquivo` não conhece
formato. O `FormatoNeutro` lê também o `role`/`content` gravado antes, e o arquivo antigo é
regravado neutro no fim do turno seguinte. O `semImagens` percorre o JSON inteiro, em vez de uma
lista de chaves, para valer nas duas formas.

Os construtores que recebem `ClaudeClient` continuam e embrulham num `ProvedorAnthropic.sobre(...)`,
que devolve `null` sem cliente (o "sem modelo" do `ClassificadorDeResposta.semModelo()`). O
`@Autowired` mora no construtor que recebe `ProvedorDeModelo`. São três implementações: o
`ProvedorAnthropic` é o **`@Primary`** (conversa e laço de ferramentas), e os cinco chamadores de
apoio pedem o `ProvedorDeApoio` com `@Qualifier(ProvedorDeApoio.QUALIFICADOR)` — ver a seção
seguinte. Provedor novo implementa a interface e decide onde entra entre esses dois.

## A IA local nas tarefas de apoio

O agente do PC sabe a ação `inferir` (um `/api/chat` no Ollama de lá) e manda o estado dela no
cabeçalho `X-Pc-Ia` de toda consulta. A VPS usa isso nas cinco tarefas de apoio —
`ClassificadorDeResposta`, `AnotadorDeMemoria`, `RevisorDeProcedimentos`, `NarradorDeBriefing` e
`InicializacaoService` —,
sempre com o Claude de reserva. Desligado (`IA_LOCAL_ATIVA=false`, o padrão), nada muda: o
`ProvedorDeApoio` vai direto ao Claude e o corpo enviado é o de sempre.

```
chamador ──► ProvedorDeApoio ──► DisponibilidadeLocal ── não ──► reserva(motivo) ──► Claude
                  │ pode                                                 ▲
                  ▼                                                      │
             ProvedorLocal ──► PcService.inferir ──► E2 ──► agente ──► Ollama
                  │ FalhaLocal(motivo) / validador reprovou ─────────────┘
```

- **O validador é o que já existia, passado como predicado** (`responder(pedido, aceita)`, um
  `default` da interface que provedor único ignora). Classificador: o JSON tem uma decisão legível
  — `"duvida"` escrito é decisão e vale; texto sem decisão reprova. Anotador: o JSON tem a lista
  `candidatos` (não há mais prova de citação). Revisor de procedimentos: o JSON tem a lista
  `acoes`; ele usa a `Origem.ANOTADOR` e o mesmo balde de custo. Briefing: as marcas do `separar`. Introdução:
  texto não vazio. Quem chama continua conferindo o que volta do Claude como sempre conferiu.
- **A disponibilidade é decidida antes de mandar**, na ordem `pc_offline` (`PcService.online()`),
  `ollama_offline` (sem `X-Pc-Ia` dos últimos 90 s, `ativo=0` ou `ollama=0`), `gpu_ocupada` e
  `sem_vram`. Este último só reprova o modelo **fora** da memória: com `carregado=1` a VRAM livre é
  baixa por causa dele mesmo. Sem leitura da GPU (`gpu=0`) só passa o que já está carregado.
- **O prazo é de quem espera**: o classificador usa o próprio (5 s), a introdução 10 s
  (`ProvedorLocal.PRAZO_COM_ALGUEM_ESPERANDO`), anotador e briefing o `IA_LOCAL_TIMEOUT_SEGUNDOS`.
  Nunca acima deste. Estourou, o `inferir` é cancelado na fila e o Claude assume — no pior caso o
  classificador leva os 5 s do local mais os 5 do Claude. **A exceção é o stream** (`transmitir`,
  só o holograma usa): ali o `IA_LOCAL_TIMEOUT_SEGUNDOS` vira prazo de *inatividade* — sem texto
  novo nesse tempo, cai —, e o total é o de quem transmite. Uma geração longa que está escrevendo
  não é um PC travado.
- **Erro de formato nunca vira execução.** Toda chamada de ferramenta do local é conferida contra
  a lista do pedido e o esquema (`ValidadorDeEsquema`): nome que não foi oferecido, argumento que
  não é JSON ou fora do esquema volta ao próprio modelo como mensagem `tool` de erro, numa volta de
  correção (`CORRECOES = 1`), e cada uma soma em `errosDeFormato` no medidor — é o número que o
  escalonamento lê. Persistindo, `FalhaLocal("erro_de_formato")` e reserva.
- **O conversor perde de propósito**: `Opaco` e o `original` de texto e chamada não vão ao Ollama,
  o system vira a primeira mensagem, o resultado vira `{"role":"tool","tool_name":...}` com o erro
  escrito no conteúdo (o Ollama não tem `is_error`), e `think` só é `true` na `CONVERSA`.

**No medidor o local tem balde próprio** (`EstadoDoDia.locais`, `"local"` no JSON do dia, custo
zero), e não uma linha em `porModelo`: o relatório falado soma dólares dali, e o `Cache: X% lido.`
divide pelo prompt inteiro — tokens sem cache do local baixariam a taxa do Claude sem o Claude ter
mudado nada. As reservas são contadas por motivo (`reservas`), e os três campos só entram no arquivo
quando existem. Leitura em `MedidorDeCusto.localDeHoje()` e numa linha de log ao lado da saída média.

### O seletor de modelo

O `ProvedorDeApoio` não decide mais sozinho: ele pergunta ao `SeletorDeModelo.escolher(origem)`, que
olha o **modo** que o dono escolheu.

| Modo | Local disponível | Local indisponível |
|---|---|---|
| `AUTO` (padrão) | local | Claude, calado |
| `LOCAL` | local | Claude, com **um** aviso por período de indisponibilidade |
| `CLAUDE` | Claude | Claude |

- **Acima do modo há duas coisas.** A chave mestra (`IA_LOCAL_ATIVA`/`IA_LOCAL_APOIO`): desligada,
  tudo vai ao Claude em qualquer modo, com motivo `ia_local_desligada` e sem aviso — é o "nada muda"
  de sempre. E o pedido pontual: "pergunta pro Claude: ..." ou "usa o Claude pra isso: ..." abre
  `claudeNestePedido()`, um `ThreadLocal` que vale até o turno acabar. O turno inteiro roda numa
  thread só (o lock do `Assistente`), então o classificador do turno — e, quando a conversa entrar
  no seletor, o laço de ferramentas — herdam sem fiação nova. O prefixo sai do texto, o modo não muda.
- **Reserva só conta quando o local foi pedido e não pôde** (`Escolha.indisponivel()`, os quatro
  motivos da `DisponibilidadeLocal`). Modo `CLAUDE`, chave mestra e pontual vão direto: nada foi
  tentado.
- **O aviso do LOCAL sai pelo `AvisosProativos`** (`ia:local`, estado `indisponivel:<início do
  período>`, frase `ia_local_indisponivel`), e só quando uma chamada **de fato** caiu no Claude — é aí
  que a frase é verdade. O período fecha quando o local volta. Quem troca para LOCAL com o PC fora já
  ouviu isso na resposta da troca, e o período conta como avisado.
- **O efetivo** (provedor, modelo e motivo) é o das tarefas de apoio, e vai à HUD no SSE `ia.modo`
  sempre que muda: troca de modo, entrada e saída de fallback. Um tique de 30 s reavalia sem ninguém
  chamar. `GET`/`POST /api/ia/modo` devolvem a mesma forma do evento. O nome do modelo local é o
  `modelo=` do `X-Pc-Ia`, higienizado (`DisponibilidadeLocal.modeloAnunciado()`): vai ao dono, nunca
  ao prompt.
- **As frases são do servidor** (`TrocaDeModelo`, irmã do `PedidoDeParada`): até 6 palavras sem
  contar "Jarvis", conjunto fechado de verbos e enfeites, um modo só. Vêm depois do
  `aprovacoes.tratar` e **antes** do classificador, então não gastam modelo e a pendência em aberto
  continua esperando. "Qual modelo você está usando?" diz o modo, quem pensa no apoio e que a
  conversa continua no Claude — afirmar o contrário seria o servidor mentindo sobre si.
- **Cada troca entra no diário** (`TipoDeAcao.TROCA_DE_MODELO`, não autônoma: o dono pediu), venha
  da frase ou da HUD. Troca para o mesmo modo não faz nada.
- **O modo vive em `modo-ia.json`, ao lado do `operacoes.json`** (volume `pc-operacoes`, pasta
  derivada como a do `CatalogoDoPc`). O `IA_MODO_PADRAO` é só o da primeira partida.
- Busca na web e computer use **não passam pelo seletor**: são da Anthropic por construção, e um
  `Origem.COMPUTER_USE` que chegar recebe `so_anthropic`. Conversa, tarefas e rotinas entram no
  seletor na 9.7; até lá o `ProvedorAnthropic` continua o `@Primary` delas.

**O `inferir` não vai ao `operacoes.json`.** O `dados` é o prompt, com mensagens do dono, e ninguém
espera por ele depois de um restart; o resultado de um id que sumiu recebe 404, que o agente trata
como definitivo. Retenção de 5 minutos, como o `computer_acao`. No `PcController` ele aceita
`dados` também em `falhou` (é lá que vem o `codigoErro`) e até `PcDto.MAX_DADOS_INFERENCIA`
(256 KiB, o padrão do agente) em vez dos 32 KB das consultas. Fica fora do enum da `controlar_pc`
(`ComandoPc.INFERENCIA`): o modelo não escolhe falar com outro modelo.

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

Cinco ferramentas abrem e comandam janelas na HUD: `mostrar_na_tela` (card de pesquisa com
ranking, ficha, lista, linha do tempo, destaque ou gráfico — este último é o único sem `itens`:
o conteúdo vai no campo irmão `dados.grafico`, em um dos quatro desenhos, e só com número
pesquisado, porque `origem: "servidor"` é marcada pelo Java), `grafico_de_dados` (o gráfico de
número da casa),
`tocar_youtube`, `abrir_pagina` e
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

### Gráfico: quem escolhe e quem preenche

O layout `grafico` tem duas entradas, e a diferença entre elas é de onde vêm os números:

| Ferramenta | Números | `grafico.origem` | Exige |
|---|---|---|---|
| `mostrar_na_tela` com `layout: "grafico"` | o modelo digita, de uma pesquisa | `pesquisa` | ao menos uma fonte e a `referencia` |
| `grafico_de_dados` | o Java preenche, da fonte escolhida | `servidor` | nada além do título e do resumo |

A separação existe porque gráfico é a forma mais convincente de errar: um valor trocado numa série
de sete pontos não parece engano, parece tendência. Então no `grafico_de_dados` o **schema não tem**
`eixoX`, `series`, `valores` nem `origem` — o modelo escolhe a fonte, o tipo de desenho e escreve a
conclusão no título, e não tem onde escrever um número. O `MontadorDeCardDePesquisa` recusa
`origem: "servidor"` vindo do JSON do modelo; quem a marca é o `MontadorDeCardDeDados`, que valida
listas montadas em Java contra os mesmos limites da HUD e não cobra fonte nem referência — o dado é da casa e o `consultadoEm` é o relógio do servidor. Até a
frase que volta ao modelo (`"Máxima de 28 °C e mínima de 12 °C na semana."`) é montada em Java, para
nem a fala depender de ele ter lido certo um número que não viu.

**Fonte nova toca dois lugares**: uma entrada em `GraficoDeDadosFerramenta.FONTES` e um `case` no
`executarDetalhado`. Hoje são `clima_7_dias`, `planilha` e `custo_30_dias`.

### Os quatro desenhos, e o que cada um proíbe

| `tipo` | Eixo | Séries | Para |
|---|---|---|---|
| `linha` | 2 a 24 pontos | até 3 | o que anda no tempo |
| `barras` | 1 a 12 categorias | até 3 | comparar categorias |
| `barras_empilhadas` | 1 a 12 categorias | até 4 partes | de que cada período é feito |
| `rosca` | 2 a 6 fatias | **uma** | de que o todo é feito agora |

As três regras por tipo moram em **um** lugar — `MontadorDeCardDePesquisa.limiteDoEixo`,
`maxSeries` e `conferirRegrasDoTipo` —, e os dois montadores mais o `grafico_de_dados` as
consultam. Antes eram quatro cópias de `"linha".equals(tipo) ? ... : ...` em três arquivos, e é
exatamente o lugar onde a validação do JSON do modelo e a das listas do Java iam divergir.

Quem empilha **não aceita valor negativo**: segmentos de sinais trocados não compartilham a base, e
o tamanho que o olho mede deixa de ser o total. A rosca ainda exige **soma maior que zero** — sem
total não há fatia. Barra empilhada somando zero, ao contrário, passa: é um período legítimo sem
movimento. E o teto do campo `tipo` no JSON do modelo é 20, não 10: com 10, `barras_empilhadas`
(17 caracteres) receberia "acima de 10 caracteres" em vez da lista de tipos, que é a única frase
que ajuda o modelo a se corrigir.

A `rosca` **não ganhou campo**: ela é o mesmo `eixoX` + `series` do resto, com os rótulos das
fatias no eixo e uma série só. Campo novo mudaria o contrato JSON e os construtores de todos os
testes de tela para não dizer nada que o par já não diga.

*(A HUD ainda desenha só `linha` e `barras` — `grafico-hud.modelo.ts:desenhar()` e o tipo `Grafico`
em `nucleo/tela.modelos.ts`. Até ela acompanhar, a rosca aparece como linha e a empilhada como
barras lado a lado, o que subestima o total na vista; o JSON já está certo.)*

### `custo_30_dias`: seis barras de cinco dias

`MedidorDeCusto.serieDiaria(30)` dá até 30 pontos e a barra empilhada cabe 12, então o Java soma os
dias em blocos de cinco — seis barras, rótulo `19-23/10`, o mês inteiro à vista. Bloco sem nenhum
dia registrado **sai do eixo**, pela mesma regra do `serieDiaria`: zero ali desenharia "não gastei
nada" onde a verdade é "eu estava desligado"; mês sem gasto nenhum responde em texto, sem card.

Para isso o `DiaDeCusto` ganhou `porOrigem` — o dado já existia por dia no `EstadoDoDia` e no JSON
do volume `custo`, e era a série que o descartava. São seis origens e quatro partes, então as
origens vão ordenadas pelo total: até quatro entram uma a uma, e acima disso ficam as três maiores
mais `outras`, que é a soma do resto. **Quem corta é o Java** — deixar o modelo escolher qual
origem desaparece seria deixá-lo escolher o que o dono não vê. O `tipo` não entra no schema desta
fonte: o total do mês é a pergunta errada ("gastei US$ 1,20" não diz o que mexer), e é a composição
por origem que responde. Por isso o campo `tipo` do schema anuncia só `linha` e `barras`
(`TIPOS_DA_PLANILHA`): rosca e empilhada pedem dado de composição, que uma coluna qualquer de um
XLSX não é.

O `clima_7_dias` abre **dois cards**, e é de propósito: `DadosDoCard.Grafico` tem um `tipo` e uma
`unidade` só, então temperatura (°C, linha, máxima e mínima) e chance de chuva (%, barras) não
cabem no mesmo card sem compartilhar escala — 30% na escala de 22 °C é exatamente o gráfico que
mente. Jogar a chuva na fala e deixar só a temperatura na tela tiraria de vista o número que decide
levar guarda-chuva. Não há encanamento novo: `ResultadoFerramenta.cards()` já é lista e o
`Ferramentas.publicarNaTela` já a percorre. A chuva só vira card quando **todos** os dias trouxeram
a chance, e um dia sem temperatura corta a série ali — `Grafico.Serie` exige `Double.isFinite`, e
linha com buraco não é representável. Os sete dias vêm de `ClimaService.previsaoDias(7)`, **uma**
chamada à Open-Meteo, com cache no mesmo mapa por data do `previsao(LocalDate)`: os dois caminhos
guardam a mesma coisa, um dia de previsão, e se aproveitam um do outro.

A fonte `planilha` lê pelo `ler_arquivo` do agente, sem ação nova de `ComandoPc` — ele já devolve
`celulas[]` com endereço, tipo e valor por aba e intervalo. O modelo manda o id do catálogo, a aba,
a letra da coluna de rótulos e até três de valores; o Java converte cada célula e **recusa o
gráfico inteiro** nomeando o endereço quando uma delas não é número ("célula B3 ('sem dados') não é
número"). Texto ou célula vazia virando zero desenharia um ponto no chão, que é uma afirmação que
ninguém fez. Intervalo maior do que o gráfico aceita é recusado **antes** de falar com o PC. A
leitura cobre a linha de cabeçalho junto com os dados, numa ida só, e é de lá que sai o nome de cada
série. Para isso o `ConsultaArquivosPc` ganhou `consultar(...)`, que devolve o `JsonNode` do agente;
o `executar(...)` continua a casca que serializa para o modelo, e as duas ferramentas de consulta não
mudaram.

A `unidade` é a única exceção ao "nada de texto do modelo" na fonte `planilha`: a planilha não
declara em que unidade está, então o modelo copia o símbolo do cabeçalho. É rótulo, não número — no
`clima_7_dias` o Java sabe a unidade e ignora o campo.

## O holograma

O dono digita um objeto na HUD, um modelo descreve o objeto em NDJSON de peças primitivas, e cada
linha completa vira peça na tela **enquanto o modelo ainda escreve**. O contrato inteiro (formato,
validação, eventos, prompt) está em `docs/HOLOGRAMA.md`. Aqui ficam só as regras que valem fora dele.

- **`ProvedorDeModelo.transmitir(pedido, aoTexto, cancelamento)` é o único caminho de stream.** O
  `default` responde inteiro e entrega o texto num pedaço só, então provedor que não transmite
  continua compilando. A Anthropic (`ClaudeClient.transmitirMensagem` + `LeitorDeStreamAnthropic`) e
  o local (parciais do E2) sobrescrevem. Só texto passa por `aoTexto`; chamada curta, sem
  ferramentas, sem cache da conversa.
- **Quem corta é um `Cancelamento`**, e não interrupção de thread: o provedor pendura nele o que
  fecha o stream (o `InputStream` da Anthropic, o `pc.cancelar` do `inferir`), e a leitura bloqueada
  sai pelo erro que o corte provoca. Quem chama confere `cancelado()` antes de tratar esse erro
  como falha do modelo.
- **Parcial de `inferir` é um endpoint E2, não uma ação**: `POST /api/pc/v2/comandos/{id}/parcial`
  responde 204, ou **410 quando a VPS desistiu**, e o 410 é o que faz o agente fechar o Ollama. O
  resultado final continua pelo `/resultado` e é a fonte da verdade: a VPS emenda os pedaços pelo
  `inicio` e completa pelo `content` o que se perdeu. O ouvinte entra no `PcService` **antes** de o
  comando ir para a fila — o agente pode postar a primeira parcial antes de o enfileirar voltar.
- **A temperatura não vai à Anthropic.** Os modelos atuais recusam `temperature` com 400; o
  `OpcoesDoPedido.temperatura` só é lido pelo local, e o agente a prende em 0..1. O `modelo` das
  opções, ao contrário, só a Anthropic lê: no local quem escolhe o modelo é o PC.
- **A reserva no Claude só vale antes da primeira peça.** Depois dela, outro modelo desenharia outro
  objeto por cima do que está na tela; a geração termina com `erro` e as peças ficam.
- **Sem a linha de fim, o servidor continua — com o mesmo modelo.** Até duas chamadas novas levam as
  linhas aceitas como fala do assistente; a montagem descarta id repetido. Esgotadas, o `fim` sai
  `status: incompleto` com o motivo, e a HUD retoma por `GET /api/holograma/continuar` (a última
  geração fica 30 min). Objeto incompleto nunca entra em cache.
- **A conversa mexe na cena por uma ferramenta só**, `holograma` (`acao` em enum; seção 12 do
  `docs/HOLOGRAMA.md`). O comando vai em `ResultadoFerramenta.holograma` e sai como
  `holograma.comando` pelo `EventosHud.aoTela`. O alvo ("os pistões") é resolvido no Java pelo
  `ResolvedorDeAlvo` — a HUD só recebe ids —, e empate volta ao modelo para ele perguntar. O estado
  vem da HUD em `POST /api/holograma/estado` e entra no bloco **dinâmico** só com o modo aberto; o
  `EstadoDoHologramaService` também lembra o que o servidor mandou no turno, porque o retrato chega
  depois de um "abre e carrega explodido". Comando curto com o modo aberto ("explode", "mais") vai
  pelo `IntentoDoHolograma`, sem modelo, depois da `TrocaDeModelo` e nunca com pergunta pendente; a
  resposta sai `RespostaConversa.silenciosa`. Na HUD, nada mexe na cena fora do
  `ComandosDoHologramaService`.
- **A galeria guarda o completo com 8 peças ou mais, antes do `fim` sair**, num JSON por objeto no
  volume `holograma` (`HOLOGRAMA_PASTA`), com a chave no **pedido** normalizado mais o nível. O
  `/gerar` a consulta primeiro e reproduz pelo mesmo SSE (`origem: cache`); `novo=true` força o
  modelo e substitui. Os três exemplos são itens fixos dela, e a miniatura é capturada pela HUD.

## O PC Windows pelo Telegram

Edição de arquivos comuns: `editar_arquivo` aceita apenas IDs catalogados de TXT/MD/JSON/XLSX,
`versaoEsperada` SHA-256 e operações fechadas. A proposta é preparada no agente,
com prévia e `editId`. Dentro da `pastaArquivos`, sobrescrita do original, cópia e
`desfazer_edicao` são aplicadas **no mesmo pedido, sem `Aprovacoes`**: o agente guarda backup
protegido de cada versão, então nada ali é irreversível — e um "Posso?" por edição, mais outro
para voltar atrás, era atrito sem contrapartida. O `FluxoEdicaoPc.aplicar` **espera** o resultado
antes de responder, porque a frase que volta afirma o que foi feito ("Atualizei nota.md (2
trechos). Se quiser voltar, diga desfaz."), e é isso que deixa a edição já concluída para um
"desfaz" na sequência; o commit recebe apenas `editId`, nunca caminho ou conteúdo refeito pelo
modelo. A prévia segue indo ao modelo na cópia, que não se desfaz.
O agente guarda backup protegido, revalida hash antes do move atômico e recusa
conflitos. A janela residual contra editores externos sem lock impede prometer
compare-and-swap perfeito. O expurgo configurável de backups limita desfazer.
`substituir_todas_ocorrencias` troca todas as correspondências literais, com opção
de ignorar maiúsculas, e a prévia informa a contagem. O `ContextoArquivo`, pertencente
ao `Assistente`, guarda o alvo real por usuário autenticado, chat e canal. ID, nome,
formato e versão vêm do catálogo/leitor ou de edição confirmada, nunca da narrativa
do modelo. `ler_arquivo` pode omitir ID; `editar_arquivo` pode omitir ID e versão.
“Esse arquivo”, “nessa planilha” e complementos como “R$ 6” podem usar o mesmo alvo
TXT/MD/JSON/XLSX sem repetir o nome. Seleção ambígua não conserva o alvo anterior, e no
resto daquele pedido trava a **edição**, não a consulta: listar de novo e ler cada candidato pelo
ID continuam indo ao PC (é assim que se acha um arquivo pelo conteúdo), mas nada consultado ali
vira o alvo — senão o último lido seria o "esse arquivo" da mensagem seguinte;
nome explícito diferente exige localizar e ler o novo arquivo. O **nome falado** — o nome sem extensão, como
“assistentes ia mais usados 2026” — vale para todo formato editável, e não só para planilha;
quem garante que ele aponta para um arquivo só é o `apelidoUnico` que o agente calcula contra o
catálogo **inteiro**, de modo que `relatorio.md` ao lado de `relatorio.xlsx` torna o apelido
ambíguo e a edição é recusada. Se a edição falhar, o mesmo turno não
cria outro documento. Leituras paginadas da mesma versão podem comprovar leitura
integral para reescrita; registros de leitura expiram em dez minutos. A VPS confere também os
intervalos efetivamente entregues ao modelo, pois o agente pode ter marcado como lido um trecho
que o limite de resultado encurtou.
O vínculo vai no `contextosArquivos` do histórico, separado dos turnos que expiram ou
são compactados; registros antigos sem esse campo continuam legíveis. Limpar/esquecer a
conversa remove o vínculo. Após restart, edição ou desfazer, a identidade permanece, mas
é obrigatório reler. A versão confirmada vem do `dados.versao` do resultado de aplicação,
inclusive sem anexo. Operação em andamento/incerta fica vinculada ao ID do comando até
`status_operacao_pc` observar estado final; só então uma releitura libera nova edição.
Se o modelo falhar depois de um efeito real, salva-se o contexto atualizado com os turnos
anteriores completos, sem guardar o turno incompleto. Tarefa agendada não herda esse estado.
O resumo do alvo entra somente no system dinâmico, uma vez por turno; nunca nas definições
das ferramentas nem na personalidade. O protocolo do PC e a recusa a arquivos alterados
externamente não mudam.
`desfazer_edicao` pode omitir `editId` para a última edição original concluída
enquanto a instância do servidor permanecer ativa.

**Apagar é reversível, então não pergunta.** `apagar_arquivo` recebe só IDs do catálogo e move os
arquivos para a lixeira do agente (`<pastaArquivos>/.lixeira/AAAA-MM-DD/`, registro mais bytes);
`restaurar_arquivo` lista a lixeira quando não recebe `idLixeira` e devolve o arquivo à pasta
quando recebe. Nenhum dos dois passa pelo `Aprovacoes`: o arquivo não sai de vista, e um "Posso?"
por arquivo — mais outro para voltar atrás — era o mesmo atrito sem contrapartida do
`desfazer_edicao`.

**A assimetria mudou de lugar: é entre apagar um e apagar em massa.** O orçamento é de
`MAX_SEM_PERGUNTA` (5) arquivos por **pedido**; a chamada que o estoura **não move nada** e vira
*uma* pendência `COMUM` para a lista inteira (`Aprovacoes.TIPO_PC_LIXEIRA`), com a pergunta montada
em Java — `"Vou mover 12 arquivos para a lixeira: a.md, b.md e mais 10. Posso?"`. Mover cinco e
perguntar os sete restantes deixaria um estado meio-feito que ninguém pediu, e uma pergunta por
arquivo treinaria o dono a dizer "sim" sem ler. O orçamento é um par de campos
(`ultimoPedido`/`apagadosNoPedido`), não um mapa: o `Assistente.responder` segura o lock pelo turno
inteiro, então só um pedido corre por vez e um mapa precisaria de evicção para nada.

O `TIPO_PC_LIXEIRA` está **fora** do `Aprovacoes.tipoNoDiario`, de propósito: o lote anota uma linha
por arquivo de dentro do próprio `Supplier`, cada uma com o `idLixeira` dela, e mapear o tipo lá
somaria uma linha agregada em cima dessas. É a única exceção ao "as aprovadas entram num lugar só".

Nomes e prazo vêm do agente, nunca do modelo. O `LixeiraPcService.nomesPorIds` varre
`listar_arquivos` por página (50 por vez, teto de 10) **uma vez** para o lote todo — cinquenta
chamadas ao `nomePorId` seriam cinquenta idas ao PC no caminho crítico do turno. Aceitar o nome do
modelo deixaria ele nomear um arquivo e apagar outro. ID fora do catálogo, nome de pasta protegida
(`.lixeira`, `backup`, qualquer coisa com separador ou começando com ponto) e lista acima de 50
recusam a **chamada inteira**, antes de qualquer movimento: nada sai de vista num pedido que o
catálogo não confirma inteiro. O prazo falado sai da **diferença entre `data` e `expiraEm`** do
próprio `mover_para_lixeira`; sem esses campos a frase sai sem número ("Fica na lixeira; se quiser,
eu volto."), porque é melhor não prometer prazo do que prometer o errado.

Atenção a uma diferença de chave fácil de errar: `listar_arquivos` devolve os itens em
**`arquivos`**, e `listar_lixeira` devolve em **`itens`**. Teste com agente de mentira não pega
isso sozinho — se o falso agente responde com a chave que o código espera, ele só prova que o
código concorda com o próprio engano. Agente de mentira copia a forma do agente real.

A restauração devolve um **ID de catálogo novo**, porque a entrada antiga saiu do catálogo no
movimento: proposta de edição e registro de leitura com o ID velho morreram junto. A retenção é
política do PC (`lixeira.diasRetencao`, padrão 30) e o expurgo roda lá; a VPS não a configura, só
repete o número que o agente devolveu no resultado do movimento.

Ação nova de PC toca **quatro** lugares, e esquecer um deles falha calado: `ComandoPc.ACOES`, o
filtro do enum em `ControlarPcFerramenta.esquemaEntrada` (senão ela aparece duas vezes ao modelo),
e os dois portões de `dados` — `PcController` no caminho E2 e `PcService.concluir` no antigo. Fora
de um dos portões, o resultado estruturado volta 400 para sempre; como o replay do resultado é a
primeira coisa do laço do agente, ele travaria inteiro. É o que o conjunto `ComandoPc.LIXEIRA`
existe para não deixar acontecer de novo — e o `ComandoPc.INFERENCIA`, pelo mesmo motivo.

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
- **O programa em primeiro plano vem no `X-Pc-Foco`**, nos dois long polls: só o nome do processo
  (`chrome`, `Code`, `WINWORD`), nunca o título da janela. O `PcService` guarda sem caractere de
  controle e com teto de 64, e **ausente apaga** — ao contrário do `X-Pc-Apps`, porque o agente omite
  o cabeçalho justo quando não tem um nome válido, e foco velho afirmaria o que já não é. Quem o põe
  no prompt (o `RetratoDoMomento`) confere de novo contra `[A-Za-z0-9._-]{1,40}` e descarta o resto
  inteiro, sem filtrar até caber.
- **PC offline responde na hora** (`PcService.online()`, 90 s sem contato), antes de enfileirar:
  travar o turno 30 s para no fim dizer "offline" seria o pior dos mundos. Com o PC online, a
  ferramenta espera até 30 s pelo resultado; o comando segue valendo 60 s na fila.
- **`desligar` e `reiniciar` exigem mensagem nova autenticada** (`Aprovacoes`, risco `CRITICO`):
  a ferramenta só registra a aprovação pendente e devolve ao modelo "confirme em nova mensagem";
  quem consome a decisão é o `Assistente.responder`, **antes** de falar com o Claude. Não há
  código que passe pelo modelo e, portanto, não há como ele se autoconfirmar. Uso único,
  2 minutos no `CRITICO` (10 no `COMUM`), uma pendência por vez, amarrada à ação e aos argumentos
  capturados na hora do pedido. O agente ainda espera 60 s (`shutdown /t 60`), então dá para
  desistir com `cancelar_desligamento` — que só aborta o desligamento já agendado no PC: uma
  aprovação em aberto morre com "cancelar" do usuário ou por expirar.
- **O despertar é a única coisa que o PC inicia.** Duas palmas ou a palavra **"Jarvis"** dita
  sozinha (o `ReconhecedorDeVoz` só dispara quando a transcrição inteira é `jarvis` — a grafia
  `jardes`, que o Whisper base costuma devolver, é normalizada; "Jarvis, iniciar" **não** dispara)
  abrem a tela
  **sem** passar pela VPS: o agente abre o Chrome em `<urlHud>?inicio=1` e é a HUD que pede a
  introdução. A VPS só entra quando a tela **já** estava aberta: aí o agente chama
  `POST /api/pc/falar` e o Jarvis responde "Já estou aqui, senhor.". Esse é o único endpoint em
  que o agente fala sem ter recebido comando — e mesmo nele ele manda a **chave** de uma frase do
  `FalaDaTela`, nunca o texto. O `PC_TOKEN` mora num `.properties` numa máquina de mesa; aceitar
  texto livre ali o transformaria num megafone para a tela da sala.
- **`modo_repouso` e `falso_positivo`** são as ações do despertar: a primeira fecha a tela e faz o
  agente voltar a escutar o gatilho, a segunda conta um alarme falso. Nenhuma leva argumento.
- **`captura_tela` vai aos dois destinos**: `sendPhoto` multipart no Telegram (o único POST da Bot
  API que não é JSON, montado pelo `MultipartTelegram`) e um `Artefato` no `ResultadoFerramenta`,
  que o `AnexosService` publica para a HUD. O mime é farejado dos bytes no `PcDto`, nunca aceito
  do agente — um `image/svg+xml` declarado chegaria à tela como imagem, e SVG é scriptável.

## O escritório pelo MCP

Os agentes do escritório (Hermes3D com o Hermes Agent no PC) chamam o Jarvis como servidor MCP em
`POST /api/mcp`. O guia de ligar e conferir está em `docs/ESCRITORIO_MCP.md`; aqui ficam as regras.

- **Terceiro domínio de confiança.** `MCP_TOKEN` (`jarvis.hud.mcp-token`, esticando o `Hud` com o
  construtor de dois argumentos e o `@ConstructorBinding`) é exigido pelo `TokenMcpFiltro`, e o
  `TokenHudFiltro` desiste da rota pelo mesmo predicado (`TokenMcpFiltro.rotaDoMcp`), como faz com
  `/api/pc`. Sem `?token=`. Requisição com `Origin` é 403: quem chama não é navegador.
- **O catálogo é outro.** `FerramentaMcp` **não** é `Ferramenta`: o Spring injeta só as
  implementações dela no `ServidorMcp`, e nenhuma ferramenta da conversa vaza para os agentes, que
  leem a internet. Ferramenta MCP nova é um `@Component` em `dev.jarvis.mcp`, e a pergunta antes de
  criar é a do `verificar_site`: o agente escolhe valor de lista ou número, nunca destino, comando ou
  texto que o Jarvis repita ao dono.
- **JSON-RPC à mão, sem SDK**: um POST por mensagem, resposta JSON, sem stream nem sessão
  (`GET`/`DELETE` são 405, notificação é 202). Erro de protocolo, método e ferramenta desconhecidos
  são erro JSON-RPC; argumento errado vai no resultado com `isError`, para o modelo corrigir.
- **`avisar_aprovacoes` não leva texto do agente**: tipo de uma lista e quantidade; a frase é a
  `escritorio_aprovacoes` do `FalasService` e sai pelo `AvisosProativos` (silêncio, sem repetir o
  mesmo número). Por cima, um aviso por tipo a cada 15 minutos, em memória.
- **O livro financeiro é um só.** `FinancasService` atende as ferramentas da conversa
  (`registrar_lancamento`, `resumo_financeiro`, `remover_lancamento`) e as do MCP
  (`registrar_gasto`, `registrar_ganho`, `resumo_financeiro`). Valor em centavos (`long`), conta
  feita em Java, e a validação (valor, data, categoria, descrição) mora no serviço, não em quem chama.
  Remover só marca `removidoEm`; o MCP não remove. O `resumo_financeiro` do MCP devolve os mesmos
  números também em `structuredContent` (centavos), pelo `ResultadoMcp.estruturado`: é o que o painel
  de operação do Hermes3D desenha, sem ler número de prosa. Cada lançamento entra no diário
  (`LANCAMENTO_FINANCEIRO`/`LANCAMENTO_REMOVIDO`, autônomos) com a origem de quem lançou — a do
  escritório é `OrigemDaAcao.ESCRITORIO`.
- **Agenda e leads são arquivos no PC, não no Google.** `LEADS_ARQUIVO` (padrão `leads.csv`) e
  `AGENDA_ARQUIVO` (padrão `agenda.xlsx`) são planilhas XLSX ou CSV na `pastaArquivos` do agente — a
  pasta do Jarvis —, escritas pelo agente de prospecção do escritório e pelo dono. Quem lê é o
  `PlanilhaDoPc` (em `ferramentas`, porque usa o `ConsultaArquivosPc`): `listar_arquivos` acha o ID
  pelo **nome exato**, e `ler_arquivo` traz o cabeçalho e as **últimas** linhas (até 8 idas de 99
  linhas no XLSX e 100 no CSV, os tetos do agente), porque os dois arquivos crescem para baixo. CSV
  salvo pelo Excel em português vem com `;`: o leitor percebe pelo cabeçalho e relê. Data do Excel
  chega como `AAAA-MM-DD` e, com o agente que traz `dataHora`, com a hora — hora pura do Excel é uma
  data em 1899 e sai só a hora. `AgendaService` e `LeadsService` devolvem texto pronto para a conversa
  (`agenda_de_hoje`, `leads_do_dia`) e para o MCP (as mesmas, só leitura). Colunas **pelo cabeçalho**,
  não pela posição; valores saem limpos (`<<<`/`>>>` e controle fora) e cortados. As duas ferramentas
  são `oferecida()` com o PC configurado (`PC_TOKEN`), que é de partida; PC desligado é a leitura que
  avisa, com a frase do `ConsultaArquivosPc`. As quatro variáveis (`LEADS_ARQUIVO`, `LEADS_ABA`,
  `AGENDA_ARQUIVO`, `AGENDA_ABA`) esticaram o bloco `Pc` com o sub-record `Escritorio` e o construtor
  de seis argumentos; o nome é validado como nome de arquivo, sem barra, e o torto cai no padrão.

## A abertura falada da HUD

`POST /api/inicializacao` é o que a tela pede no instante em que acende, e devolve as três frases
que o Jarvis fala mais os números que a sequência de abertura mostra (`sitesOk`/`sitesTotal`).

O `InicializacaoService` espelha o `BriefingService` — várias fontes, uma chamada ao Claude, fonte
que falha fica de fora —, com duas diferenças de propósito:

- **Fala com o `ClaudeClient` direto, não pelo `Assistente`.** Valem os dois motivos do
  `NarradorDeBriefing` (o ciclo pelas Ferramentas e o risco de o modelo chamar ferramenta no meio
  de uma apresentação), mais um que é só daqui: `Assistente.responder` segura um lock justo pelo
  turno inteiro, e quem bateu palmas na frente do PC não pode esperar uma conversa do celular
  terminar.
- **Reúne as fontes em paralelo**, com prazo compartilhado de 3 s, num executor de threads
  virtuais próprio (o desenho do `ConversaService` — não há executor compartilhado no projeto).
  Em sequência não dá: `ClimaService.hoje()` é `synchronized` e `SitesService.checarTodos()`
  percorre os sites um a um com 10 s cada, e o despertar ficaria refém do site mais lento.

Cache de **2 minutos**, com `Duration.between` e não com `Saudacao.reutilizavel` (que também exige
mesmo dia e mesma saudação): é o que impede palmas repetidas de gerarem duas chamadas ao Claude.
Prazo e validade são constantes na classe, como em `BriefingService` e `PcService` — não valia
mais um componente em `JarvisProperties`, que é construído por posição em dezenas de testes.

Claude falhando, a introdução é montada em Java com o que chegou. A tela acabou de acender sozinha:
ficar muda seria pior que uma frase sem charme.

**A fala avulsa** (`TelaEvento.deFala`) usa o nome de evento `voz.falar` e pega carona no
`@EventListener aoTela` que já existe no `EventosHud`, que repassa pelo `nome` — tipo novo de
`TelaEvento` chega à HUD sem fiação nova de SSE.

## O Jarvis falando primeiro

Até aqui tudo que o Jarvis dizia era resposta a um pedido — menos o resumo das 8h, que o dono
agendou, e o aviso de teto de custo. O `MonitorDeSites` muda isso: ele acompanha os
`SITES_MONITORADOS` a cada `SITES_INTERVALO_MINUTOS` e avisa quando um site cai e quando volta.
Antes dele o `SitesService` só era consultado quando alguém perguntava — no briefing e no
`verificar_site` —, então uma API que caísse às duas da tarde de terça aparecia no briefing de
quarta.

**Todo aviso não pedido passa pelo `AvisosProativos`, e por mais nada.** Ele é o único lugar onde
moram as três regras de boa educação, e aviso novo as herda sem ninguém lembrar delas:

- **Horário de silêncio** (`PROATIVO_SILENCIO`, padrão `23:00-07:00`, no fuso configurado). Dentro
  dele nada sai, e o aviso **não é descartado**: entra numa fila e é entregue junto, numa mensagem
  só, no primeiro tique de minuto depois do fim da janela. Um site que caiu às 2h ainda importa às
  7h; o que não importa é o telefone apitando às 2h. A `JanelaDeSilencio` **cruza a meia-noite** de
  propósito, e é por isso que não é um par de `LocalTime` comparado com `isBefore` — com a
  comparação ingênua, `23:00-07:00` nunca conteria hora alguma e o silêncio jamais valeria.
- **Sem repetir**: a dupla *chave do evento + estado* é lembrada (`site:loja` + `fora`), e o mesmo
  estado não é anunciado duas vezes. "O site caiu" é notícia; "o site continua caído" o dono já
  sabe.
- **Um destino só**, o `TELEGRAM_USUARIO_PERMITIDO`. Zero, ninguém é avisado — e aí nem se acumula,
  porque guardar para ninguém é só memória gasta.

**Duas checagens com falha, não uma.** Uma requisição HTTP sozinha erra por motivos que não são o
site: um deploy de trinta segundos, um DNS lento, um pico de rede na VPS. Avisar na primeira
treinaria o dono a ignorar o aviso, que é o único jeito de um monitor falhar de vez. A volta, ao
contrário, é anunciada na **primeira** checagem boa: a assimetria é de propósito, porque o custo de
demorar a dizer "voltou" é o dono preocupado à toa. O `desde` que a frase afirma é o instante da
**primeira** falha da sequência, não o da segunda — é a hora em que o site caiu.

### A rotina do site virou regra

O aviso de queda terminava num "Posso rodar a rotina X?" quando havia par em `SITES_ACOES`. Isso
saiu do monitor: o aviso é só aviso, e a queda e a volta saem também como `EventoDeGatilho`. Na
partida, cada par do `SITES_ACOES` vira **uma** regra de gatilho equivalente (ver "Gatilhos"); é a
tarefa da regra que pergunta, pelo `Aprovacoes.encaminhar`, como qualquer ação de tarefa. O
`Supplier` de pergunta do `AvisosProativos.avisar` continua existindo para quem precisar dele.

### O resultado estruturado do `SitesService`

O `checar(String)` devolve **prosa** (`"HTTP 502 em 80 ms (ERRO no servidor)"`), que é o que o
briefing lê e onde o `InicializacaoService` procura a marca `(OK)`. Decidir "o site caiu" a partir
dela significaria farejar texto — acerta hoje e erra calado no dia em que a frase mudar, e um aviso
proativo é uma afirmação, não um palpite. Então o `SitesService` ganhou `situacaoDe(url)` e
`checarTodasAsSituacoes()`, que devolvem `Situacao(ok, status, ms, motivo)`, e o `checar` virou a
**casca que formata** — o texto não mudou um caractere. É o desenho do `ConsultaArquivosPc`, onde
`consultar` devolve o dado e `executar` serializa.

### O histórico de disponibilidade conta, não lista

`HistoricoDeDisponibilidade` guarda um `AAAA-MM-DD.json` por dia no volume `proativo`, 30 dias, com
a troca atômica do `ArquivoAtomico` — o desenho do `DiarioDeAcoes`, incluindo a gravação
write-behind numa thread virtual única, a virada do dia preguiçosa **e** por cron de meia-noite, e o
expurgo também na partida.

O que muda é a granularidade: aqui o dia é **agregado por site** (`checagens`, `falhas`, `quedas`),
e é a razão que o `CustoDiarioEmArquivo` já documenta. Um site checado a cada cinco minutos dá 288
checagens por dia; três sites por trinta dias dariam 26 mil linhas para responder a uma pergunta que
é *um número* — "quanto o site ficou no ar em outubro?". O diário de ações **não** agrega pela razão
oposta: "o que você fez hoje?" quer os fatos, um por um. Por isso também não há teto de linhas: o
tamanho do dia depende de quantos sites existem, não de quantas vezes algo aconteceu.

**Dia sem arquivo é dia omitido**, não dia com 100%: zero falhas ali afirmaria "estava tudo no ar"
onde a verdade é "eu estava desligado". O `serieDiaria(int)` já devolve os pontos prontos para uma
fonte nova do `grafico_de_dados` — que são os dois lugares de sempre,
`GraficoDeDadosFerramenta.FONTES` e um `case` no `executarDetalhado`, e ainda não foram tocados.

### Aviso novo toca dois lugares

Uma frase no `FalasService.FALAS` e uma chamada a `AvisosProativos.avisar(chave, estado, texto,
perguntaSeAgora)`. O catálogo é fechado como o do `FalaDaTela`, pelo mesmo motivo — o aviso chega
sem ninguém ter pedido, e o dono não tem como conferir de onde o texto veio. O que o `FalaDaTela`
não tem são os **marcadores**: `{site}`, `{desde}`, `{motivo}` e `{duracao}`, cujos valores saem de
dado real (relógio, nome do site, motivo da checagem), nunca de texto do modelo. É a regra da
`pergunta` do `Aprovacoes` e da `descricao` do `DiarioDeAcoes`. Situação desconhecida e marcador sem
valor **estouram**: uma mensagem chegando ao Telegram com um `{motivo}` literal é pior que um teste
vermelho.

### A cota de voz avisa antes

O `MonitorDeCotaVoz` é o segundo morador do `AvisosProativos`, e **todos** os seus avisos passam por
lá: o antecipado, a reserva, a chave recusada e a renovação. Antes ele chamava o `TelegramClient`
direto — tocava o telefone às 3h da manhã e o primeiro aviso chegava no instante em que a voz da
ElevenLabs *já* tinha sido desligada, que é notícia dada quando não há mais nada a fazer com ela.

São **duas chaves de evento**, e a separação é o desenho:

| Chave | Estados | Quem avisa |
|---|---|---|
| `voz:provedor` | `navegador:reserva`, `navegador:chave`, `elevenlabs` | `trocarParaNavegador` e `voltarParaElevenLabs` |
| `voz:cota` | `alta:<período>` | o aviso antecipado |

Uma chave para os três de provedor porque são estados do **mesmo fato** ("de onde a voz sai agora"),
e é assim que a memória do `AvisosProativos` reconhece a repetição. Chave separada para o
antecipado porque, na mesma, os dois apagariam a memória um do outro.

O antecipado sai quando o uso passa de `VOZ_AVISO_PERCENTUAL` (padrão 80), **uma vez por período de
cobrança**. O período é o `renovaEm` do `SaldoDeVoz` — é o único dado de período que existe, porque
a ElevenLabs informa quando o contador zera, não quando ele começou; plano sem essa data avisa uma
vez e não repete. O marcador fica no `DiarioDeVoz`, em disco, senão um restart reavisaria. Estando a
voz **já no navegador**, o aviso não sai e o período é marcado: "a cota está em 80%" depois do aviso
da reserva repete o que a mensagem anterior disse.

**A projeção não é um número do modelo nem um chute.** O `DiarioDeVoz`, que era um contador em
memória zerando à meia-noite, passou a guardar o uso **por dia** num JSON com a troca atômica do
`ArquivoAtomico`. Daí sai `mediaDiaria()`, e três regras decidem o que a frase afirma:

- **Hoje não entra na média** — é um dia pela metade, e meio dia de uso puxaria a projeção para
  longe justamente na hora de avisar.
- **Dia sem registro é dia omitido**, nunca dia com zero: é a regra do `HistoricoDeDisponibilidade` e
  do `MedidorDeCusto.serieDiaria`, e aqui zero afirmaria "não falei nada" onde a verdade pode ser
  "eu estava desligado". A soma é dividida pelos dias **com** registro.
- **Menos de dois dias completos não é ritmo**: `mediaDiaria()` volta vazio e a frase sai sem
  projeção.

São **três falas** e não uma com marcador opcional, porque `FalasService.frase` estoura com marcador
sem valor — `voz_cota_alta` ("dura até {data}"), `voz_cota_alta_ate_renovacao` (a projeção passa da
renovação, então a cota não acaba e uma data posterior ao zeramento não afirmaria nada) e
`voz_cota_alta_sem_projecao`. Os dias saem de `Math.floor`: arredondar para cima prometeria um dia
que a cota pode não ter.

**A armadilha da pasta.** O uso diário mora em `/data/voz/uso/`, numa **subpasta** do volume `voz`.
`CacheDeVoz.descartarExcedente()` lista todo arquivo *regular* de `/data/voz` e apaga do menos usado
para o mais usado até caber no teto — um `uso-diario.json` solto ali sairia junto com os MP3 no dia
em que o cache estourasse, e o `.bak` com ele. `Files.list` não desce em subpasta. A pasta é
constante na classe, como o `CacheDeVoz.PADRAO`: não vale configuração nova para uma pasta dentro de
um volume que já está montado.

## O que o Jarvis anota sozinho

As ferramentas de memória atendem a pedido explícito. O `AnotadorDeMemoria` atende ao resto, no
desenho da revisão em segundo plano do Hermes Agent: ele junta a conversa de cada chat (Telegram e
HUD, só com `usuarioAutenticado() > 0`) — a mensagem do dono **e a resposta final do Jarvis**, rotuladas
`Pessoa:`/`Jarvis:` — e, quando o trecho fecha (`MEMORIA_ANOTADOR_OCIOSO_MINUTOS` parado ou 10
turnos, o que vier primeiro), faz **uma** chamada curta ao modelo de apoio, sem ferramentas e com
`system` próprio, pedindo até 5 candidatos. É o desenho do `ClassificadorDeResposta`: a
personalidade não ajuda a extrair fato e estragaria o prefixo de cache das conversas.

**A fala do Jarvis entra como contexto, não como fonte.** Boa parte das decisões do dono é um "sim"
a uma proposta ("Quer que eu use X?" — "pode"), e só com as mensagens dele o modelo não sabia a que
ele disse sim. O prompt manda anotar o que o dono disse **ou confirmou**, nunca o que o Jarvis
sugeriu sozinho. Ela vai **sem** os avisos ("Anotei", "Aprendi") e cortada em 600 caracteres.
Resultado de ferramenta, busca e anexo continuam **fora**: são dado de terceiros, e memória entra
no prompt de toda conversa seguinte.

**Sem prova de citação.** O dono escolheu o comportamento do Hermes: o modelo escreve o fato com
as próprias palavras, sem copiar trecho literal, e o campo `evidencia` saiu do formato. O que
continua segurando é o resto: o filtro `SEGREDO` e os limites do `MemoriaService` (não
reimplementados), o upsert por assunto, o `Recusado` do assunto que o dono mandou tirar, o aviso e o
desfazer. As ferramentas explícitas (`registrar_memoria`, `atualizar_memoria`) **mantêm** a
`evidencia` literal do pedido — elas são o modelo principal, no meio de um turno que pode ter lido
página ou arquivo.

O `acao` e o `idExistente` que o modelo devolve são **informativos**. Quem escolhe entre criar e
atualizar é o `MemoriaService.anotar`, casando o assunto no disco: um `acao: novo` errado não
duplica, e um `idExistente` apontado para outro registro não sobrescreve nada. O modelo nunca mira
uma escrita num ID que ele escolheu, e o `tipo` é fixo em `declaracao_usuario`.

`anotar` existe porque `registrar` recusa assunto repetido com `Conflito` — falta o *upsert*, e o
dono volta a falar do mesmo assunto. Ele decide e grava dentro de **um** `comBloqueio`, chamando
`registrarTravado`/`atualizarTravado`: `comBloqueio` toma um `FileLock` de verdade a cada chamada, e
aninhar dois do mesmo arquivo estoura `OverlappingFileLockException`.

O que foi anotado entra numa fila por chat e sai numa linha na resposta seguinte (`drenarAviso`:
"Anotei: ..."), anexada em Java **só** à string da resposta — a mensagem `assistant` do turno fica
intacta, então o aviso nunca chega ao histórico do modelo nem ao prefixo de cache. Memória que
cresce calada é memória que o dono não consegue corrigir. O `RespostaConversa.fala` carrega a
resposta **sem** o aviso: ele é para ler na tela, não para ouvir — a HUD narra
`resposta.fala || resposta.texto` e cala a `silenciosa` (o caminho rápido do holograma).

No log vão **só** contagens e motivos de recusa, nunca conteúdo: `Anotador: N candidatos, M
gravados, recusados {recusado_pelo_armazem=1}`.

## O que o Jarvis aprende fazendo

A memória guarda **quem o dono é**; os procedimentos guardam **como fazer** uma classe de tarefa do
jeito dele — as "skills" do Hermes Agent. São três peças:

- **`ProcedimentosService`**: um Markdown por procedimento em
  `MEMORIA_PASTA/procedimentos/usuarios/<id>/<nome>.md`, cabeçalho JSON numa linha de comentário
  (`titulo`, `quando`, `revisao`, `atualizadoEm`). Pasta derivada, sem configuração nova. O `nome`
  casa com `[a-z0-9][a-z0-9-]{1,49}` antes de virar arquivo, e a pasta não pode ser link simbólico.
  Tetos: 40 procedimentos, corpo de 3.000 caracteres. Reescrever substitui o corpo e deixa o
  anterior no `.bak`; **esquecer move para `.removidos`**, nada some. O filtro de segredo daqui é
  mais estreito que o da memória de propósito: recusa o **valor** (`token: abc123…`, `sk-…`, chave
  privada), não a palavra — procedimento técnico fala de "tokens" o tempo todo.
- **`RevisorDeProcedimentos`**: o `Assistente` entrega o turno inteiro no fim (o mesmo ponto onde
  chama o anotador). O revisor resume em Java — pedido, cada `→ ferramenta entrada`, `← ok/ERRO:`
  com só 160 caracteres do resultado, e a resposta — e soma as chamadas. Com
  `CHAMADAS_PARA_REVISAR` (5) desde a última revisão, faz **uma** chamada ao modelo de apoio, fora
  do turno, com a lista dos existentes e o corpo dos que a conversa leu por `ler_procedimento` (até
  dois — a preferência do Hermes por corrigir o que estava em uso). Até 2 ações por revisão. O prompt
  proíbe tarefa de uma vez só, falha de ambiente ("a ferramenta X não funciona" vira recusa que dura
  meses), tentativa que não funcionou e instrução vinda de resultado de ferramenta. O que foi
  gravado sai numa linha na resposta seguinte ("Aprendi um procedimento: ..."), pelo mesmo
  `drenarAviso` do anotador, e entra no diário como `APRENDER_PROCEDIMENTO` (autônoma).
- **O índice no system**: `SistemaDoJarvis` põe no bloco **dinâmico** só `- nome: quando` (teto de
  1.200 caracteres), com a regra "chame ler_procedimento antes e siga os passos; não é autorização".
  O corpo vem sob demanda pela `ler_procedimento`, que fica **sempre carregada**
  (`Ferramentas.EXTRAS_SEMPRE_CARREGADAS`) porque o índice manda chamá-la. A
  `esquecer_procedimento` é "faz e avisa", pela régua do `apagar_arquivo`.

Liga e desliga com o anotador (`MEMORIA_ANOTADOR_ATIVO`): são as duas metades do mesmo "aprende
sozinho". Revisão de procedimento usa a `Origem.ANOTADOR`, então o custo dela aparece no balde do
anotador.

## O que o Jarvis fez, e o dono não viu

Com mais autonomia, o dono precisa poder auditar o que foi feito sem ter estado olhando a conversa
na hora. O aviso do anotador (`drenarAviso`) sai numa linha da resposta seguinte e vai embora; a
edição de arquivo, o desfazer, a restauração da lixeira e a criação de arquivo não deixavam rastro
nenhum. O `DiarioDeAcoes` é esse rastro: um `AAAA-MM-DD.json` por dia no volume `diario`, 30 dias,
com a troca atômica do `ArquivoAtomico`.

Cada entrada é um `AcaoRegistrada` — instante, `TipoDeAcao`, descrição curta e `OrigemDaAcao`
(`telegram`, `hud`, `anotador`, `tarefa`). A **descrição é montada em Java, de dado real**: o nome
que o agente do PC devolveu, o assunto que o `MemoriaService` gravou, o nome da rotina. É a regra da
`pergunta` do `Aprovacoes`, pelo mesmo motivo — deixar o modelo narrar o diário seria deixá-lo
escolher o que o dono lê sobre o que ele próprio fez.

O `TipoDeAcao.autonoma()` parte o diário em duas metades, e é a parte que importa do desenho:
**autônoma** é o que o Jarvis fez sem perguntar (editar, desfazer, apagar, restaurar, criar arquivo,
anotar e corrigir memória), e é só disso que o dono não tem como saber. O resto ele aprovou de viva voz.

### As aprovadas entram num lugar só

O `Supplier` do `Aprovacoes` roda em exatamente dois pontos — `tratar` (a frase fixa) e
`executarPendente` (o "sim" em linguagem natural) —, e os dois passam por um `rodar(Pendente, canal)`
privado que executa e anota. **Central de propósito**: aprovação nova entra no diário sem ninguém
lembrar de registrá-la, que é o contrário do "ação nova toca quatro lugares" do `ComandoPc`.

Daí vêm os dois tipos próprios de PC. O campo `tipo` da pendência é uma `String`, e um `"pc"`
genérico cobriria **apagar arquivo e desligar o PC** — distinguir os dois pela `descricao` seria
farejar texto. Então `ControlarPcFerramenta` passa `Aprovacoes.TIPO_PC_ENERGIA` (`"pc_energia"`) e o
lote de `apagar_arquivo` passa `TIPO_PC_LIXEIRA` (`"pc_lixeira"`). Para o `tratar` nada muda: a
frase esperada é derivada de "não é rotina", então os dois continuam caindo em
`confirmo pc <código>`. A diferença é no diário: `pc_energia` vira `DESLIGAR_PC`, e `pc_lixeira`
**não está no `tipoNoDiario`** porque anota uma linha por arquivo de dentro do próprio `Supplier`.

O registro vem **depois** da execução e nunca a derruba (`try/catch` com WARN, e `registrar` já não
lança): a ação aconteceu no PC ou no disco, e o diário é a testemunha dela, não o guarda. Tipo de
pendência que o diário não conhece devolve `null` em `tipoNoDiario` e só não é anotado — uma
pendência nova não pode deixar de executar por causa do diário.

### Por que o diário tem teto por dia e o custo não

O `CustoDiarioEmArquivo` guarda o dia **agregado**, e o javadoc dele explica: um registro por chamada
viraria megabytes por semana. Aqui não dá para agregar — "o que você fez hoje?" quer os fatos, um por
um. Então o teto é explícito: `DiaDoDiario.MAX_ACOES` (500) para de acrescentar e passa a contar o que
recusou, o contador vai ao JSON e volta no restart, e a ferramenta diz que a lista está incompleta.
Um dia truncado que se declara truncado é honesto; um dia que cresce sem teto para de ser legível.

### A seção do briefing conta, não lista

`autonomasDesdeOUltimoBriefing()` devolve a contagem por tipo **e avança o marcador** — um
`ultimo-briefing.json` ao lado dos dias, que o expurgo não apaga porque não casa o `NOME_DO_DIA`. O
marcador significa "já contei até aqui, inclusive", então a janela nova é o que vem **estritamente
depois** dele; incluir o próprio instante faria a ação registrada no instante do briefing ser contada
outra vez no seguinte. Sem marcador a janela começa no instante anterior ao início de hoje, senão uma
instalação nova despejaria trinta dias na primeira manhã.

No `BriefingService` a seção entra por um `adicionarSeHouver`, irmão do `adicionar` que **omite** a
seção quando o montador devolve `null`. A diferença importa: no `adicionar`, seção que falhou continua
aparecendo com a explicação do que faltou, e isso não muda — aqui a seção sai por *não ter o que
contar*. Um briefing que anuncia "o que fiz sozinho: nada" todos os dias treina o dono a ignorar
justamente a seção que importa no dia em que ela tiver conteúdo. Como a lista de seções é montada
antes da narração, o alinhamento `falas.get(i + 1)` do `NarradorDeBriefing` continua certo sem
nenhuma mudança lá.

A prosa é só a contagem (`"3 edições de arquivo, 1 anotação na memória."`), do tipo mais frequente
para o menos, com o `rotulo(quantidade)` no singular ou plural. Listar oito linhas de "editei tal
arquivo" num briefing falado seria o inverso de um resumo; quem quer o detalhe pergunta, e aí
responde a `acoes_do_jarvis`.

*(A HUD é um repositório irmão e discrimina a seção pelo título, então até ela conhecer
`DadosSecao.Acoes` o card não é desenhado e a fala sai normal — a mesma situação da rosca.)*

### Quem lê o diário não tem a própria noção de "hoje"

`DiarioDeAcoes.deHoje()`, `deOntem()` e `zona()` existem para isso. A ferramenta `acoes_do_jarvis`
chegou a usar `LocalDate.now(props.zona())` e lia o dia errado contra um relógio de teste: duas
noções de dia divergem na virada da meia-noite, e a hora de cada ação sairia formatada num fuso
enquanto o arquivo foi escolhido por outro. Quem sabe que dia é hoje é o componente que tem o
`Clock`.

A ferramenta segue o molde da `custo_do_jarvis`: dois **enums fechados** (`periodo` = hoje/ontem/
semana, `assunto` = tudo/arquivos/memoria/pc) e nunca um inteiro de dias, que convidaria o modelo a
pedir trinta e ler trinta arquivos no caminho crítico de um turno. A saída tem teto de linhas com
"E mais N", marca `(aprovado)` o que passou por "Posso?", e dia sem nada responde uma frase — isto é
lido em voz alta, e "nenhum resultado" não é resposta.

### Ação autônoma nova toca dois lugares

Uma entrada em `TipoDeAcao` (com `autonoma()` decidido de propósito) e uma chamada a
`diario.registrarDoCanal(...)` no ponto onde a ação **deu certo**. Hoje são sete:
`FluxoEdicaoPc.aplicar` (edição, cópia e desfazer, logo depois de `resultado.ok()`, de onde saem os
três retornos que só diferem na entrega do anexo), `RestaurarArquivoFerramenta`,
`ApagarArquivoFerramenta` (por arquivo movido, nos dois caminhos, porque o `mover` privado é um
só), `CriarArquivoFerramenta`, `AnotadorDeMemoria.gravar` (por fato anotado, não por lote — um lote
pode trazer cinco), `AtualizarMemoriaFerramenta` e `RevisorDeProcedimentos` (por procedimento
gravado). O canal vem de `OuvinteProgresso.canal()`, que é
a única forma de uma ferramenta saber de onde veio o pedido.

Cada ação ganha um `id` curto e estável e o `usuario` autenticado. O campo tipado `desfazer`
contém somente capacidades emitidas pelo servidor: `editId`, ID da lixeira ou do arquivo,
IDs de memória, revisão anterior, procedimento, modo anterior ou ID de agendamento. Conteúdo
anterior e caminhos de backup nunca entram no diário nem na entrada da ferramenta. `referencia`
continua legível para compatibilidade; os construtores antigos também. Registros antigos sem
identidade e metadados suficientes são consultáveis, mas não ganham reversão por adivinhação.

### Desfazer é uma execução com posse persistida

`desfazer_acao` é o caminho geral, só por pedido explícito e sem aprovação adicional. Sem `id`,
usa a última ação reversível disponível do mesmo usuário nas últimas 24 horas; com `id`, consulta
os 30 dias retidos. Pedido sobre horário ou arquivo específico primeiro consulta `acoes_do_jarvis`
com `horario` (HH ou HH:mm) e `texto`, e usa o ID real. Ação sem volta explica o ocorrido e para.

O `DesfazedorDeAcoes` compartilha o controle com os atalhos `desfazer_edicao` e `desfazer_anotacao`.
Antes de executar, `DiarioDeAcoes.mudarEstado` persiste `em_andamento`, pela mesma fila serial do
write-behind, esperando a troca atômica. No PC, guarda também o ID do comando de reversão. Sucesso
registra `DESFAZER_ACAO` e persiste `desfeita`; não há refazer. Resultado incerto mantém a reserva,
e uma nova consulta reconcilia pelo resultado E2 sem repetir o comando. Revisão posterior,
backup expirado e identidade de outro usuário são recusados. O desfazer de memória usa a revisão
exata, preserva a conversa e, ao remover uma anotação nova, marca o assunto recusado.

Procedimentos guardam revisões em `.historico/<nome>/<revisao>.md`; criação se desfaz pelo caminho
de `.removidos`. Troca de modo volta ao anterior somente sem troca posterior vigente. Desfazer um
agendamento revoga execuções futuras; as edições já executadas se desfazem pelas próprias entradas.
`Aprovacoes.pedirComResultado` transporta esses metadados em `ResultadoAprovado`, sem extrair IDs
da prosa e mantendo o registro das aprovadas no `rodar` central.

Na HUD conectada, `acoes_do_jarvis` monta um card `linha_do_tempo` por dia consultado pelo
`MontadorDeLinhaDoTempo`: até 500 ações retidas, com ID e estado da reversão. O modelo recebe só
o resumo limitado; os cards seguem em `ResultadoFerramenta.cards`, sem fontes web e sem ampliar
o validador de pesquisa do modelo. `CardTela.origem` continua sendo o canal `hud`.

### O freio global

`dev.jarvis.agente.Freio` persiste a pausa manual (`ATIVO`/`PAUSADO`, instante e canal) e o marcador
do aviso de teto em `DIARIO_PASTA/freio.json`, com troca atômica e `.bak`, sem volume novo. Ausência
é primeira partida ativa; primário e backup ilegíveis deixam a autonomia pausada.

`ComandoDoFreio` reconhece frases fechadas normalizadas, de até quatro palavras, antes do modelo,
da fila do Telegram e da reserva/lock da conversa HUD. “Jarvis, para tudo”, “pausa tudo” e “para
geral” interrompem o pedido, cancelam o computer use do usuário e acionam os ouvintes `aoPausar`.
A resposta é “Parei tudo. Diga 'pode voltar' quando quiser.”. “Pode voltar”, “retoma” e “volta ao
normal” liberam a pausa manual; não liberam gasto acima do teto autônomo.

`podeAgirSozinho(origem)` bloqueia pausa manual ou teto atingido. `CUSTO_TETO_AUTONOMO_USD`
(vazio/zero = sem teto) soma no `MedidorDeCusto` apenas `ANOTADOR`, `TAREFA` e `GATILHO`. Atingir
o limite aciona os ouvintes e emite um aviso por dia pelo `AvisosProativos`; o marcador persiste
antes de avisar. A virada usa o fuso configurado, sem remover uma pausa manual. Custos antigos
lançados como conversa permanecem como estavam; não há reclassificação por estimativa.

Anotador e revisor descartam trechos na pausa. Cada análise captura uma geração antes de ser
despachada e a confere junto à gravação curta (`Freio.escrever`), sem segurar o freio durante I/O
do modelo. Assim, uma resposta antiga não grava nem após uma retomada rápida. Tarefas de planilha
carregam a geração até o lock do Assistente e pulam horários vencidos; retomar não executa uma
fila atrasada. O laço confere o freio antes de novos passos e ferramentas. Conversa explícita e
avisos informativos continuam funcionando; o monitor não oferece uma rotina durante o bloqueio.

O diário entra nessas classes por um **construtor a mais, com o campo anulável**: os construtores
antigos continuam e passam `null`, e é isso que mantém os testes que montam esses serviços à mão
compilando sem toque.

## A memória falando normalmente

Mexer na memória não pede id, código nem frase decorada. São quatro caminhos, e o que decide se um
deles pergunta antes é só uma coisa: se o fato **sai de vista**.

| O dono diz | Ferramenta | Aprovação |
|---|---|---|
| "o que você sabe sobre mim?" / "do AgendaBarber?" | `listar_memorias` | não |
| "isso mudou, agora é React" | `buscar_memoria` + `atualizar_memoria` | não (a revisão anterior fica no `.historico`) |
| "não anota isso" | `desfazer_anotacao` | não (foi o Jarvis que gravou, há minutos) |
| "esquece que eu uso Angular no projeto X" | `buscar_memoria` + `esquecer_memoria` | **sim**, `Aprovacoes` com risco `COMUM` |

É a mesma régua de `apagar_arquivo`, e o resultado é o oposto pelo mesmo motivo: apagar arquivo
não pergunta porque a lixeira o traz de volta, e esquecer memória pergunta porque não há lixeira
de onde voltar. `listar_memorias` devolve
`Ficha` — categoria, projeto, assunto e conteúdo cortado em 120 caracteres —, **sem id nem
revisão**: a listagem existe para ser falada, e 20 linhas de 240 caracteres estourariam o
`LIMITE_CONTEXTO`. Id continua existindo e saindo no `buscar_memoria`; ele só não aparece para quem
fala, e o system proíbe o modelo de citá-lo.

**A prova de citação mudou de campo.** `registrar_memoria` e `atualizar_memoria` recebem `evidencia`,
que tem de ser trecho literal do pedido (mesma `Texto.normalizar` do anotador), e o `conteudo` fica
livre. Cobrar o conteúdo literal — como antes — recusava a correção exatamente quando o modelo
escrevia bem: "isso mudou, agora é React" não produzia "Usa React no AgendaBarber". Também não há
mais exigência de palavra na abertura da mensagem, em nenhuma das ferramentas.

**O desfazer é por usuário e tem dez minutos.** O `AnotadorDeMemoria` guarda o lote anotado em dois
mapas: `avisos`, por chat, que o `drenarAviso` **consome** na resposta seguinte, e `desfazeveis`, por
usuário, que o `desfazer_anotacao` consome. Dois porque quando o dono lê "Anotei: ..." e responde
"não anota isso", o aviso já saiu — e por usuário porque a ferramenta recebe um `OuvinteProgresso`,
que tem identidade autenticada e não tem chatId. Fora da janela nada é apagado: a ferramenta manda o
modelo pelo `esquecer_memoria`, que pergunta.

`MemoriaService.desfazerUltimaEscrita` decide **pelo disco**, não por um sinalizador de quem chamou:
se existe a revisão anterior em `.historico`, a anotação foi um *upsert* e a volta é para ela —
apagar levaria embora o fato que já estava lá, que o dono nunca pediu para esquecer. Sem revisão
anterior, o registro nasceu agora e sai pelo caminho permanente do `esquecer`, mais um marcador do
**assunto** em `memoria-exclusoes/usuarios/<id>/assuntos/<sha256>.md`. Esse marcador é o que faz
`anotar` estourar `Recusado` na conversa seguinte; sem ele o anotador reencontraria o assunto e
regravaria o que acabou de ser tirado. Hash e não nome sanitizado porque assunto é texto do usuário.
Um `registrar_memoria` explícito **libera** o assunto: o dono mudou de ideia, e a memória é dele.

**Esquecer continua levando o histórico da conversa junto**, e é o `EsquecimentosDeMemoria` que
segura isso de pé. O `Supplier` do `Aprovacoes` nasce no turno N e roda no turno N+1 carregando o
`OuvinteProgresso` do turno N — marcar `memoriaEsquecida` nele escreveria num campo que ninguém mais
lê, porque quem apaga `historicos`, `conversas` e o arquivo do volume é o `progresso` do turno
**corrente**. Então o `Supplier` deixa o recado, e o `Assistente` o drena logo depois de consumir a
aprovação. `desfazer_anotacao` **não** marca nada: quem recusou uma anotação está no meio de uma
conversa e perdê-la seria susto, não privacidade.

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

### Diário de custo

`ClaudeClient.criarMensagem` recebe uma `Origem` explícita em cada chamada: `CONVERSA`,
`BRIEFING`, `INICIALIZACAO`, `ANOTADOR`, `CLASSIFICADOR`, `BUSCA` (a reserva da `buscar_na_web`,
que pensa no esforço de apoio e não liga o cache da conversa) ou `HOLOGRAMA` (a geração com stream,
no esforço de apoio e num balde próprio: um objeto detalhado pode custar mais que uma conversa, e o
dono precisa ver isso no `custo_do_jarvis`). Os dois provedores de computer use
registram `COMPUTER_USE` ao receber uma resposta de sucesso; `TarefasComputerUse` usa os mesmos
tokens só para o retrato da HUD. O registro de custo acontece uma vez por resposta, depois das
tentativas HTTP. A ferramenta `custo_do_jarvis` consulta hoje, os últimos sete ou os últimos
trinta dias; `MedidorDeCusto.serieDiaria(int)` fornece os pontos para o futuro gráfico.

Ela também fecha com **`Cache: X% lido.`**, que é a taxa de acerto do cache de prompt. O
denominador é o tamanho real do prompt — `entrada + cache_escrito + cache_lido`, porque
`input_tokens` da API é só o resto **não** cacheado. Período sem token de entrada nenhum devolve
`-1` em `Relatorio.percentualLidoDoCache()` e a frase **não sai**: isto é lido em voz alta, e
"0% lido" num dia sem chamada é ruído. Os tokens de cache já eram gravados por dia, por origem e
por modelo desde o começo — o que faltava era o caminho de leitura, e é por isso que não houve
migração de arquivo.

`CUSTO_PRECOS` aceita `modelo=entrada/saida/cache_escrita/cache_leitura`, em dólares por milhão de
tokens. `CUSTO_PRECOS_CONTEXTO_LONGO` aceita `modelo@limiar=...` com os mesmos quatro preços: se
`entrada + cache_escrita + cache_leitura` ultrapassar o limiar, a tarifa longa vale para a chamada
inteira. O Haiku 5.5 padrão tem limiar de 100 mil tokens; o `.env.example` traz as duas faixas,
mas confira os preços atuais na documentação da Anthropic. O cache usa o TTL de
`ANTHROPIC_CACHE_TTL`, padrão de cinco minutos — e o `cache_escrita` configurado **tem de
acompanhar**: escrever custa 1,25× no TTL de 5 min e **2× no de 1 h**, e o campo é um só. Modelo
sem preço configurado mantém os tokens, marca o custo como
incompleto e gera um WARN por modelo por dia. `CUSTO_PRECO_BUSCA_WEB` é por mil buscas, separado
dos tokens.

O medidor grava um `AAAA-MM-DD.json` por dia em `CUSTO_PASTA`, com troca atômica e `.bak`, no
volume `custo`. Mantém hoje e os 89 dias anteriores. Gravações que falham continuam pendentes
pela data original. O teto opcional `CUSTO_TETO_DIARIO_USD` avisa no Telegram em 80% e 100%,
uma vez por limite por dia; os sinalizadores persistem, e o teto nunca bloqueia o Jarvis.

Toda configuração entra nos **três** lugares, na mesma ordem:

1. `JarvisProperties` (record com `@ConfigurationProperties(prefix = "jarvis")`);
2. `src/main/resources/application.yml`, lendo a variável com default: `${MINHA_VAR:valor}`;
3. `.env.example`, com um comentário curto explicando para que serve.

Nada de `@Value` espalhado pelo código, e nada de ler `System.getenv` direto. Um exemplo curto é
o `PC_TOKEN` (`jarvis.pc.token`), usado pelo `TokenPcFiltro`: vazio, `/api/pc` recusa tudo com 401
e o controle do PC fica desligado.

Atenção ao `JarvisProperties`: é um record de 23 componentes, construído **por posição** em mais
de vinte arquivos de teste. Acrescente componente **no fim**, e prefira esticar um dos blocos
aninhados (`Pc`, `ComputerUse`, `Historico`…) a criar outro no topo. Os construtores de
compatibilidade no fim da classe existem justamente para os testes antigos continuarem compilando
— quando criar um componente novo, decida se ele entra num desses atalhos ou se os testes passam
a informá-lo. O `Diario` que virou o 20º é o exemplo do barato: o atalho novo repete a aridade
anterior (19 parâmetros) e passa `null`, e nenhum dos testes que informavam `Custo` precisou mudar.
O `Proativo` que virou o 21º fez o mesmo com a aridade 20, e o `Holograma` que virou o 23º, com a
22 — ele não coube em bloco nenhum existente: provedor, modelo e temperatura são do holograma, não
da IA local nem da Anthropic.

Um detalhe de nome que custa uma hora se for descoberto no bind: `SITES_INTERVALO_MINUTOS` e
`SITES_ACOES` moram em `jarvis.proativo.*`, e **não** em `jarvis.sites.*`. O `jarvis.sites` já é o
escalar com a lista de URLs, e um bloco com o mesmo nome colidiria com ele. Pela mesma razão a
faixa de silêncio vai entre aspas no `application.yml` (`"${PROATIVO_SILENCIO:23:00-07:00}"`): sem
elas o YAML leria os dois-pontos do default como um mapa.

### Os avisos proativos

`PROATIVO_SILENCIO` (a faixa em que nada sai), `PROATIVO_PASTA` (volume `proativo`),
`SITES_INTERVALO_MINUTOS` e `SITES_ACOES` (obsoleto: migrado para gatilhos na partida) são as quatro
do bloco `jarvis.proativo`, mais o sub-bloco `gatilhos` (`GATILHO_MAX_POR_HORA`, padrão 3, e
`GATILHO_ENTRADA_MINUTOS`, padrão 10), que entrou como quinto componente do `Proativo` com o
construtor de compatibilidade de quatro argumentos. Os 30 dias de
retenção do histórico e as **duas** falhas seguidas antes de dar um site como fora são
**constantes** (`HistoricoDeDisponibilidade.DIAS_GUARDADOS` e `MonitorDeSites.FALHAS_PARA_AVISAR`),
pela regra de sempre: não vale um componente num record construído por posição para um número que
ninguém vai mexer — e o 2 não é um ajuste de gosto, é o desenho.

O intervalo é o único número que o `@Scheduled` lê direto do yml
(`fixedDelayString = "${jarvis.proativo.sites-intervalo-minutos:5}"`, em minutos), como o
`jarvis.resumo-diario.cron` já faz. O acessor `intervaloDeChecagemDeSites()` existe para quem
monta as properties à mão, com piso de 1 minuto.

### A IA local

`IA_LOCAL_ATIVA` (padrão `false`), `IA_LOCAL_APOIO` (vazio acompanha a ativa; `false` mantém o
apoio no Claude), `IA_LOCAL_TIMEOUT_SEGUNDOS` (60) e `IA_LOCAL_VRAM_MINIMA_MB` (6000) entraram
**esticando o bloco `Pc`** com um sub-record `IaLocal` como sexto componente, e um construtor de
compatibilidade de cinco argumentos dentro do próprio `Pc` — nenhuma linha de teste precisou
mudar. O `IA_MODO_PADRAO` (`auto`) esticou o próprio `IaLocal` como quinto componente, cru pelo
motivo do `apoio` (valor torto cai em `auto` com um WARN na subida), com o construtor de quatro
argumentos ao lado. O `apoio` é `String` crua pelo motivo do `Esforcos`: vazio tem de significar "o mesmo que
`ativa`", o que um `boolean` não sabe dizer. Os acessores (`apoioLocal()`, `prazoDaIaLocal()`,
`vramMinimaDaIaLocal()`) moram no `JarvisProperties`, com os padrões repetidos lá para quem monta as
properties à mão.

### O TTL do cache de prompt

`ANTHROPIC_CACHE_TTL` (`5m` padrão, ou `1h`) entrou **esticando o bloco aninhado `Anthropic`**, como
quinto componente, com um construtor de compatibilidade de quatro argumentos dentro do próprio
record. É o que o CLAUDE.md manda preferir a criar o 22º componente do `JarvisProperties`, e
**nenhuma** linha de teste precisou mudar — todas montam `Anthropic("chave", "modelo-a", 1024, 512)`.
O acessor `ttlDeCache()` devolve `1h` só quando foi isso que pediram e `5m` em qualquer outro caso:
valor torto no `.env` cai no padrão em vez de virar um HTTP 400 a cada pedido, como nos acessores de
custo.

O campo `ttl` **só é emitido no `1h`**. No `5m` ele é omitido de propósito: é o padrão da API, e
omiti-lo mantém o corpo byte a byte igual ao de antes desta configuração existir — ninguém perde o
cache já quente num deploy.

O mesmo valor vai nos **dois** marcadores (o do bloco fixo e o automático), e isso não é zelo: a API
exige que a entrada de TTL mais longo apareça antes da de TTL mais curto, e marcadores com TTLs
diferentes no mesmo pedido são 400.

### O esforço de saída, por tipo de chamada

`output_config.effort` (`low`, `medium` ou `high` — a API aceita cinco, expomos três) diz quanto o
modelo pensa antes de responder, e é o lado **caro** da conta: ele mexe no token de saída, não no de
entrada. Antes nenhuma chamada o mandava, então todas pensavam no padrão do modelo — `medium` no
Haiku 5.5, mas `high` no Sonnet 5.5, de modo que o patamar de gasto mudava sozinho num
`ANTHROPIC_MODELO` trocado.

São cinco variáveis, e o que separa `medium` de `low` é sempre a mesma pergunta — **o raciocínio
muda a resposta?**:

| Variável | Padrão | Por quê |
|---|---|---|
| `ESFORCO_CONVERSA` | `medium` | o laço de ferramentas escolhe ferramenta, lê resultado e decide o passo seguinte |
| `ESFORCO_BRIEFING` | `low` | os dados chegam prontos e o canal proíbe mudar número ou fato |
| `ESFORCO_INICIALIZACAO` | `low` | idem, e quem bateu palma está esperando na frente do PC |
| `ESFORCO_APOIO` | `low` | classificador e anotador decidem em poucos tokens, e o servidor revalida tudo depois |
| `ESFORCO_TAREFA` | `medium` | é o mesmo laço da conversa, sem ninguém lendo na hora |

**O valor é fixo por origem, nunca decidido pedido a pedido.** Trocar o esforço no meio de uma
conversa invalida o cache de prompt que o passo anterior acabou de escrever — e é por isso que ele é
resolvido **uma vez, no construtor do `ClaudeClient`**, para um `EnumMap` imutável. Esse é também o
único lugar onde o WARN de valor torto sai: na subida, nomeando a variável e o valor recusado. A cada
pedido seria ruído, e o nível já não mudaria mesmo.

**São dois enums, e a diferença entre eles é a parte que importa.** O `Esforco` é o nível (constante
Java, `chave()` para a API, como a `Origem`). O `PerfilDeEsforco` é *qual configuração vale para
esta chamada*, e **não** é a `Origem`: a origem responde "quem gastou" e vai para o JSON do dia, o
perfil responde "quanto pensar". As duas quase coincidem — menos num ponto, que é justo o que obriga
a separá-las: anotador, classificador e busca usam o perfil de apoio, mas têm custos separados.
Tarefas e gatilhos também têm baldes próprios e usam o perfil de tarefa. O `ClaudeClient` deriva o perfil da origem em todas as chamadas, e só o
`Assistente` — o único que conhece o `Canal` — nomeia o perfil, numa sobrecarga de cinco argumentos.
O computer use não tem perfil: monta o corpo do pedido por conta própria.

O bloco entrou **esticando o `Anthropic`**, como sexto componente, num sub-record `Esforcos` de cinco
Strings cruas — uma a mais no topo por variável deixaria o bloco com dez componentes. Os
construtores de compatibilidade de quatro e de cinco argumentos cobrem os dezoito testes que montam
`Anthropic` por posição. Cru porque `ESFORCO_CONVERSA=médio` tem de cair no padrão com um WARN, não
estourar `BindException` e impedir o Jarvis de subir; e o padrão mora também no `PerfilDeEsforco`,
pelo motivo do `validadeDoHistorico()` — quem monta as properties à mão recebe o mesmo valor que o
`.env` entrega.

**Duas consequências de cache, as duas aceitas.** O corpo passa a carregar `output_config` onde não
carregava nada, e isso custa **um** rebuild de prefixo no deploy. Não há como evitá-lo, e o valor
explícito é o ponto: confiar no padrão do modelo faria o esforço da conversa virar `high` sozinho no
dia em que o `ANTHROPIC_MODELO` fosse para o Sonnet 5.5. E como a API trata troca de esforço como
invalidação de `tools` e `system` (o quanto depende do modelo), briefing, introdução e apoio
provavelmente deixam de ler o cache da personalidade escrito pela conversa — eles continuam
partilhando prefixo **entre si**, porque os três são `low`. São ~1,25× a personalidade por
briefing/introdução contra o que se economiza de saída em todas elas.

### A saída média por origem

`MedidorDeCusto` passou a contar **chamadas** por balde, e não só tokens: sem denominador, "tokens de
saída" sobe junto com o uso e não compara nada. O campo `chamadas` percorre o caminho que a taxa de
cache já percorreu — `Uso`, `EstadoDoDia`, `CustoDiarioEmArquivo` (gravar e ler) — e sai como uma
linha de log, `Saída média por chamada em 2026-10-26: conversa 380, briefing 210`.

Ela sai em **dois** pontos: no `virarODia()`, o registro do dia que acabou, e no `fechar()`
(`@PreDestroy`), que é o que roda num `docker compose up -d --build` — então todo deploy deixa no log
o retrato de antes, que é como se mede o efeito de uma troca de esforço sem esperar a meia-noite. No
log e não no `custo_do_jarvis`: aquele relatório é lido em voz alta e já tem sete cláusulas.

**Não há migração de arquivo.** Dia gravado antes do contador volta com `chamadas = 0` e **sai da
frase**, pela regra do `percentualLidoDoCache()`: "0 token por chamada" afirmaria algo que o arquivo
não sabe. Os dois baldes (`porOrigem` e `porModelo`) contam igual, mantendo a invariante do
`EstadoDoDia` — a média falada, porém, sai de `porOrigem`, que é o único que divide por caminho.

### O aviso antecipado de voz

`VOZ_AVISO_PERCENTUAL` (padrão 80, `0` desliga só este aviso) entrou **esticando o bloco aninhado
`Voz`**, como sexto componente, com um construtor de compatibilidade de cinco argumentos dentro do
próprio record. É o que o CLAUDE.md manda preferir a criar o 22º componente do `JarvisProperties`, e
nenhuma das três linhas de teste que montam `Voz` por posição precisou mudar. O acessor
`avisoDeVozPct()` apara para 0-100, como o `reservaDeVozPct()`: um número torto no `.env` não pode
nem calar o aviso para sempre nem fazê-lo sair a cada consulta.

Os 35 dias de uso guardados, a janela de 7 dias da média e os 2 dias mínimos são **constantes** do
`DiarioDeVoz` (`DIAS_GUARDADOS`, `JANELA_DA_MEDIA`, `MINIMO_DE_DIAS`), pela regra de sempre.

### O diário de ações

`DIARIO_PASTA` (`jarvis.diario.pasta`, volume `diario`) é a única configuração do diário. Ao lado
dele, no mesmo bloco, mora `CONCESSAO_TEMPORARIA_MAX_DIAS` (`jarvis.diario.concessao-temporaria-max-dias`,
padrão 7), porque o `concessoes.json` fica nessa pasta. O `Diario` ganhou o construtor de
compatibilidade de um argumento **e** o `@ConstructorBinding` no canônico: record aninhado com dois
construtores sem a anotação fica **nulo** no binding, sem erro — o `Historico` ficou assim até
ganhar a mesma anotação, e os `HISTORICO_*` caíam calados nos fallbacks do `JarvisProperties`. Os 30 dias
de retenção e as 500 ações por dia são **constantes** do `DiarioDeAcoes` e do `DiaDoDiario`, pela
regra que o `MedidorDeCusto` já documenta: não vale um componente num record construído por posição
em dezenas de testes para um número que ninguém vai querer mexer.

### Quanto tempo a conversa dura

`HISTORICO_VALIDADE_HORAS` é **72**, não 24. Com um dia, a conversa de sexta-feira já não existia na
segunda e o assunto que o dono deixou pela metade morria no fim de semana. O número aparece nos três
lugares de sempre **e** no fallback de `JarvisProperties.validadeDoHistorico()`, que é o que vale
para quem monta as properties à mão. O `MAX_TURNOS_HISTORICO` continua 10: o que mudou é por quanto
tempo um turno vale, não quantos entram no prompt.

### O turno antigo encolhe

Guardar dez turnos **inteiros** por 72 h era o que mais pesava na entrada. O array `messages` é
remontado do zero a cada passo do `CicloDeFerramentas`, então um `listar_arquivos` que devolveu o JSON do
agente, ou uma busca com `encrypted_content`, voltava em todos os pedidos seguintes por três dias.

O `CompactadorDeHistorico` troca o turno velho por **duas** mensagens — o pedido e a resposta final,
mais uma nota de até 400 caracteres: `[ferramentas: listar_arquivos → notas.md (id <uuid>); busca na
web: 'preço SSD 1TB']`. Ele é chamado em exatamente dois lugares, os mesmos dois onde o `Deque` já
era mexido: o fim do turno em `responder` (antes de `arquivo.agendar`, para o disco guardar o que o
modelo vai receber) e o `restaurarHistorico`, que é como o arquivo gravado pela versão antiga é
compactado ao carregar. **O turno em andamento não muda**: o laço de ferramentas continua vendo tudo
do próprio turno.

- **Tirar aos pares é o que a API aceita.** Mexer no `encrypted_content`, ou deixar o
  `server_tool_use` sem o `web_search_tool_result`, é HTTP 400; levar os dois juntos não é, porque
  não sobra nada referenciando nada — e vale igual para `tool_use`/`tool_result`. É o que o
  `AssistenteTest.formatoAceitoPelaApi` confere, sem rede: papéis alternando, nada vazio, nenhum
  resultado órfão. O javadoc do `semPensamentos` dizia o contrário e foi corrigido.
- **O identificador vai inteiro.** Os IDs do catálogo são UUIDs de 36 caracteres
  (`ApagarArquivoFerramenta` exige `[0-9a-fA-F-]{36}`), então abreviar convidaria o modelo a mandar
  para `apagar_arquivo` um id que nenhuma ferramenta aceita. Cabe menos na nota; o que não couber é
  contado (`e mais N`), como o teto do `DiaDoDiario`.
- **A nota é montada em Java, de dado real** — nome do bloco `tool_use`, identificador do resultado,
  consulta do `input.query`. É a regra da `pergunta` do `Aprovacoes`: deixar o modelo narrar o que
  ele próprio fez seria deixá-lo escolher o que o Jarvis lembra.
- **Compactar o que já está compacto tem de devolver a mesma coisa**, senão a nota se perde na
  segunda passada e a resposta encolhe a cada restart. Quem resolve é o prefixo fixo
  `[ferramentas: `: a nota é reconhecida na última linha e recolocada, e o corte de
  `HISTORICO_MAX_CARACTERES_RESPOSTA` incide só sobre o texto, nunca sobre ela.
- **`HISTORICO_TURNOS_COMPLETOS` (padrão 1)** é o que deixa "apaga o segundo" e "abre esse"
  funcionando logo depois de uma listagem: o turno imediatamente anterior fica inteiro. Com 0, tudo
  é compactado. Os dois entraram **esticando o bloco aninhado `Historico`**, com o construtor de
  compatibilidade de dois argumentos dentro do próprio record — e `turnosCompletos` é `Integer`
  porque **zero é um valor legítimo**, então o "não positivo vira o padrão" dos outros acessores
  mentiria aqui.

Por pedido sai uma linha `Histórico enviado: N turno(s), M caracteres`. O par dela é o
`Claude: entrada=…` do `ClaudeClient`, que é em **tokens**: um diz o que foi montado, o outro o que
a API cobrou, e é assim que se compara antes e depois junto do `MedidorDeCusto`.

## Economia de tokens

- O `CompactadorDeHistorico` mantém inteiro só o último turno (configurável por
  `HISTORICO_TURNOS_COMPLETOS`); dos anteriores guarda pedido, resposta curta e nota. Pares de
  `tool_use`/`tool_result`, busca web e busca de ferramentas saem juntos. A nota registra consulta
  web somente quando o `server_tool_use.name` é `web_search`.
- O bloco fixo do `system` contém personalidade e um parágrafo constante com as categorias de
  ferramentas. O bloco dinâmico mantém relógio, canal, estado da HUD e o retrato do momento (até 600
  caracteres, só em conversa privada). Cache explícito no fixo e
  automático na conversa usam o mesmo `ANTHROPIC_CACHE_TTL`. O esforço é fixo por origem, não muda
  entre passos do mesmo pedido.
- O `Assistente` envia a busca BM25 `tool_search_tool_bm25_20251119` mais **todas** as definições
  a cada chamada. `Ferramentas` marca `defer_loading: true` fora de
  `FERRAMENTAS_SEMPRE_CARREGADAS` (padrão: `clima,criar_lembrete,listar_lembretes,controlar_pc,`
  `controlar_tela,tocar_youtube`). `editar_arquivo` e `tarefa_no_pc` ficam sempre visíveis;
  `ouvir_resposta` também na HUD. Busca web e o provedor separado de computer use continuam
  carregados. Ferramenta adiada
  não leva `cache_control`; os blocos `server_tool_use` e `tool_search_tool_result` voltam
  intactos no histórico. A ordem das definições Java continua alfabética.
- Cada `Ferramenta` informa `grupo` e `simples` somente no Java, para o futuro roteador local.
  A execução conhecida é contada uma vez ao começar, mesmo se falhar, e `MedidorDeCusto` agrega
  por dia no volume `custo`. `custo_do_jarvis` mostra até dez nomes mais usados no período.
- `RESULTADO_MAX_CARACTERES` (padrão 6.000) limita só `tool_result.content`. O resultado integral
  continua disponível para anexos, eventos e código Java. Texto cortado termina com
  `[cortado; peça a próxima parte]`; JSON longo vira JSON válido resumido com esse aviso,
  identificadores e dados de continuação. Quando um trecho TXT/MD/DOCX é cortado, `proximoInicio`
  avança só até o último caractere visível; em `listar_arquivos`, `pagina` e `tamanhoPagina` são
  recalculados para não saltar itens. `ler_arquivo` limita `maximoCaracteres` ao teto configurado.
  Em PDF, CSV, JSON e XLSX, o resumo não inventa valores nem preserva um cursor que saltaria dados:
  recalcula o deslocamento quando há texto paginado e pede uma releitura menor quando omite itens.
  Não corte o `JsonNode` de origem.
- **A busca na web tem provedor configurável.** `BUSCA_PROVEDORES` (padrão
  `tavily,searxng,anthropic`) é a ordem da ferramenta `buscar_na_web`; provedor sem
  `TAVILY_API_KEY` ou sem `SEARXNG_URL` sai da lista **na partida**, e falhou um, vai o próximo. A
  `AnthropicBusca` é a última reserva: chamada curta (`Origem.BUSCA`, esforço `low`) só com a
  nativa, `max_uses: 1`, e as fontes saem dos `web_search_tool_result`, nunca do texto do modelo.
  Quando o primeiro **configurado** não é `anthropic`, a nativa sai do array `tools`
  (`BuscaWebServidor.ativa()` falso) e entram `buscar_na_web`/`ler_pagina_web` — pelo
  `Ferramenta.oferecida()`, que é de partida pela regra da lista estável; com `anthropic` em
  primeiro, ou sem chave nenhuma, o corpo é o de antes. Resultado: título, URL https (a
  `urlSegura` do `MontadorDeCardDePesquisa` mais o esquema), data e trecho de 600 caracteres, até
  4.000 no total, entre `<<<DADO_DA_WEB`/`>>>FIM_DADO_DA_WEB` (os sinais `<<<`/`>>>` saem do
  conteúdo, para página nenhuma fechar a caixa). Cache de 10 minutos por `Texto.normalizar` da
  consulta, mais quantidade e recência. A `ler_pagina_web` corta em 6.000, abaixo do
  `RESULTADO_MAX_CARACTERES`, para não ganhar um "peça a próxima parte" que não existe.
- **A cota do Tavily é contada por mês, sem contador próprio.** `MedidorDeCusto.registrarBusca`
  grava `"buscas": {"tavily": N}` no dia; `buscasDoMes` soma os dias do mês (os anteriores, uma vez
  por dia). A `CotaDeBusca` avisa pelo `AvisosProativos` ao **cruzar** 80% e 100% de
  `BUSCA_COTA_MENSAL_TAVILY` — cruzamento acontece uma vez por mês com ou sem restart, então não há
  marcador em disco —, e em 100% o Tavily é pulado até a virada. A leitura de página conta um
  crédito (o Tavily cobra um por cinco): a conta erra para o lado seguro.

As duas configurações novas entram nos três lugares de sempre: `JarvisProperties`,
`application.yml` e `.env.example`.

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
- **Ação perigosa (apagar, reiniciar, pagar) passa pelo `Aprovacoes`, e por mais nada.** Não
  invente um segundo caminho de confirmação: a ferramenta chama `pedir(...)` e devolve ao modelo
  apenas "confirme em nova mensagem"; a execução mora no `Supplier`, com a ação e os argumentos
  já capturados, e roda quando o `Assistente` consome a aprovação — fora do alcance do modelo.
  A `pergunta` mostrada ao usuário é montada **em Java, de dado real** (nome da rotina, nome do
  arquivo, objetivo); texto do modelo ali deixaria ele escolher o que o "sim" significa. Como os
  dois pontos de execução passam por um `rodar` só, é ali — e em nenhum outro lugar — que a ação
  aprovada entra no `DiarioDeAcoes`.
  `Risco.CRITICO` é para o irreversível (desligar, reiniciar) e encurta o prazo para 2 minutos.
- **A aprovação é respondida em linguagem natural, e quem interpreta é o servidor.** Não se cobra
  palavra, código nem frase exata: o usuário responde como quiser. Com pendência em aberto — e
  **só** aí, para não gastar nada quando não há nada pendente —, o `Assistente` pega a pergunta com
  `Aprovacoes.pendenciaDe` e passa pergunta e resposta ao `ClassificadorDeResposta`, que tem dois
  caminhos: uma lista de "sim"/"não" de graça para frases de até quatro palavras ("pode", "manda",
  "deixa pra lá") e, para o resto, uma chamada curta ao Claude sem ferramentas, com 60 tokens e 5 s
  de prazo. As cinco saídas: `SIM` chama `executarPendente`, `NAO` cancela e responde "Certo, não
  fiz.", `AJUSTE` cancela e trata a mensagem como pedido novo (uma ressalva muda o que o "sim"
  significaria), `OUTRO_ASSUNTO` deixa a pendência esperando, e `DUVIDA` repete a pergunta.
  **Tudo que dá errado vira `DUVIDA`** — rede, prazo, JSON torto —, porque o preço de uma pergunta
  repetida é baixo e o de um falso "sim" é uma máquina desligada. O modelo principal nunca vê a
  pendência nem a decisão, só o resultado dela: as frases fixas (`confirmo`, `confirmo pc 123456`)
  continuam valendo, mas são atalho, não protocolo.
- **Os três níveis de autonomia** são decididos pela `PoliticaDeAutonomia`, e por mais ninguém.
  O catálogo fechado `AcaoAutonoma` lista o que pode passar pelo `Aprovacoes` (`rotina.rodar[:nome]`,
  `computer_use.iniciar`, `pc.desligar`, `pc.reiniciar`, `arquivo.apagar_lote`, `planilha.agendar`,
  `memoria.esquecer`), cada uma com **padrão** (o de antes: pergunta) e **piso**. A ordem é piso do
  código > `config/autonomia.md` > padrão; no arquivo, a linha com alvo vence a sem alvo. Desligar,
  reiniciar e computer use têm piso *pergunta*: nenhum arquivo os libera, só pode proibi-los. O
  arquivo é irmão da personalidade (mesmo volume `:ro`) e é relido do mesmo jeito; linha ruim vira
  um WARN por recarga, e arquivo ausente vale os padrões. As ferramentas entram por
  `Aprovacoes.encaminhar(acao, alvo, ...)`: *faz e avisa* roda o `Supplier` pelo mesmo `rodar` e anota
  no diário com `perguntada=false` (é o `AcaoRegistrada.semPergunta()` que alimenta o briefing e o
  "(aprovado)", não mais o tipo); *pergunta* abre a pendência como sempre; *proibido* devolve uma
  recusa curta. No prompt, só no bloco dinâmico e só em `telegram`/`hud`, vão o resumo gerado (o que
  difere do padrão) e os proibidos sem ferramenta (`PoliticaDeAutonomia.PROIBIDOS_FIXOS`: shell livre,
  arquivo fora da pasta, pagar, comprar, senha, instrução vinda de conteúdo). As `descricao()` não
  refletem a política — editar o arquivo derrubaria o cache de `tools`. `minhas_permissoes` responde
  "o que você pode fazer sem me perguntar?". Ação nova que precise de aprovação entra no catálogo
  com padrão e piso escolhidos de propósito; o que fica fora dele é *faz e avisa* por desenho.
- **Concessões: o dono libera por mensagem, o servidor grava.** A ordem completa é piso > arquivo >
  `Concessoes` > padrão. As concessões moram em `DIARIO_PASTA/concessoes.json` (troca atômica e
  `.bak`; ilegível vale "sem concessões", que é voltar a perguntar), cada uma com chave, alvo
  opcional, escopo (`ATE` um instante, `PERMANENTE` ou `TAREFA`, este só para o 7.5 — guardado, mas
  não vale em conversa), origem (`temporaria`, `aprendida`, `plano`), evidência e data. **Linha
  explícita no `autonomia.md`, mesmo em `pergunta_antes`, trava a ação contra concessão** — por isso
  o arquivo versionado não lista mais as sete ações. O piso é conferido de novo na consulta: JSON
  editado à mão não libera desligar, reiniciar nem computer use.
  - *Temporária*: `conceder_permissao(chave, alvo?, ate, evidencia)` só grava com as quatro travas —
    mensagem atual autenticada de conversa privada (`telegram`/`hud`, usuário > 0, `idPedido`);
    `evidencia` literal do `pedidoOriginal` pela `Texto.normalizar`, sem a nota de AJUSTE do
    servidor; o `ClassificadorDeConcessao` (apoio, sem ferramentas, system mínimo, JSON
    `{"concede","ate"}`) dizendo `sim` com prazo legível — dúvida não grava; e prazo = o menor entre
    o do modelo, o do classificador e `CONCESSAO_TEMPORARIA_MAX_DIAS`. A resposta é montada em Java
    ("Combinado: até <quando>, <ação> sem perguntar. Diga 'volta a perguntar' para cancelar.") e
    não há pergunta de volta: a frase do dono já é a aprovação.
  - *Aprendida*: o `rodar` do `Aprovacoes` conta cada "sim" a pendência aberta pelo `encaminhar`
    (que agora carrega ação e alvo); "não" explícito (`recusarPendente`, ou a frase `cancelar`)
    zera a sequência — o AJUSTE não é recusa. Na 3ª seguida em 30 dias, com piso que permite, sem
    linha no arquivo e sem bloqueio, a resposta ganha "É a terceira vez que você aprova <ação>. Quer
    que eu pare de perguntar?" e nasce a pendência COMUM `conceder_permanente`, decidida pelo
    `ClassificadorDeResposta` como qualquer outra: sim grava `PERMANENTE`, não cala a oferta por
    30 dias. Oferecer zera a sequência; oferta ignorada volta depois de mais três "sim".
  - *Revogar*: `revogar_permissao` sem aprovação (reduzir autonomia é sempre seguro); com alvo leva
    também a concessão da ação inteira, e revogar cala a oferta por 30 dias. `minhas_permissoes`
    lista as ativas com prazo, e diz "sem efeito" quando o arquivo trava.
  - *Diário*: `CONCESSAO_CRIADA`, `CONCESSAO_USADA` (só o primeiro uso, marcado no `encaminhar`),
    `CONCESSAO_EXPIRADA` (na consulta seguinte ao prazo, ou num tique de 10 min) e
    `CONCESSAO_REVOGADA`. Nenhuma é autônoma: a ação liberada já entra como feita sem pergunta.
- **A VPS só busca URL que ela provou ser pública.** O `abrir_pagina` é a única ferramenta que
  acessa um endereço ditado pelo modelo, e ele passa pelo `GuardaSsrf`: só http/https, DNS
  resolvido e conferido contra loopback, faixas privadas, link-local (inclusive o
  `169.254.169.254` dos metadados de nuvem) e os endereços das interfaces da máquina; no máximo
  3 redirecionamentos, cada um revalidado; 8 s, 1 MB, e nenhum cookie ou cabeçalho de
  autenticação. Resta a janela de DNS rebinding, porque o `HttpClient` do Java não tem gancho de
  resolução — por isso da página só voltam quatro metadados `og:`, nunca o HTML cru.
- **A `ler_pagina_web` só abre URL que a `buscar_na_web` devolveu no mesmo pedido**
  (`UrlsDaBusca`, um par de campos pelo motivo do orçamento da lixeira). E quem busca a página é o
  Tavily, não a VPS — por isso não passa pelo `GuardaSsrf`. O `SEARXNG_URL` pode ser interno à rede
  do Docker porque é configuração do dono; o modelo escolhe a consulta, nunca o destino.
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

## Autonomia

O que o Jarvis faz sem ninguém pedir, ou sem perguntar antes, passa por seis peças. Cada uma tem a
própria seção neste arquivo; aqui fica o mapa de como elas se encaixam, e a sétima peça, que só
existe nesta seção.

| Peça | Responde | Onde mora | Detalhe |
|---|---|---|---|
| **Política** | esta ação é *faz e avisa*, *pergunta* ou *proibido*? | `PoliticaDeAutonomia` + `config/autonomia.md` | "Os três níveis de autonomia", em Segurança |
| **Concessões** | o dono liberou isto por mensagem? | `Concessoes`, `DIARIO_PASTA/concessoes.json` | "Concessões", em Segurança |
| **Tarefas de fundo** | o pedido longo continua sem a conversa esperar? | `TarefasDeFundo`, `DIARIO_PASTA/tarefas.json` | "Tarefas de fundo" |
| **Gatilhos** | o que fazer quando um evento acontecer? | `MotorDeGatilhos`, `gatilhos.json` | "Gatilhos" |
| **Freio** | o trabalho autônomo pode andar agora? | `Freio`, `DIARIO_PASTA/freio.json` | "O freio global" |
| **Diário** | o que foi feito, e como desfazer? | `DiarioDeAcoes`, volume `diario` | "O que o Jarvis fez, e o dono não viu" |

- **A ordem da decisão é uma só**: piso do código > `config/autonomia.md` > concessões > padrão do
  catálogo (`AcaoAutonoma`). Desligar, reiniciar e computer use têm piso *pergunta*: nada os libera,
  só os proíbe. Linha explícita no arquivo trava a ação contra concessão.
- **Toda aprovação passa pelo `Aprovacoes`**, e é no `rodar` dele que a ação aprovada roda e entra no
  diário. *Faz e avisa* roda pelo mesmo `rodar`, com `perguntada=false`.
- **Tarefa e gatilho não ganham permissão por serem automáticos.** O plano da tarefa passa inteiro
  pela política antes de começar (o recibo `TAREFA` vale só ali dentro), e cada disparo de gatilho
  vira uma tarefa de fundo de origem `GATILHO`, com as mesmas regras. Tarefa não cria gatilho.
- **O freio está acima de tudo que é autônomo**: pausa manual ou teto (`CUSTO_TETO_AUTONOMO_USD`,
  baldes `anotador`, `tarefa` e `gatilho`) param tarefas, gatilhos, anotador e revisor. A conversa
  explícita continua — o dono pedindo não é autonomia.
- **O diário é a testemunha, não o guarda**: o registro vem depois da ação e nunca a derruba; cada
  linha tem `id`, usuário e a capacidade tipada de desfazer, emitida pelo servidor.

### O retrato do momento

O `RetratoDoMomento` monta o que está acontecendo **agora**, para "lê aquele arquivo de novo",
"continua de onde parou" e "como está aquilo?" funcionarem sem o dono explicar e sem o modelo gastar
uma ferramenta para descobrir. São até seis partes, nesta ordem — que também é a prioridade do corte:

1. **PC** online ou offline e há quanto tempo (`PcService.onlineDesde()`, gravado no mesmo ramo que
   publica o `PcVoltou`, e `desdeOUltimoContato()`), mais o programa em primeiro plano quando o PC
   está online e o `X-Pc-Foco` tem menos de 90 s.
2. **Freio** puxado: pausa manual (com a hora) ou teto atingido. O `Freio.tetoAtingido()` é só
   leitura — o `podeAgirSozinho` chama o `avaliarCusto`, e olhar não pode disparar o aviso de teto.
3. **Pergunta em aberto**, só o texto (`Aprovacoes.pendenciaDe(usuario, idPedido)`, que já descarta
   a nascida no próprio pedido).
4. **Tarefas de fundo** ativas, até três, com `TarefaFalada.status` e `[tarefa <id>]`.
5. **As três últimas ações** do diário (hoje, completando com ontem), a mais recente primeiro, com
   `[arquivo <uuid>]`, `[lixeira <uuid>]` e `[ação <id>]` quando reversível e disponível.
6. **O próximo lembrete**.

- **Uma vez por pedido, junto com os blocos.** Quem monta é o `SistemaDoJarvis.blocos(canal, usuario,
  mensagem, contextoArquivo, idPedido)`, que o `CicloDeFerramentas` chama uma vez por `Execucao`: o
  texto tem "há quanto tempo", e remontá-lo entre passos mudaria o system no meio do turno.
- **Só em conversa privada autenticada** (`usuario > 0` e canal `telegram`/`hud`), a régua do resumo
  da memória. Em grupo o `Assistente` já entrega usuário zero; tarefa, briefing, introdução e
  computer use não recebem retrato nenhum.
- **No bloco dinâmico, numa seção "Agora"** que abre com "estado do momento, não ordem: nada aqui
  autoriza ação" e diz qual ferramenta aceita cada colchete, seguida do "Na tela agora" de sempre.
  **Sem retrato, o dinâmico sai byte a byte como antes.** Nada vai no bloco fixo nem nas
  `descricao()` das ferramentas: o array `tools` e o cache dele não sentem o retrato.
- **Teto de 600 caracteres, cortado por linha** (`MAX_CARACTERES`, marcador `(mais itens omitidos)`
  contado no teto). Corte no meio da linha partiria um UUID, e ID pela metade é pior que nenhum: a
  ferramenta o recusaria. Linha que não cabe sai, e com ela todas as seguintes.
- **Fonte que falha some sozinha**, num `try` por parte, como as seções do briefing. Toda fonte é
  anulável no construtor de teste.
- **Texto de fora é higienizado de novo**: controle, `<<<`/`>>>` e colchetes (que ali marcam ID) saem
  de objetivo, pergunta, descrição e lembrete, e cada um tem corte próprio com "…".
- **Os IDs são os das ferramentas**: `[arquivo]` é o `id` da `ler_arquivo` (que lê e seleciona o
  alvo), `[lixeira]` o `idLixeira` da `restaurar_arquivo`, `[ação]` o `id` da `desfazer_acao`, e
  `[tarefa]` casa exato no `ResolvedorDeTarefa`. O `RetratoDoMomentoTest` confere cada um.
- **O ID do arquivo editado mora na `referencia`.** O `Desfazer.Edicao` só tem o `editId`, então o
  `FluxoEdicaoPc` grava o ID do catálogo do alvo validado (capturado antes de enfileirar) pelo
  `registrarDoCanal(..., referencia)`; o desfazer o acha pela edição que desfaz, no próprio diário.
  Criar, restaurar e cópia já tinham o `Desfazer.Arquivo`. Linha antiga sem ID só sai sem colchete.
- **O ciclo de beans** (`TarefasDeFundo` → `CicloDeFerramentas` → `SistemaDoJarvis` → retrato) é
  quebrado por um `ObjectProvider<TarefasDeFundo>`, resolvido na hora do pedido.

Parte nova do retrato toca dois lugares: o método da linha no `RetratoDoMomento.montar`, na posição
da prioridade, e — se ela trouxer um ID — a frase de IDs em `PromptDeSistema.aSecaoAgora`.

## Tarefas de fundo

`dev.jarvis.tarefas.TarefasDeFundo` atende pedidos longos fora do lock do `Assistente`, usando
`CicloDeFerramentas.Execucao`. O ciclo não tem lock próprio: cada execução guarda em memória o
system, as definições, as assinaturas repetidas e o cursor do lote de ferramentas. Uma suspensão
preserva o cursor e a continuação; retomar não repete as ferramentas anteriores. O caminho antigo
do Assistente continua com oito passos. O system permanece o mesmo objeto em toda a execução,
inclusive depois de uma espera ou pausa.

`iniciar_tarefa_de_fundo` encerra o turno com metadado do servidor, sem uma chamada extra ao modelo.
Objetivo, plano, ações previstas, estado, resumo, resultado, instantes, passos, tempo ativo e custo
ficam em `DIARIO_PASTA/tarefas.json`, com troca atômica e `.bak`; mensagens e Suppliers não vão ao
disco. As configurações `TAREFAS_SIMULTANEAS` (2), `TAREFA_MAX_PASSOS` (30),
`TAREFA_MAX_MINUTOS` (20) e `TAREFA_ESPERA_PC_HORAS` (12) vivem em `jarvis.diario.tarefas` e nos três lugares habituais. Esperas
não contam como tempo ativo. Não há teto monetário individual: o teto diário autônomo pausa pelo
Freio; passos ou tempo excedidos falham com motivo.
Tarefas encerradas ficam consultáveis por até 30 dias, limitadas às 100 mais recentes; as ativas
não são expurgadas. O histórico de modelo e os anexos em memória são liberados ao encerrar.

O plano passa inteiro pela política antes de iniciar. Uma proibição recusa tudo. Quando precisa,
há uma aprovação agrupada, com prazo CRITICO de dois minutos se houver energia do PC. O recibo
`Concessoes.concederPlano`, escopo TAREFA, pode satisfazer a pergunta de uma ação com piso dentro
daquela tarefa; isto não baixa o piso da política nem libera concessões temporárias/permanentes.
Proibição e revogação posteriores continuam valendo. Recibos são invalidados antes de publicar o
estado terminal. Ação imprevista pergunta normalmente. Aprovações de tarefa aguardam a vez em
FIFO, sem substituir a pendência existente, e o prazo nasce quando a pergunta é apresentada.
O “sim” na conversa apenas autoriza; a ação capturada roda no executor da tarefa, pelo `rodar`
central do `Aprovacoes`.

`aguardar_tarefa`, oferecida somente em TAREFA e executável somente com contexto interno, espera
PC, operação real do PC, site da configuração ou horário ISO-8601. Não há polling do modelo.
Computer use vinculado mantém seus limites próprios e entrega o resultado à tarefa, sem aviso
final duplicado. O `ContextoDaTarefa` é instalado e removido pelo executor: isola URLs e orçamento
da lixeira, identifica leituras, atribui custo a `tarefa` e diário a `tarefa:<id>`, inclusive nas
threads de computer use. Nunca aceite esse contexto da entrada JSON do modelo.

O Freio interrompe e pausa as execuções; retomar só reenfileira quando a autonomia está liberada.
Na partida, RODANDO volta à fila com aviso de retomada e resumo, preservando orçamento. Comandos
pendentes têm IDs persistidos e são reconciliados antes de novas ações; aprovação perdida é
perguntada novamente. O resultado solicitado sai imediatamente no canal de origem, com fallback
da HUD desconectada ao Telegram e anexos. Origem GATILHO usa `AvisosProativos`; o mecanismo de
gatilhos não faz parte deste pacote.

### Conferir antes de concluir, e uma segunda tentativa

A tarefa de fundo só vira `CONCLUIDA` pelo `concluir_tarefa(resultado, como_conferi)` (só no canal
`tarefa`, sempre carregada). Terminar o laço falando, sem ele, é `FALHOU` com
`TarefasDeFundo.NAO_CONFIRMOU` ("Não confirmou o resultado.") mais o texto do modelo. Quem decide se
a conclusão pode rodar é o `CicloDeFerramentas`, que viu a sequência: o estado mora numa
`ConferenciaDoResultado` por `Execucao`, ativa só com `Canal.TAREFA` **e** `idTarefaDeFundo` — a
conversa, a tarefa de planilha e a tarefa agendada antiga não mudam.

- **`Ferramenta.mudaEstado()`** (padrão `false`; `mudaEstado(entrada)` para quem depende da entrada,
  como a `controlar_pc`, cujas ações de consulta não mudam nada) marca o que mexe fora da conversa:
  arquivos, rotina, computer use, PC, planilha, lembretes, memória, gatilhos, permissões e o
  `desfazer_acao`. É só Java: não entra na definição nem no cache.
- **O portão** vem depois do de repetição: depois da última mudança, sem uma ferramenta de leitura
  que rodou sem `is_error` (fora de `NAO_CONFEREM`: concluir, aguardar, tela, holograma...), o
  `concluir_tarefa` volta `is_error` com "Antes de concluir, confira o resultado (por exemplo com
  <sugestão>)." — a sugestão sai do mapa `SUGESTOES`. Conferida, falta ainda o `como_conferi`.
  Tarefa sem mudança conclui direto. O texto do aviso final é montado pela ferramenta
  ("Organizei 14 arquivos em 4 pastas. Conferi listando a pasta de novo.") e entra no diário como
  `TAREFA_CONCLUIDA`; a conclusão não é passo (`PASSO_DE_TAREFA`). Operações pendentes que terminam
  depois (`conferirResultados`) contam como conferência e desfazem a conclusão: o modelo conclui de
  novo, e a nota entra na mesma mensagem do `tool_result` em vez de duas do usuário seguidas.
- **A falha é estruturada**: `ResultadoFerramenta.falhou` (`falha(texto)`, `comoFalha()`), marcada
  na origem, onde o `ok` do agente é conhecido — continuação da rotina, `controlar_pc` recusado,
  edição, criar/apagar/restaurar arquivo, computer use `falhou` — ou o `is_error` de exceção. **Não**
  são falha: recusa da política, pergunta aberta, PC offline e validação de entrada; "tente outro
  caminho" depois de "a configuração proíbe" convidaria o modelo a contornar a política.
- **Duas falhas seguidas de mudança** (uma mudança que dá certo zera): na primeira o `tool_result`
  ganha "tente outro caminho uma vez, se houver"; na segunda o ciclo lança a pergunta do gancho
  `perguntarAoFalhar`, que a `TarefasDeFundo` liga ao `Aprovacoes.perguntarNaTarefa` (tipo
  `segunda_tentativa`, fora do `tipoNoDiario`). A frase é montada em Java ("Tentei a e b, e os dois
  falharam (motivo). Quer que eu tente outra coisa ou deixo assim?") e a tarefa fica
  `ESPERANDO_VOCE`. "Sim" volta sem reexecutar a ferramenta, com a falha mais a nota do dono; "não"
  é o "Certo, parei <objetivo>." de qualquer pergunta recusada.

Ferramenta nova que muda estado declara `mudaEstado`, marca a falha na origem e, se houver como
conferir, ganha uma linha no `SUGESTOES`.

### Acompanhar, pausar e esperar sozinho

Quatro ferramentas da conversa, fora do canal `tarefa`: `tarefas_em_andamento` (objetivo, status em
português, passo atual e há quanto tempo; as encerradas nas últimas 24 h no fim), `cancelar_tarefa`
(sem aprovação; a resposta lista o que ficou no diário, com o id do `desfazer_acao` onde houver, e o
"Cancelada:" final não sai para não ecoar), `pausar_tarefa` e `retomar_tarefa`. A entrada `tarefa` é
id ou palavras do objetivo, e quem acha é o `ResolvedorDeTarefa`: vazio ou só dêitico ("isso",
"ela") com **uma** ativa é ela; com várias, a ferramenta lista e não faz nada. A identidade vem do
pedido, nunca da entrada. O texto é do `TarefaFalada`.

A pausa do dono é o campo persistido `pausa` (`dono` ou `sem_resposta`), separado do `pausar`
volátil do Freio: o `varrer` zera o `pausar` a cada volta, e só o `retomar` limpa a `pausa`. Rodando,
a tarefa para na próxima fronteira (passo ou ferramenta); operação já entregue ao PC continua.

**O PC voltou é um evento** (`dev.jarvis.pc.PcVoltou`), e quem publica é só o `PcService.registrarContato`:
o E2 o chama por `OperacoesPcService.aoContato`, então os dois caminhos são um gatilho e um evento.
Sai no primeiro contato da partida e no contato depois de mais de 90 s (`LIMITE_SEM_CONTATO`). O
`EsperasDaTarefa` só guarda o instante; a espera `PC` fica pronta com um retorno **igual ou
posterior** ao início dela, o que fecha a corrida entre a checagem de offline e o contato que chega
logo depois. A `computer_livre` continua olhando o estado direto. Ao retomar sai "O PC voltou;
continuei <objetivo>.". `TAREFA_ESPERA_PC_HORAS` (12) conta de `esperaDesde`, persistido; passou,
FALHOU com o motivo. Retomar a pedido do dono zera a conta.

**Ferramenta que responde "PC offline, nada iniciado" usa `ResultadoFerramenta.pcOffline`** — na
conversa é só o texto; em tarefa, o `ContextoDaTarefa.esperarPcSeEmTarefa` lança uma
`SuspensaoDaTarefa` com `repetirFerramenta`. O ciclo não guarda continuação, desconta a assinatura
repetida e, na volta, roda a ferramenta de novo: o modelo nunca vê o offline e o passo não entra no
resumo como falha. Use só onde **nada** começou; o que já foi despachado espera o resultado.

Na pergunta de uma ação dentro da tarefa (`SuspensaoDaTarefa.Pergunta`): aprovada continua; recusada
cancela com "Certo, parei <objetivo>."; expirada ou tomada por outra pergunta (`SEM_RESPOSTA` na
`AutorizacaoDaTarefa`) fica PAUSADA com `pausa=sem_resposta` e aparece como "esperando sua resposta";
o `retomar_tarefa` chama `renovar()`, e a mesma pergunta volta com código e prazo novos.

`aguardar(minutos 1–60, motivo)` só existe no canal `tarefa` (`Ferramentas.SO_EM_TAREFA`) e só roda
com contexto interno: uma espera `HORARIO` com o motivo no `esperaAlvo`. É agendamento, não sleep —
a suspensão libera o trabalhador e quem acorda é o `varrer`, pelo `Clock`. O progresso da tarefa vai
à HUD conectada como `conversa.ferramenta` com id `tarefa:<id>` (`RegistroConversas.progressoDeTarefa`);
a HUD de hoje descarta id que não é o do pedido pendente, então aparecer lá depende de ajuste no
jarvis-hud. No Telegram não sai nada por passo: só o aviso final, as perguntas e o "PC voltou".

## Gatilhos

O dono dita a regra na conversa ("quando a barbearia cair, pergunta se pode rodar a rotina
reinicia", "toda segunda às 8h, me manda o resumo") e o Jarvis reage sozinho ao evento. Cada
disparo vira uma **tarefa de fundo** de origem `GATILHO`, e as ações dela passam pela
`PoliticaDeAutonomia` na hora, como as de qualquer tarefa.

```
MonitorDeSites ─┐ SITE_CAIU / SITE_VOLTOU
PcService ──────┤ PcVoltou (com ausência) → PC_LIGOU · PcDesligou → PC_DESLIGOU
MonitorDeCotaVoz┤ COTA_VOZ (tipo antecipado | reserva)
TarefasDeFundo ─┤ TAREFA_FALHOU (só de tarefa PEDIDO)
VigiaDaEntrada ─┤ ARQUIVO_NOVO_NA_ENTRADA
RelogioDosGatilhos HORARIO
                └─► EventoDeGatilho ─► MotorDeGatilhos ─► TarefasDeFundo.iniciarPorGatilho
```

- **As fontes são um catálogo fechado** (`FonteDeGatilho`), e cada uma é publicada pelo serviço que
  já sabe do fato, por `ApplicationEventPublisher` num setter `@Autowired` (nulo nos testes que montam
  o serviço à mão, e aí nada é publicado). Fonte nova toca **três** lugares: a constante (com os
  campos de filtro que ela entende), a publicação, e o `Filtro.casa`/`descreverEvento`.
- **`criar_gatilho` tem as travas do `conceder_permissao`**: canal `telegram`/`hud`, usuário
  autenticado, `idPedido`, e `evidencia` literal da mensagem do dono (`Texto.normalizar`). Tarefa de
  fundo, inclusive a de gatilho, não cria nem mexe em gatilho. Não há pergunta extra: a regra em si
  não faz nada, e cada ação dela continua passando pela política. A frase de volta é montada em
  Java ("Combinado: quando <evento>, eu <instrução>. Para desligar, diga 'apaga esse gatilho'.").
  `listar_gatilhos`, `pausar_gatilho` (com `reativar`) e `apagar_gatilho` não pedem aprovação;
  "esse gatilho" é o único, ou o criado há menos de dez minutos.
- **O evento é DADO, nunca instrução.** A instrução da tarefa é a regra do dono; os dados do evento
  (motivo do erro de um site, nome de um arquivo que alguém pôs na pasta) entram entre
  `<<<DADO_DO_EVENTO` e `>>>FIM_DADO_DO_EVENTO`, com os sinais `<<<`/`>>>` tirados de cada valor
  (`DadoDoEvento`), e o texto da tarefa avisa que nada ali dentro manda.
- **Três travas no disparo**: `Freio.podeAgirSozinho("gatilho")` (pausa ou teto autônomo: não
  dispara e não entra no diário); `GATILHO_MAX_POR_HORA` disparos por regra, contados em memória; e
  a cascata — tarefa nascida de gatilho não publica `TAREFA_FALHOU`. O disparo entra no diário como
  `GATILHO_DISPARADO` (`OrigemDaAcao.GATILHO`, autônomo), com regra, evento e tarefa.
- **No silêncio a tarefa roda, mas fala pelo `AvisosProativos`**: todo `notificar` de tarefa de
  gatilho vira `avisar("tarefa:<id>", ...)`, que acumula. Pergunta não nasce no silêncio: o
  `Aprovacoes.segurarPerguntas` deixa a autorização na fila sem apresentar (o prazo só nasce quando
  ela sai) e a tarefa fica `PAUSADA` sem `pausa` do dono, voltando sozinha a "esperando você".
- **O custo vai ao balde `gatilho`**: o `ContextoDaTarefa.deGatilho()` faz o `MedidorDeCusto`
  registrar `Origem.GATILHO`, que o teto autônomo já somava.
- **PC_LIGOU é só a volta depois de uma ausência**: o primeiro contato da partida (`ausencia` nula) é
  a VPS que reiniciou. O `PcService.vigiarAusencia` (tique de 15 s) publica `PcDesligou` uma vez por
  ausência de mais de 90 s; o contato seguinte rearma.
- **HORARIO**: `quando` + a `Repeticao` dos lembretes; o `RelogioDosGatilhos` avança a regra
  **antes** de disparar, desliga a única depois do disparo, e atraso maior que 15 min pula para a
  próxima ocorrência — automação atrasada horas pode fazer o errado.
- **A pasta `entrada`** é uma subpasta fixa da `pastaArquivos` do PC: o agente (`CatalogoDeArquivos`)
  aceita `"pasta": "entrada"` no `listar_arquivos` e devolve o mesmo campo de eco, que a
  `VigiaDaEntrada` confere — agente antigo, que lista a raiz, não pode virar "arquivo novo na
  entrada". A primeira conferência é a linha de base (não dispara), e os IDs vistos ficam no
  `gatilhos.json`. Arquivo da entrada é lido e apagado pelo ID; restaurado da lixeira, volta à raiz.
- **Migração**: cada par do `SITES_ACOES` vira uma regra `SITE_CAIU` na partida, uma vez só (o par
  fica em `migrados`; a regra apagada pelo dono não volta).

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

- **O que sobrevive ao restart tem volume próprio**: lembretes no JSON de `LEMBRETES_ARQUIVO`
  (volume `lembretes`), reagendados na partida com aviso de "Atrasado:" para o que venceu no meio;
  histórico da conversa em `HISTORICO_PASTA` (volume `historico`), um `chat-<id>.json` por chat,
  descartando turno mais velho que `HISTORICO_VALIDADE_HORAS`; memória pessoal em `MEMORIA_PASTA` e
  `MEMORIA_PASTA_EXCLUSOES`; o diário do E2 do PC em `PC_OPERACOES_PASTA` e, ao lado dele, o
  `catalogo.json` com os apps e as rotinas que o PC anunciou — não para a máquina funcionar, mas
  para o array `tools` nascer igual depois de um restart e o cache de prompt não ser reconstruído
  (e, também ali, o `modo-ia.json` com o modo do seletor);
  o cache de voz em
  `/data/voz` e o uso de voz por dia em `/data/voz/uso` (35 dias, mais o marcador do período já
  avisado); o custo em `CUSTO_PASTA` (volume `custo`), um JSON por data por 90 dias; e o que o
  Jarvis fez em `DIARIO_PASTA` (volume `diario`), um JSON por data por 30 dias, mais o
  `ultimo-briefing.json` que marca até onde a seção "o que fiz sozinho" já contou, o `freio.json` e
  o `concessoes.json` com as permissões que o dono deu, e o `gatilhos.json` com as regras, os pares
  do `SITES_ACOES` já migrados e os IDs da pasta `entrada` já vistos; e a
  disponibilidade dos sites em `PROATIVO_PASTA` (volume `proativo`), um JSON por data por 30 dias; e
  a galeria de hologramas em `HOLOGRAMA_PASTA` (volume `holograma`), um JSON e um PNG por objeto, até 60.
  Todos usam a troca atômica do `ArquivoAtomico`.
- **O diário guarda até 500 ações por dia.** Passando disso ele conta as descartadas e a
  `acoes_do_jarvis` avisa que a lista está incompleta — não há paginação, e o que foi recusado não
  volta. Um dia honesto tem dezenas; 500 é laço de ferramenta, não trabalho.
- **O histórico da conversa vale 72 h**, e são ainda `MAX_TURNOS_HISTORICO` (10) turnos por prompt.
  Do que o modelo vê, porém, só os últimos `HISTORICO_TURNOS_COMPLETOS` (1) vêm inteiros: dos
  anteriores sobram o pedido, a resposta cortada em 1500 caracteres e a nota do que foi usado.
  Resultado de ferramenta e busca de pedido antigo **não voltam** — quem quiser o detalhe de novo
  tem de chamar a ferramenta outra vez, e é esse o preço combinado.
- **A fila do silêncio e a memória de "o que eu já avisei" são em memória.** Um restart no meio da
  madrugada perde o que o `AvisosProativos` tinha acumulado para entregar às 7h, e o
  `MonitorDeSites` volta sem saber que um site já estava fora — então ele precisa de duas checagens
  novas para avisar outra vez. O que **não** se perde é o histórico de disponibilidade, que está no
  volume `proativo`, nem o uso de voz e o período já avisado, que estão em `/data/voz/uso` — o aviso
  de cota é o único que não repete depois de um restart, porque o marcador dele é de disco.
- O retrato da tela, o cache de buscas no YouTube e na web, a fila de comandos do PC, a fila de mensagens do
  Telegram e as confirmações pendentes são **em memória**: depois de um restart o Jarvis fica cego
  para a tela até a HUD repostar o estado, qualquer confirmação pendente se perde e o que estava
  esperando a vez na fila do Telegram morre com o processo. O que **não** se perde mais é o catálogo
  de apps e rotinas do PC, que foi para o disco justamente porque ele entra no array `tools`.
- No Telegram só entende texto (nada de áudio ou foto). Na HUD, quem transcreve e sintetiza é o
  navegador — ou a ElevenLabs, quando `VOZ_PROVEDOR=elevenlabs`.
- **Atende uma mensagem por vez — mas isso vale só para a conversa.** `Assistente.responder` segura
  um `ReentrantLock` **justo** (`new ReentrantLock(true)`) pelo turno inteiro, e conversas do Telegram
  e da HUD, tarefas agendadas antigas e limpeza passam pelo mesmo lock. O retrato público do
  histórico usa um bloqueio curto separado, que nunca é mantido durante I/O do modelo. O que é
  autônomo não espera a conversa: tarefas de fundo (`TAREFAS_SIMULTANEAS`, padrão 2), os disparos de
  gatilho (que viram tarefas de fundo) e o computer use têm executores próprios, fora desse lock, e a
  introdução da HUD também fica de fora.

  **Mas ler não espera mais atender.** No `TelegramPoller` são duas threads: a de leitura só faz
  `getUpdates`, confere o usuário e enfileira; quem fala com o `Assistente` é um trabalhador só
  (`LinkedBlockingQueue`, FIFO), então a ordem e o "um pedido por vez" continuam valendo. Mensagem
  que chega durante um pedido entra na fila e a **primeira** da rajada recebe "Recebi. Respondo
  assim que terminar o pedido anterior.".

  Uma frase curta de parada — `PedidoDeParada`: até 3 palavras, uma raiz (`para`, `pare`, `parar`,
  `cancela`, `cancelar`, `espera`, `espere`, `deixa`) e nada além de enfeite conhecido — **não entra
  na fila**: ela marca o pedido em andamento (`Assistente.interromperPedidoAtual`) e cancela a
  tarefa de computer use do mesmo usuário, que roda fora do lock. O `CicloDeFerramentas` confere a marca
  no começo de cada passo e antes de cada ferramenta, e encerra o turno com "Parei, senhor." sem
  executar mais nada; como no turno com erro, esse turno **não** entra no histórico, não vai ao
  disco e não chega ao anotador. Sem nada em andamento, `interromperPedidoAtual` devolve `false` e a
  mensagem segue o caminho normal — "para" pode ser a resposta a uma aprovação pendente, e quem
  decide isso é o `ClassificadorDeResposta`.
