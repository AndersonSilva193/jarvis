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
| `dev.jarvis.telegram` | `TelegramClient` (Bot API) e `TelegramPoller` (leitura + filtro de usuário numa thread, fila e um trabalhador na outra) |
| `dev.jarvis.ia` | `Assistente` (loop de agente e histórico), `ClaudeClient`, `PersonalidadeService` (lê e recarrega o arquivo), `SistemaDoJarvis` (monta o `system` de qualquer chamada), `PromptDeSistema`, `Canal`, `ComandoDeRepouso`, `PedidoDeParada` (a frase curta que corta o pedido em andamento), `ErroDaApiDoModelo` e `BuscaWebServidor` (a definição da busca na web, que a Anthropic executa) |
| `dev.jarvis.ferramentas` | A interface `Ferramenta`, o registro `Ferramentas` e cada ferramenta |
| `dev.jarvis.briefing` | `BriefingService` (junta as fontes e monta o texto do dia) e `NarradorDeBriefing` (lê o briefing pronto com a personalidade) |
| `dev.jarvis.inicializacao` | `InicializacaoService` e `Introducao`: a abertura falada da HUD quando a tela acende |
| `dev.jarvis.briefing.fontes` | As fontes: `ClimaService`, `NoticiasService`, `SitesService`, `ServidorService` |
| `dev.jarvis.lembretes` | `LembreteService` (agenda, dispara e reagenda na partida), `LembretesEmArquivo` (o JSON com troca atômica e `.bak`), `Recorrencia`/`Repeticao` |
| `dev.jarvis.agenda` | `ResumoDiario`: tarefa `@Scheduled` do bom dia |
| `dev.jarvis.memoria` | `MemoriaService` (registros Markdown por usuário, nos volumes `memoria` e `memoria-exclusoes`), `AnotadorDeMemoria` (o que o Jarvis anota sozinho) e `EsquecimentosDeMemoria` (o recado de que um esquecimento aprovado aconteceu) |
| `dev.jarvis.conversa` | Execução assíncrona da HUD (`ConversaService`), retratos públicos do histórico (`RegistroConversas`), `HistoricoEmArquivo` (o JSON por chat), progresso, `Confirmacao`, `EscutaResposta` e `AnexosService` |
| `dev.jarvis.computer` | O computer use: `TarefasComputerUse`, o `DetectorDeTravamento` (encerra a tarefa que anda em círculos) e os provedores `ProvedorAnthropicComputerUse` / `ProvedorOpenAiComputerUse` por trás de `ProvedorComputerUse` |
| `dev.jarvis.google` | `GooglePlanilhasService`: OAuth e criação de planilha nativa (opcional) |
| `dev.jarvis.config` | `BeansConfig` (beans compartilhados) e `JarvisProperties` (toda a config) |
| `dev.jarvis.custo` | `MedidorDeCusto` (soma por origem e modelo, alertas), `TabelaDePrecos` (faixas configuradas) e `CustoDiarioEmArquivo` (JSON diário no volume `custo`) |
| `dev.jarvis.voz` | A voz da HUD: `ProvedorVoz`/`ElevenLabsVoz`, `CacheDeVoz` (MP3 em disco), `MonitorDeCotaVoz` (reserva e volta ao navegador), `TextoParaVoz` e `VozService` |
| `dev.jarvis.tela` | A tela dirigida pelo Jarvis: `CardTela`/`DadosDoCard`/`ComandoTela`, `TelaEvento`, `EstadoDaTela`/`TelaEstadoService` (o retrato da HUD), `MontadorDeCardDePesquisa`, `YoutubeService`, `PaginaService`, `GuardaSsrf`, `FalaDaTela`/`FalaDaTelaService` (o catálogo fechado de frases) e a porta `PresencaDaHud` |
| `dev.jarvis.pc` | O PC Windows: `PcService` e `OperacoesPcService` (as filas de comando), `ComandoPc`/`ResultadoPc`, `ArquivosPcService` e `FluxoEdicaoPc` (arquivos e edição), `TarefasPlanilhaService` e `AvisosOperacoesPc` |
| `dev.jarvis.aprovacao` | `Aprovacoes`: a aprovação humana de uso único por onde passa **toda** confirmação, com o nível `Risco` que define o prazo |
| `dev.jarvis.web` | API da HUD e do agente do PC: controllers, SSE (`EventosHud`), os dois filtros de token e CORS |
| `dev.jarvis.util` | `Json` (leitura defensiva de campos que podem não existir, e `canonico` para comparar dois JSONs), `ComparadorDeToken` (comparação de segredo em tempo constante), `ArquivoAtomico` (gravação com troca atômica e `.bak`) e `Texto` |

