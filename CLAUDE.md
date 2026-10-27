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
  último bloco cacheável e avança sozinha. Quem o liga é a `Origem.CONVERSA` no `ClaudeClient`,
  porque a pergunta é "este caminho chama o modelo várias vezes com o mesmo prefixo?" — e só o laço
  de ferramentas do `Assistente` chama. Os dois levam o mesmo TTL (`ANTHROPIC_CACHE_TTL`) de
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
| `dev.jarvis.ia` | `Assistente` (loop de agente e histórico), `ClaudeClient`, `CompactadorDeHistorico` (encolhe o turno que saiu da janela de turnos completos), `PersonalidadeService` (lê e recarrega o arquivo), `SistemaDoJarvis` (monta o `system` de qualquer chamada), `PromptDeSistema`, `Canal`, `ComandoDeRepouso`, `PedidoDeParada` (a frase curta que corta o pedido em andamento), `ErroDaApiDoModelo` e `BuscaWebServidor` (a definição da busca na web, que a Anthropic executa) |
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
| `dev.jarvis.diario` | `DiarioDeAcoes` (o que o Jarvis fez, JSON por dia no volume `diario`), `DiarioEmArquivo`, `DiaDoDiario`, `AcaoRegistrada` e os enums `TipoDeAcao`/`OrigemDaAcao` |
| `dev.jarvis.proativo` | O Jarvis falando primeiro: `AvisosProativos` (o único caminho do aviso não pedido), `MonitorDeSites`, `FalasService` (o catálogo das frases, com marcadores), `JanelaDeSilencio` e `HistoricoDeDisponibilidade`/`DisponibilidadeEmArquivo`/`DiaDeDisponibilidade` (o JSON por dia no volume `proativo`) |
| `dev.jarvis.voz` | A voz da HUD: `ProvedorVoz`/`ElevenLabsVoz`, `CacheDeVoz` (MP3 em disco), `MonitorDeCotaVoz` (reserva, aviso antecipado e volta ao navegador), `DiarioDeVoz` (o uso por dia, de onde sai a projeção), `TextoParaVoz` e `VozService` |
| `dev.jarvis.tela` | A tela dirigida pelo Jarvis: `CardTela`/`DadosDoCard`/`ComandoTela`, `TelaEvento`, `EstadoDaTela`/`TelaEstadoService` (o retrato da HUD), `MontadorDeCardDePesquisa`, `YoutubeService`, `PaginaService`, `GuardaSsrf`, `FalaDaTela`/`FalaDaTelaService` (o catálogo fechado de frases) e a porta `PresencaDaHud` |
| `dev.jarvis.pc` | O PC Windows: `PcService` e `OperacoesPcService` (as filas de comando), `CatalogoDoPc` (os apps e as rotinas que o PC anunciou, em disco), `ComandoPc`/`ResultadoPc`, `ArquivosPcService` e `FluxoEdicaoPc` (arquivos e edição), `TarefasPlanilhaService` e `AvisosOperacoesPc` |
| `dev.jarvis.aprovacao` | `Aprovacoes`: a aprovação humana de uso único por onde passa **toda** confirmação, com o nível `Risco` que define o prazo — e o único ponto onde a ação aprovada roda, logo o único que a anota no diário |
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

### A pergunta nasce com a mensagem, não antes

Com uma rotina associada em `SITES_ACOES` (`nome_do_site=nome_da_rotina`), o aviso termina num
"Posso rodar a rotina X para tentar resolver?" criado no `Aprovacoes` com risco `COMUM`. Três
detalhes que o desenho resolve e que são fáceis de errar:

- **A pergunta entra como `Supplier`, não como texto**, e só é avaliada quando a mensagem realmente
  sai. É isso que cumpre "durante o silêncio, não crie pendência": uma pendência `COMUM` vale dez
  minutos, e criada às 2h expiraria horas antes de o dono ler a pergunta — ele responderia "pode" a
  um "Posso?" que já não existe. Mesmo idioma do `Supplier` do `Aprovacoes`, pelo mesmo motivo: a
  ação nasce no instante em que faz sentido.
- **O monitor confere `pendenciaDe` antes de `pedir`.** O `Aprovacoes.pedir` *sobrescreve calado*
  uma pendência do mesmo usuário com outro `pedidoOrigem` (a exceção é só para outro usuário ou
  para o mesmo pedido). Sem essa checagem, um site caindo no instante errado trocaria o "Posso?"
  que o dono está lendo por outro, e o "pode" dele executaria algo que ele não leu. Havendo
  pendência em aberto, o aviso sai **sem** a pergunta.
- **O `pedidoOrigem` é sintético** (`proativo:site_fora:<site>:<instante>`), porque `pedir` exige
  usuário autenticado e pedido de origem, e a confirmação só vale em **mensagem nova**. Um id que
  nunca colide com o de uma mensagem real é o que faz a próxima mensagem do dono poder confirmar.

O `tipo` da pendência é o **`"rotina"`** que já existe, e não um novo: é literalmente uma rotina, a
frase fixa já é `confirmo rotina <código>` e o `Aprovacoes.tipoNoDiario` já a anota como
`TipoDeAcao.ROTINA` — a execução aprovada entra no diário sem tocar em `Aprovacoes`. O
enfileiramento em si mora no `OperacoesPcService.iniciarRotina`, que a `RodarRotinaFerramenta`
passou a chamar: são os mesmos trinta segundos de espera e o mesmo `pedirAviso` no timeout, e duas
cópias disso divergiriam na primeira correção feita num lado só.

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
`diario.registrarDoCanal(...)` no ponto onde a ação **deu certo**. Hoje são seis:
`FluxoEdicaoPc.aplicar` (edição, cópia e desfazer, logo depois de `resultado.ok()`, de onde saem os
três retornos que só diferem na entrega do anexo), `RestaurarArquivoFerramenta`,
`ApagarArquivoFerramenta` (por arquivo movido, nos dois caminhos, porque o `mover` privado é um
só), `CriarArquivoFerramenta`, `AnotadorDeMemoria.gravar` (por fato anotado, não por lote — um lote
pode trazer cinco) e `AtualizarMemoriaFerramenta`. O canal vem de `OuvinteProgresso.canal()`, que é
a única forma de uma ferramenta saber de onde veio o pedido.

Ação que **tem volta** guarda o identificador que a desfaz no campo `referencia` do
`AcaoRegistrada` — hoje o `idLixeira` de um arquivo apagado. Vazio é o normal, e é só gravado no
JSON do dia quando existe; o construtor de quatro argumentos continua valendo para quem não tem
nada a desfazer. Não é falado: quem vai ao ouvido do dono é a `descricao`.

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
`BRIEFING`, `INICIALIZACAO`, `ANOTADOR` ou `CLASSIFICADOR`. Os dois provedores de computer use
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

Atenção ao `JarvisProperties`: é um record de 21 componentes, construído **por posição** em mais
de vinte arquivos de teste. Acrescente componente **no fim**, e prefira esticar um dos blocos
aninhados (`Pc`, `ComputerUse`, `Historico`…) a criar outro no topo. Os construtores de
compatibilidade no fim da classe existem justamente para os testes antigos continuarem compilando
— quando criar um componente novo, decida se ele entra num desses atalhos ou se os testes passam
a informá-lo. O `Diario` que virou o 20º é o exemplo do barato: o atalho novo repete a aridade
anterior (19 parâmetros) e passa `null`, e nenhum dos testes que informavam `Custo` precisou mudar.
O `Proativo` que virou o 21º fez o mesmo com a aridade 20.

Um detalhe de nome que custa uma hora se for descoberto no bind: `SITES_INTERVALO_MINUTOS` e
`SITES_ACOES` moram em `jarvis.proativo.*`, e **não** em `jarvis.sites.*`. O `jarvis.sites` já é o
escalar com a lista de URLs, e um bloco com o mesmo nome colidiria com ele. Pela mesma razão a
faixa de silêncio vai entre aspas no `application.yml` (`"${PROATIVO_SILENCIO:23:00-07:00}"`): sem
elas o YAML leria os dois-pontos do default como um mapa.

### Os avisos proativos

`PROATIVO_SILENCIO` (a faixa em que nada sai), `PROATIVO_PASTA` (volume `proativo`),
`SITES_INTERVALO_MINUTOS` e `SITES_ACOES` são as quatro do bloco `jarvis.proativo`. Os 30 dias de
retenção do histórico e as **duas** falhas seguidas antes de dar um site como fora são
**constantes** (`HistoricoDeDisponibilidade.DIAS_GUARDADOS` e `MonitorDeSites.FALHAS_PARA_AVISAR`),
pela regra de sempre: não vale um componente num record construído por posição para um número que
ninguém vai mexer — e o 2 não é um ajuste de gosto, é o desenho.

O intervalo é o único número que o `@Scheduled` lê direto do yml
(`fixedDelayString = "${jarvis.proativo.sites-intervalo-minutos:5}"`, em minutos), como o
`jarvis.resumo-diario.cron` já faz. O acessor `intervaloDeChecagemDeSites()` existe para quem
monta as properties à mão, com piso de 1 minuto.

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
a separá-las: a tarefa agendada e a conversa gastam no mesmo balde (`Origem.CONVERSA`) e pensam
diferente. Por isso o `ClaudeClient` deriva o perfil da origem em todas as chamadas, e só o
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