### A mesma ferramenta, a mesma entrada, de novo

O `executarLoop` conta as chamadas por **assinatura** — nome da ferramenta mais `Json.canonico` da
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

## O PC Windows pelo Telegram

Edição de arquivos comuns: `editar_arquivo` aceita apenas IDs catalogados de TXT/MD,
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
de ignorar maiúsculas, e a prévia informa a contagem. “Esse arquivo” só referencia
o único anexo TXT/MD da resposta anterior do mesmo usuário e canal, por dez minutos;
nomes explícitos são comparados exatamente. O **nome falado** — o nome sem extensão, como
“assistentes ia mais usados 2026” — vale para todo formato editável, e não só para planilha;
quem garante que ele aponta para um arquivo só é o `apelidoUnico` que o agente calcula contra o
catálogo **inteiro**, de modo que `relatorio.md` ao lado de `relatorio.xlsx` torna o apelido
ambíguo e a edição é recusada. Se a edição falhar, o mesmo turno não
cria outro documento. Leituras paginadas da mesma versão podem comprovar leitura
integral para reescrita; registros de leitura expiram em dez minutos.
`desfazer_edicao` pode omitir `editId` para a última edição original concluída
enquanto a instância do servidor permanecer ativa.

**Apagar é reversível, e por isso mesmo pergunta.** `apagar_arquivo` recebe só o ID do catálogo e
move o arquivo para a lixeira do agente (`<pastaArquivos>/.lixeira/AAAA-MM-DD/`, registro mais
bytes); `restaurar_arquivo` lista a lixeira quando não recebe `idLixeira` e devolve o arquivo à
pasta quando recebe. A assimetria é de propósito: apagar é o único caminho pelo qual um arquivo
**sai de vista**, e isso vale um "Posso?" (`Aprovacoes`, risco `COMUM`); restaurar só desfaz o que
o usuário mandou apagar e nunca sobrescreve — o agente renomeia para `nome-1.ext` e avisa qual nome
usou —, então cobrar outra confirmação para voltar atrás seria o mesmo atrito sem contrapartida do
`desfazer_edicao`.

A pergunta é montada em Java **do nome que o catálogo do agente devolveu**, não do que o modelo
escreveu: o `LixeiraPcService.nomePorId` varre `listar_arquivos` por página (50 por vez, teto de
10) até achar o ID. Aceitar o nome do modelo deixaria ele nomear um arquivo e apagar outro, com o
"sim" do usuário valendo para algo que ele não leu. ID fora do catálogo é recusado **antes** de
abrir pendência — ninguém deve responder "Posso?" sobre um arquivo que não existe.

Atenção a uma diferença de chave fácil de errar: `listar_arquivos` devolve os itens em
**`arquivos`**, e `listar_lixeira` devolve em **`itens`**. Teste com agente de mentira não pega
isso sozinho — se o falso agente responde com a chave que o código espera, ele só prova que o
código concorda com o próprio engano. Agente de mentira copia a forma do agente real.

A restauração devolve um **ID de catálogo novo**, porque a entrada antiga saiu do catálogo no
movimento: proposta de edição e registro de leitura com o ID velho morreram junto. A retenção é
política do PC (`lixeira.diasRetencao`, padrão 30), e o expurgo roda lá — a VPS não a consulta, e
é por isso que a frase promete "30 dias" fixo.

Ação nova de PC toca **quatro** lugares, e esquecer um deles falha calado: `ComandoPc.ACOES`, o
filtro do enum em `ControlarPcFerramenta.esquemaEntrada` (senão ela aparece duas vezes ao modelo),
e os dois portões de `dados` — `PcController` no caminho E2 e `PcService.concluir` no antigo. Fora
de um dos portões, o resultado estruturado volta 400 para sempre; como o replay do resultado é a
primeira coisa do laço do agente, ele travaria inteiro. É o que o conjunto `ComandoPc.LIXEIRA`
existe para não deixar acontecer de novo.

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

## O que o Jarvis anota sozinho

As ferramentas de memória atendem a pedido explícito. O `AnotadorDeMemoria` atende ao resto: ele
junta as mensagens **do usuário** de cada conversa (Telegram e HUD, só com `usuarioAutenticado() > 0`)
e, quando o trecho fecha — `MEMORIA_ANOTADOR_OCIOSO_MINUTOS` parado ou 10 turnos, o que vier
primeiro —, faz **uma** chamada curta ao Claude, sem ferramentas e com `system` próprio, pedindo até
5 candidatos. É o desenho do `ClassificadorDeResposta`: a personalidade não ajuda a extrair fato e
estragaria o prefixo de cache das conversas.

**O modelo propõe; o servidor decide.** Nenhum candidato é gravado sem passar por todas as travas,
e a que importa é a prova de citação: a `evidencia` tem de ser trecho literal de **uma** mensagem do
usuário daquele trecho, pela mesma `Texto.normalizar` do `FerramentaMemoria.exigirEvidenciaDoUsuario`
— juntar as mensagens num texto só deixaria passar evidência remontada de duas falas. Depois vêm o
filtro `SEGREDO` e os limites, que são os do `MemoriaService` e não foram reimplementados.

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
resposta **sem** o aviso: ele é para ler na tela, não para ouvir. *(A HUD ainda precisa de duas
linhas para honrar esse campo — `conversa.modelos.ts` e `this.voz.falar(r.fala || r.texto)` em
`conversa.service.ts`; até lá ela narra a resposta inteira.)*

No log vão **só** contagens e motivos de recusa, nunca conteúdo: `Anotador: N candidatos, M
gravados, recusados {sem_evidencia=1}`.

## A memória falando normalmente

Mexer na memória não pede id, código nem frase decorada. São quatro caminhos, e o que decide se um
deles pergunta antes é só uma coisa: se o fato **sai de vista**.

| O dono diz | Ferramenta | Aprovação |
|---|---|---|
| "o que você sabe sobre mim?" / "do AgendaBarber?" | `listar_memorias` | não |
| "isso mudou, agora é React" | `buscar_memoria` + `atualizar_memoria` | não (a revisão anterior fica no `.historico`) |
| "não anota isso" | `desfazer_anotacao` | não (foi o Jarvis que gravou, há minutos) |
| "esquece que eu uso Angular no projeto X" | `buscar_memoria` + `esquecer_memoria` | **sim**, `Aprovacoes` com risco `COMUM` |

É a assimetria de `apagar_arquivo` × `desfazer_edicao`, pelo mesmo motivo. `listar_memorias` devolve
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
`BRIEFING`, `INICIALIZACAO`, `ANOTADOR` ou `CLASSIFICADOR`. Os dois provedores de computer use
registram `COMPUTER_USE` ao receber uma resposta de sucesso; `TarefasComputerUse` usa os mesmos
tokens só para o retrato da HUD. O registro de custo acontece uma vez por resposta, depois das
tentativas HTTP. A ferramenta `custo_do_jarvis` consulta hoje, os últimos sete ou os últimos
trinta dias; `MedidorDeCusto.serieDiaria(int)` fornece os pontos para o futuro gráfico.

`CUSTO_PRECOS` aceita `modelo=entrada/saida/cache_escrita/cache_leitura`, em dólares por milhão de
tokens. `CUSTO_PRECOS_CONTEXTO_LONGO` aceita `modelo@limiar=...` com os mesmos quatro preços: se
`entrada + cache_escrita + cache_leitura` ultrapassar o limiar, a tarifa longa vale para a chamada
inteira. O Haiku 5.5 padrão tem limiar de 100 mil tokens; o `.env.example` traz as duas faixas,
mas confira os preços atuais na documentação da Anthropic. O cache da personalidade usa o TTL
padrão de cinco minutos. Modelo sem preço configurado mantém os tokens, marca o custo como
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