`DIARIO_PASTA` (`jarvis.diario.pasta`, volume `diario`) é a única configuração do diário. Os 30 dias
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
remontado do zero a cada passo do `executarLoop`, então um `listar_arquivos` que devolveu o JSON do
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
  ferramentas. O bloco dinâmico mantém relógio, canal e estado da HUD. Cache explícito no fixo e
  automático na conversa usam o mesmo `ANTHROPIC_CACHE_TTL`. O esforço é fixo por origem, não muda
  entre passos do mesmo pedido.
- O `Assistente` envia a busca BM25 `tool_search_tool_bm25_20251119` mais **todas** as definições
  a cada chamada. `Ferramentas` marca `defer_loading: true` fora de
  `FERRAMENTAS_SEMPRE_CARREGADAS` (padrão: `clima,criar_lembrete,listar_lembretes,controlar_pc,`
  `controlar_tela,tocar_youtube`). `tarefa_no_pc` fica sempre visível; `ouvir_resposta` também na
  HUD. Busca web e o provedor separado de computer use continuam carregados. Ferramenta adiada
  não leva `cache_control`; os blocos `server_tool_use` e `tool_search_tool_result` voltam
  intactos no histórico. A ordem das definições Java continua alfabética.
- Cada `Ferramenta` informa `grupo` e `simples` somente no Java, para o futuro roteador local.
  A execução conhecida é contada uma vez ao começar, mesmo se falhar, e `MedidorDeCusto` agrega
  por dia no volume `custo`. `custo_do_jarvis` mostra até dez nomes mais usados no período.
- `RESULTADO_MAX_CARACTERES` (padrão 6.000) limita só `tool_result.content`. O resultado integral
  continua disponível para anexos, eventos e código Java. Texto cortado termina com
  `[cortado; peça a próxima parte]`; JSON longo vira JSON válido resumido com esse aviso,
  identificadores e dados de continuação. `ler_arquivo` limita `maximoCaracteres` a esse tamanho
  e mantém os deslocamentos de paginação do agente do PC. Não corte o `JsonNode` de origem.

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
- **Os três níveis de autonomia** vivem em `PromptDeSistema.regrasDeAprovacao`, no bloco dinâmico e
  só nos canais em que há alguém para responder (em `briefing` e `tarefa` uma pergunta ficaria sem
  destino): *faz e avisa* (ler, listar, criar, editar e desfazer arquivo na pasta do Jarvis,
  **apagar arquivo** — vai para a lixeira, e mais de 5 no mesmo pedido pergunta uma vez — e
  **restaurar da lixeira**, lembretes, tela, busca, clima, abrir app da lista, volume, captura,
  listar, gravar e corrigir memória, **desfazer o que o anotador gravou**, **dizer o que ele
  próprio fez**, **acompanhar os sites e avisar que um caiu ou voltou**), *pergunta antes*
  (rotina que executa código, computer use, desligar e reiniciar,
  agendar tarefa de planilha, esquecer memória) e *proibido* (shell livre,
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
  `MEMORIA_PASTA_EXCLUSOES`; o diário do E2 do PC em `PC_OPERACOES_PASTA` e, ao lado dele, o
  `catalogo.json` com os apps e as rotinas que o PC anunciou — não para a máquina funcionar, mas
  para o array `tools` nascer igual depois de um restart e o cache de prompt não ser reconstruído;
  o cache de voz em
  `/data/voz` e o uso de voz por dia em `/data/voz/uso` (35 dias, mais o marcador do período já
  avisado); o custo em `CUSTO_PASTA` (volume `custo`), um JSON por data por 90 dias; e o que o
  Jarvis fez em `DIARIO_PASTA` (volume `diario`), um JSON por data por 30 dias, mais o
  `ultimo-briefing.json` que marca até onde a seção "o que fiz sozinho" já contou; e a
  disponibilidade dos sites em `PROATIVO_PASTA` (volume `proativo`), um JSON por data por 30 dias.
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
- O retrato da tela, o cache de buscas no YouTube, a fila de comandos do PC, a fila de mensagens do
  Telegram e as confirmações pendentes são **em memória**: depois de um restart o Jarvis fica cego
  para a tela até a HUD repostar o estado, qualquer confirmação pendente se perde e o que estava
  esperando a vez na fila do Telegram morre com o processo. O que **não** se perde mais é o catálogo
  de apps e rotinas do PC, que foi para o disco justamente porque ele entra no array `tools`.
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