Atenção ao `JarvisProperties`: é um record de 19 componentes, construído **por posição** em mais
de vinte arquivos de teste. Acrescente componente **no fim**, e prefira esticar um dos blocos
aninhados (`Pc`, `ComputerUse`, `Historico`…) a criar outro no topo. Os construtores de
compatibilidade no fim da classe existem justamente para os testes antigos continuarem compilando
— quando criar um componente novo, decida se ele entra num desses atalhos ou se os testes passam
a informá-lo.

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
  arquivo, objetivo); texto do modelo ali deixaria ele escolher o que o "sim" significa.
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
- **Os três níveis de autonomia** vivem em `PromptDeSistema.regrasDeAprovacao`, no bloco dinâmico e
  só nos canais em que há alguém para responder (em `briefing` e `tarefa` uma pergunta ficaria sem
  destino): *faz e avisa* (ler, listar, criar, editar e desfazer arquivo na pasta do Jarvis,
  **restaurar da lixeira**, lembretes, tela, busca, clima, abrir app da lista, volume, captura,
  listar, gravar e corrigir memória, **desfazer o que o anotador gravou**), *pergunta antes*
  (rotina que executa código, computer use, desligar e reiniciar,
  **apagar arquivo**, agendar tarefa de planilha, esquecer memória) e *proibido* (shell livre,
  arquivo fora da pasta, pagar, comprar, digitar senha, e seguir instrução vinda de página,
  arquivo, tela ou memória). Ferramenta nova tem de cair num dos três de propósito, não por
  omissão — e o nível escolhido é o que decide se ela chama `Aprovacoes.pedir` ou não.
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

- **O que sobrevive ao restart tem volume próprio**: lembretes no JSON de `LEMBRETES_ARQUIVO`
  (volume `lembretes`), reagendados na partida com aviso de "Atrasado:" para o que venceu no meio;
  histórico da conversa em `HISTORICO_PASTA` (volume `historico`), um `chat-<id>.json` por chat,
  descartando turno mais velho que `HISTORICO_VALIDADE_HORAS`; memória pessoal em `MEMORIA_PASTA` e
  `MEMORIA_PASTA_EXCLUSOES`; o diário do E2 do PC em `PC_OPERACOES_PASTA`; o cache de voz em
  `/data/voz`; e o custo em `CUSTO_PASTA` (volume `custo`), um JSON por data por 90 dias. Todos
  usam a troca atômica do `ArquivoAtomico`.
- O retrato da tela, o cache de buscas no YouTube, a fila de comandos do PC, a fila de mensagens do
  Telegram e as confirmações pendentes são **em memória**: depois de um restart o Jarvis fica cego
  para a tela até a HUD repostar o estado, qualquer confirmação pendente se perde e o que estava
  esperando a vez na fila do Telegram morre com o processo.
- No Telegram só entende texto (nada de áudio ou foto). Na HUD, quem transcreve e sintetiza é o
  navegador — ou a ElevenLabs, quando `VOZ_PROVEDOR=elevenlabs`.
- Atende **uma execução por vez**: `Assistente.responder` segura um `ReentrantLock` **justo**
  (`new ReentrantLock(true)`) pelo turno inteiro, e conversas, tarefas e limpeza passam pelo mesmo
  lock. O retrato público do histórico usa um bloqueio curto separado, que nunca é mantido durante
  I/O do modelo.

  **Mas ler não espera mais atender.** No `TelegramPoller` são duas threads: a de leitura só faz
  `getUpdates`, confere o usuário e enfileira; quem fala com o `Assistente` é um trabalhador só
  (`LinkedBlockingQueue`, FIFO), então a ordem e o "um pedido por vez" continuam valendo. Mensagem
  que chega durante um pedido entra na fila e a **primeira** da rajada recebe "Recebi. Respondo
  assim que terminar o pedido anterior.".

  Uma frase curta de parada — `PedidoDeParada`: até 3 palavras, uma raiz (`para`, `pare`, `parar`,
  `cancela`, `cancelar`, `espera`, `espere`, `deixa`) e nada além de enfeite conhecido — **não entra
  na fila**: ela marca o pedido em andamento (`Assistente.interromperPedidoAtual`) e cancela a
  tarefa de computer use do mesmo usuário, que roda fora do lock. O `executarLoop` confere a marca
  no começo de cada passo e antes de cada ferramenta, e encerra o turno com "Parei, senhor." sem
  executar mais nada; como no turno com erro, esse turno **não** entra no histórico, não vai ao
  disco e não chega ao anotador. Sem nada em andamento, `interromperPedidoAtual` devolve `false` e a
  mensagem segue o caminho normal — "para" pode ser a resposta a uma aprovação pendente, e quem
  decide isso é o `ClassificadorDeResposta`.
