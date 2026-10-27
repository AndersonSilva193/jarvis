# Jarvis

Assistente pessoal no Telegram, feito em Java 21 + Spring Boot 4, que usa o Claude para entender
pedidos em linguagem natural e executar ferramentas reais. Roda 24h numa VPS dentro de um container
Docker, e tem duas caras: o **Telegram**, para conversar de qualquer lugar, e a
**[HUD](../jarvis-hud)**, a tela que fica aberta num monitor, fala em voz alta e abre cards.

Ele responde no personagem descrito em `config/personalidade.md` — um arquivo de texto que você
edita na VPS e ele relê sozinho, sem rebuild e sem reiniciar o container.

Projetos irmãos: **[jarvis-hud](../jarvis-hud)** (a tela, em Angular) e
**[jarvis-agente-pc](../jarvis-agente-pc)** (o braço dele no PC Windows).

## Como funciona

```
Telegram ──► TelegramPoller ──► Assistente ──► Claude (API)
                                    │   ▲            │
                                    ▼   │  "chame a ferramenta X"
                               Ferramentas ◄─────────┘
```

1. O `TelegramPoller` pergunta ao Telegram, em loop, se chegou mensagem (long polling, sem precisar
   de domínio nem porta aberta).
2. O `Assistente` manda a conversa e a lista de ferramentas para o Claude.
3. Se o Claude pedir uma ferramenta, o Java executa e devolve o resultado. Isso se repete até ele
   responder.
4. A resposta volta para você no Telegram — ou, se o pedido veio da HUD, é falada na tela.

Todo pedido passa por um único `ReentrantLock` justo no `Assistente`: conversas do Telegram, da HUD
e tarefas do PC são atendidas **uma por vez**, na ordem de chegada.

### O prompt de sistema

Toda chamada ao Claude leva o `system` em **dois blocos**, nesta ordem:

| Bloco | Conteúdo | Cache |
|---|---|---|
| fixo | o texto de `config/personalidade.md` e um parágrafo constante com as categorias de ferramentas | `cache_control: ephemeral` |
| dinâmico | data e hora com fuso, canal do pedido, retrato da tela, regras técnicas e de formato | nunca |

A ordem importa: o cache de prompt da Anthropic casa **prefixo exato**, então qualquer coisa que muda
a cada pedido (a começar pelo relógio) tem de ficar *depois* do bloco cacheado. O marcador de cache
só é enviado quando o bloco fixo passa de `ANTHROPIC_CACHE_MINIMO_TOKENS` — abaixo disso a API o
ignoraria calada.

### Economia de tokens nas ferramentas

O Jarvis envia à API todas as definições permitidas para o canal. A busca BM25 do Claude carrega
no contexto só as ferramentas necessárias. `FERRAMENTAS_SEMPRE_CARREGADAS` define as seis
disponíveis de imediato:
`clima,criar_lembrete,listar_lembretes,controlar_pc,controlar_tela,tocar_youtube` por padrão.
`tarefa_no_pc` também fica visível; `ouvir_resposta` fica visível na HUD. A busca web e o
computer use continuam disponíveis. As demais ferramentas são encontradas pela busca quando o
pedido exigir. Alterar a lista fixa muda o prefixo do cache de prompt.

`RESULTADO_MAX_CARACTERES` limita a 6.000 caracteres, por padrão, cada resultado textual que
volta ao modelo. O texto cortado avisa `[cortado; peça a próxima parte]`; JSON longo continua
válido e conserva identificadores e indicação de continuação. `ler_arquivo` usa esse limite para
as páginas de texto e aceita o deslocamento retornado para ler a próxima parte. O registro diário
de custo passa a contar ferramentas executadas, e `custo_do_jarvis` mostra as dez mais usadas no
período consultado. A contagem começa com esta versão; dias antigos não têm esse dado.

O **canal** diz ao Jarvis como escrever: `telegram` aceita negrito, listas e blocos de código; `hud`
e `briefing` viram áudio, então são frases curtas sem markdown; `tarefa` começa pelo resultado, com
uma linha de status. Cada chamada ao Claude registra no log quanto consumiu:

```
Claude: entrada=812 saída=96 cache_escrito=0 cache_lido=2310
```

`cache_escrito` aparece na primeira chamada (quando o prefixo é gravado) e `cache_lido` nas
seguintes. Se as duas ficarem sempre em zero, o cache não está pegando.

## Instalação

### Passo 1: pegar as credenciais

1. **Bot do Telegram:** fale com o [@BotFather](https://t.me/BotFather), envie `/newbot`, escolha
   nome e usuário. Ele te dá o **token**.
2. **Chave do Claude:** crie em [console.anthropic.com](https://console.anthropic.com), em
   *API Keys*. A API é paga por uso (créditos pré-pagos); adicione alguns dólares para começar.
3. **Seu ID do Telegram:** mande qualquer mensagem para o [@userinfobot](https://t.me/userinfobot),
   ou deixe `TELEGRAM_USUARIO_PERMITIDO=0`, suba o bot, mande um "oi" e veja o ID nos logs.

### Passo 2: subir na VPS

```bash
# envie a pasta para a VPS (git clone ou scp) e entre nela
cp .env.example .env
nano .env                      # preencha token, chave, seu ID e os sites

nano config/personalidade.md   # opcional: ajuste o jeito do Jarvis (veja abaixo)

docker compose up -d --build   # compila e sobe
docker compose logs -f         # acompanha os logs (Ctrl+C para sair)
```

Mudou o `.env` ou o código? Rode `docker compose up -d --build` de novo.

Para rodar no seu PC (sem Docker), exporte as mesmas variáveis do `.env`, aponte
`JARVIS_PERSONALIDADE_ARQUIVO=config/personalidade.md` e rode `mvn spring-boot:run`.

Para validar antes de publicar: `mvn -q -B package` aqui e `npm test -- --watch=false` /
`npm run build` em `jarvis-hud`. Os testes usam modelo, reconhecimento e síntese simulados; nenhuma
mensagem é enviada ao Telegram. Quando for publicar, disponibilize primeiro este backend (ele mantém
o alias SSE da HUD antiga) e depois a HUD.

### A personalidade

`config/personalidade.md` é o system prompt fixo: identidade, tom, o que o Jarvis faz e o que não
faz. Ele é versionado junto com o código (o repositório é privado), mas **não entra na imagem**:
chega ao container por volume, montado como somente leitura em `/app/config`.

É justamente por isso que editar o texto **não** exige rebuild — o Jarvis compara a data de
modificação do arquivo a cada chamada e relê quando ela muda. Se o arquivo faltar ou estiver em
branco, o bot sobe com uma personalidade mínima de reserva e avisa no log, em vez de deixar de
responder.

Segredo nenhum entra aqui: token, chave de API e afins continuam só no `.env`.

### Criando uma ferramenta nova

Basta criar uma classe em `dev.jarvis.ferramentas` que implemente `Ferramenta` com `@Component`.
O Spring encontra sozinho e o Claude passa a poder usá-la. Exemplo de esqueleto:

```java
@Component
public class AgendamentosHojeFerramenta implements Ferramenta {

    private final JsonMapper json;

    public AgendamentosHojeFerramenta(JsonMapper json) {
        this.json = json;
    }

    public String nome() { return "agendamentos_hoje"; }

    public String descricao() {
        return "Lista os agendamentos de hoje no AgendaBarber, com horário, cliente e serviço.";
    }

    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        esquema.putObject("properties");
        return esquema;
    }

    public String executar(JsonNode entrada) throws Exception {
        // chame sua API com o HttpClient (ou consulte o banco) e devolva um texto simples
        return "09:00 - João - Corte\n10:30 - Pedro - Barba";
    }
}
```

Dicas: a `descricao()` é o que faz o modelo escolher a ferramenta certa, então capriche nela. E
devolva textos curtos e objetivos: tudo que a ferramenta retorna vira tokens (custo).

## As ferramentas, por área

### Conversa e informação

| Você pede | Ferramenta usada |
|---|---|
| "a API do AgendaBarber está no ar?" | `verificar_site` |
| "como está o servidor?" | `status_servidor` |
| "como está o tempo?" | `clima` |
| "quais as notícias de hoje?" | `noticias` |
| "me dá o briefing" | `briefing_do_dia` |
| "quanto você gastou hoje?" / "e na semana?" | `custo_do_jarvis` |
| "o que você fez hoje?" / "o que mudou nos meus arquivos ontem?" | `acoes_do_jarvis` |
| "a voz está funcionando?" | `status_voz` |
| (na HUD) uma pergunta que precisa da sua resposta | `ouvir_resposta` |

Além disso, todo dia útil às 8h ele manda um resumo de bom dia (clima, lembretes, sites e notícias),
no cron de `RESUMO_DIARIO_CRON`. A busca na web é uma ferramenta **do servidor da Anthropic**: o
Jarvis só manda a definição (`BUSCA_WEB_ATIVA`, `BUSCA_WEB_TIPO`, `BUSCA_WEB_MAX_USOS`) e quem
procura é a API.

### Lembretes persistentes e recorrentes

| Você pede | Ferramenta usada |
|---|---|
| "me lembra de mandar o currículo amanhã às 9h" | `criar_lembrete` |
| "todo dia útil às 7h me lembra de tomar o remédio" | `criar_lembrete` com `recorrencia` |
| "quais lembretes eu tenho?" / "cancela o 2" | `listar_lembretes` / `cancelar_lembrete` |

Os lembretes vivem num arquivo JSON em `LEMBRETES_ARQUIVO` (padrão
`/data/jarvis/lembretes/lembretes.json`), montado pelo Compose no volume nomeado `lembretes`. Cada
mudança é gravada num temporário na mesma pasta e trocada com `ATOMIC_MOVE`, e a versão anterior fica
num `lembretes.json.bak` ao lado — principal corrompido na partida faz o Jarvis ler o `.bak` e avisar
em WARN. Os instantes vão em ISO-8601 com fuso e a exibição usa `FUSO_HORARIO`. Na partida tudo é
reagendado, e os IDs continuam do maior ID salvo: "cancela o 7" não acerta um lembrete diferente
depois de um restart.

O que venceu com o container parado é avisado na partida com o prefixo "Atrasado:" e a hora original.
Acima de 24 horas de atraso vira um resumo único ("3 lembretes venceram enquanto eu estava fora: ..."),
nunca uma mensagem por lembrete.

A recorrência aceita `diario`, `dias_uteis`, `semanal` (com os dias da semana) e `mensal` (o dia do mês
de `data_hora`; dia 31 cai no último dia dos meses curtos). Ao disparar, a próxima ocorrência é
calculada e gravada **antes** de a mensagem sair — uma queda no meio pode repetir um aviso, nunca
apagar a série. `listar_lembretes` mostra a recorrência e a data da próxima ocorrência; cancelar um
recorrente cancela a série inteira. O briefing e o card da HUD mostram a ocorrência **de hoje** mesmo
quando a próxima já pulou para amanhã (o lembrete das 8h visto às 10h continua na agenda do dia).

### Memória persistente na VPS

| Você pede | Ferramenta usada |
|---|---|
| "o que você sabe sobre mim?" / "o que você guardou do AgendaBarber?" | `listar_memorias` |
| "o que você lembra do projeto Jarvis?" | `buscar_memoria` / `ler_memoria` |
| "lembre que prefiro chá" | `registrar_memoria` |
| "isso mudou, agora é React" | `atualizar_memoria` |
| "não anota isso" / "apaga isso que você anotou" | `desfazer_anotacao` |
| "esquece que eu uso Angular no projeto X" | `esquecer_memoria` (com um "Posso?" respondido em linguagem natural) |

Tudo isso se faz falando normalmente: não há ID para repetir, código para digitar nem palavra que a
mensagem precise começar. O `listar_memorias` mostra o que o Jarvis sabe agrupado por categoria, até
20 registros por vez, **sem IDs** — ele fala das lembranças pelo assunto.

O que **não** pergunta: listar, guardar, corrigir e desfazer. Corrigir é reversível (a versão
anterior fica guardada) e "não anota isso" só desfaz o que o próprio Jarvis anotou sozinho nos
últimos dez minutos — se aquilo foi uma correção de algo que você já tinha dito, ele volta à versão
anterior em vez de apagar, e responde "Tirei.". Passados os dez minutos, o pedido cai no caminho de
baixo.

O que **pergunta**: esquecer. É o único caminho pelo qual uma lembrança sai de vista, então o Jarvis
responde "Vou apagar da memória: 'cidade: mora em Curitiba'. Posso?" e espera. Você responde como
quiser — "pode", "não", "deixa pra lá" —, e quem interpreta é o servidor, antes de o modelo entrar
na conversa; o Jarvis não tem como se autoconfirmar. Um assunto que você mandou tirar não volta a
ser anotado sozinho, até você mesmo pedir para guardá-lo de novo.

Episódios só são registrados quando você pede para lembrar do ocorrido. Consultas por assunto e
projeto devolvem até cinco registros; não encontrar um registro não autoriza inventar lembranças. O
conteúdo lido é dado não confiável: não concede permissões nem autoriza ações.

Os registros ficam em Markdown UTF-8 em `MEMORIA_PASTA` (padrão `/data/jarvis/memoria`), separados por
`usuarios/<id>/perfil.md`, `preferencias.md`, `projetos/<id-validado>.md` e
`episodios/AAAA-MM-DD.md`. Cada registro traz ID, assunto, projeto, origem, classificação, instantes
UTC e revisão. A data exibida usa `FUSO_HORARIO`. Correções anteriores ficam em `.historico` até o
registro ser esquecido. O Jarvis recusa conteúdo possivelmente secreto e não usa o `criar_arquivo` do
PC para memória. Ela continua disponível pelo Telegram quando o PC está desligado.
No Telegram, a memória só é consultada em conversa privada com o usuário permitido; respostas em
grupos não recebem esse acesso.

O Compose monta dois volumes nomeados: `memoria` para os registros e `memoria-exclusoes` para
marcadores permanentes sem conteúdo — os de registro esquecido, pelo ID, e os de assunto que você
recusou, em `assuntos/`, que são o que impede o Jarvis de anotar de novo o que acabou de tirar. A
imagem cria ambas as pastas para o usuário `jarvis` (UID
1001); em uma instalação com bind mount, crie as pastas no host com dono `1001:1001` e acesso
restrito a esse usuário. Não exponha os volumes via nginx ou servidor de arquivos. O volume de
exclusões deve sobreviver à restauração de um backup antigo de conteúdo: na partida, o Jarvis
reaplica os marcadores e remove registros restaurados que haviam sido esquecidos.

### Histórico da conversa

O que o Jarvis lembra da conversa também sobrevive ao restart. Para cada chat há um arquivo
`chat-<id>.json` em `HISTORICO_PASTA` (padrão `/data/jarvis/historico`, volume nomeado `historico`),
gravado pela mesma troca atômica dos lembretes. Dentro dele ficam as duas metades do diálogo: os
últimos dez turnos que vão ao modelo e as trocas públicas que a HUD mostra na lista.

O arquivo é escrito **ao fim de cada turno concluído**, numa thread virtual própria — quem está
esperando a resposta não paga a gravação. Turno que falhou no meio não chega ao disco, porque quem
agenda é o mesmo trecho que já decidia guardar o turno em memória.

O que não entra: bloco de pensamento (que já ficava fora, por exigência da API) e imagem em base64 —
uma captura de tela viraria centenas de kilobytes parados no volume, e no lugar dela fica o texto
`[imagem omitida]`. Na partida, todo turno mais velho que `HISTORICO_VALIDADE_HORAS` (padrão 72) é
descartado — três dias, para o assunto deixado pela metade na sexta ainda existir na segunda; o id de sessão é novo a cada partida, e é por ele que a HUD sabe que o histórico
restaurado é lista para olhar, não resposta para falar de novo.

Privacidade: esquecer a memória pessoal apaga **na hora** o arquivo de todos os chats daquele
usuário, não só o que estava em RAM — o Jarvis só diz "esqueci" depois de o disco estar limpo. O
`/limpar` do Telegram e o `POST /api/conversa/limpar` da HUD apagam o arquivo daquele chat do mesmo
jeito.

### A tela dirigida pelo Jarvis

| Você pede | Ferramenta usada |
|---|---|
| "mostra na tela a tabela do Brasileirão" | `mostrar_na_tela` |
| "mostra a previsão da semana num gráfico" | `grafico_de_dados` |
| "faz um gráfico da coluna de vendas daquela planilha" | `grafico_de_dados` |
| "mostra num gráfico o meu gasto do mês" | `grafico_de_dados` |
| "põe um lo-fi para estudar" | `tocar_youtube` |
| "abre essa página na tela" | `abrir_pagina` |
| "fecha aquele card" / "limpa a tela" | `controlar_tela` |

A HUD é dirigida pelo Jarvis: ele abre cards e comanda os que já estão abertos. Funciona para pedido
feito na própria HUD **e** no Telegram — "mostra na tela o vídeo X" pelo celular abre a janela na
tela de casa.

Cinco tipos de card: `pesquisa` (ranking, ficha, lista, linha do tempo, destaque ou **gráfico**),
`video`, `musica`, `pagina` e `noticia`. O layout `grafico` é o único sem `itens`: o conteúdo vai
em `dados.grafico`, com `eixoX`, as `series`, a `unidade` e o desenho em `linha` (2 a 24 pontos),
`barras` (até 12 categorias), `barras_empilhadas` (até 12 períodos e 4 partes) ou `rosca` (2 a 6
fatias e uma série só). Os dois últimos não aceitam valor negativo, e a soma da rosca tem de ser
maior que zero: segmento de sinal trocado não compartilha a base, e anel sem total não tem fatia.
Dois eventos no SSE:

| Evento | Carga |
|---|---|
| `tela.card` | `{id, tipo, titulo, dados, origem, criadoEm}` — abre uma janela |
| `tela.comando` | `{acao, alvo, valor}` — mexe no que já está aberto |

As 20 ações de `tela.comando`: `limpar`, `fechar`, `fechar_tudo`, `minimizar`, `maximizar`,
`restaurar`, `focar`, `fixar`, `desafixar`, `abrir_historico`, `fechar_historico`,
`mostrar_briefing`, `repetir_briefing`, `abrir_noticia`, `pausar`, `continuar`, `volume_mais`,
`volume_menos`, `avancar` e `voltar`. As que mexem em uma janela levam o id dela em `alvo`.

**O que a HUD precisa fazer**, e sem isso nada funciona:

1. Ouvir `tela.card` e `tela.comando` no `GET /api/eventos`.
2. Criar a janela **com o `id` que veio no card** — o id é do servidor, sorteado (`c7k2`), e é por
   ele que o Jarvis acha o alvo depois.
3. Repostar `POST /api/tela/estado` a cada mudança: abrir, fechar, minimizar, maximizar, fixar,
   focar, tocar e pausar.

O servidor guarda só o último retrato, por 10 minutos, e dele monta até 12 linhas no prompt —
`[c7k2] vídeo 'Lo-fi para estudar' (tocando)`. É isso que permite ao Jarvis dizer "fechei o
vídeo" em vez de chutar um id. Sem o POST, `controlar_tela` nunca encontra alvo.

Com nenhuma HUD conectada ao SSE, as ferramentas de tela respondem "a tela não está aberta" ao
modelo e não publicam nada — nem pelo Telegram.

A fala, nesses casos, é curta de propósito ("Aqui está, senhor.", "Tela limpa."): o card já mostra
os dados, e repetir em voz alta o que está na tela é ruído. Essa regra vive no `Canal.HUD`.

#### Gráficos: os números não passam pelo modelo

Todo gráfico diz de onde vieram seus números, no campo `dados.grafico.origem`. Num gráfico de
pesquisa (`mostrar_na_tela` com `layout: "grafico"`) ele vale `pesquisa`, o Jarvis teve de informar
a fonte e a data, e os valores foram digitados por ele a partir do que leu na web. Num gráfico de
`grafico_de_dados` ele vale `servidor`: aí o Jarvis escolheu só **qual** gráfico quer e escreveu a
conclusão no título, enquanto eixo, valores e unidade foram preenchidos pelo Java. A ferramenta não
tem campo onde um valor caiba — não é uma regra de conduta, é a forma do schema. Gráfico é a maneira
mais convincente de errar: um número trocado numa série de sete pontos não parece engano, parece
tendência.

Três fontes hoje:

- **`clima_7_dias`** — máxima, mínima e chance de chuva da semana à frente, numa só consulta à
  Open-Meteo. Abre **dois cards**, porque um gráfico tem uma unidade só: temperatura em linha (°C) e
  chance de chuva em barras (%). 30% desenhado na mesma escala de 22 °C seria um gráfico que mente,
  e deixar a chuva só na fala tiraria da tela justamente o número que decide levar guarda-chuva. Dia
  que a previsão ainda não tem encerra a série; sem a chance de chuva em todos os dias, vai só o
  card de temperatura.
- **`planilha`** — até três colunas de um XLSX ou CSV da pasta do Jarvis, pelo id de
  `listar_arquivos`. Uma linha vira um ponto, e o nome de cada série sai do cabeçalho da própria
  planilha. Célula que não é número **derruba o gráfico** dizendo qual é ("célula B3 ('sem dados')
  não é número"): texto virando zero seria uma barra zerada que você leria como "vendeu nada".

- **`custo_30_dias`** — o que o Jarvis gastou no mês, em barras empilhadas por origem. Trinta dias
  não cabem nas doze categorias da empilhada, então os dias vão somados em **blocos de cinco**: seis
  barras, rótulo `19-23/10`. Bloco sem nenhum dia registrado sai do eixo — zero ali desenharia "não
  gastei nada" onde a verdade é "eu estava desligado". São seis origens e quatro partes, então as
  três maiores vão uma a uma e o resto é somado em `outras`; quem corta é o Java, porque deixar o
  Jarvis escolher qual origem desaparece seria deixá-lo escolher o que você não vê.

A única coisa que o Jarvis escreve num gráfico de planilha, além do título, é a unidade — a planilha
não declara se a coluna está em reais ou em quilos, então ele copia o símbolo do cabeçalho. No clima
nem isso: a unidade é do servidor.

O estado aceita também `resposta` e todas as janelas minimizadas, sem o antigo teto de 24. A HUD
envia primeiro o foco e a mídia em reprodução; o contexto continua limitado a 12 linhas. `limpar`
preserva cards fixados; `fechar_tudo` fecha inclusive os fixados. Instale esta versão da API antes
da HUD com janelas.

### O PC Windows

O PC roda o [agente](../jarvis-agente-pc), que **busca** comandos na VPS por long polling. Nenhuma
porta aberta na máquina, nenhum domínio.

| Você pede | Ferramenta usada |
|---|---|
| "abre o Chrome" / "bloqueia a tela" / "aumenta o volume" | `controlar_pc` |
| "tira um print" | `controlar_pc` com `captura_tela` |
| "desliga o PC" / "reinicia" / "cancela o desligamento" | `controlar_pc` (com confirmação) |
| "roda a rotina de testes" | `rodar_rotina` |
| "como está o comando X?" / "cancela o comando X" | `status_operacao_pc` / `cancelar_operacao_pc` |

`captura_tela` vai aos dois destinos: `sendPhoto` no Telegram e um anexo na HUD. O mime é farejado
dos bytes, nunca aceito do agente.

**PC offline responde na hora** (90 s sem contato), antes de enfileirar. Com o PC online, a
ferramenta espera até 30 s pelo resultado; o comando segue valendo na fila. `desligar` e `reiniciar`
pedem confirmação: uso único, 2 minutos, amarrada à ação e ao argumento — e o agente ainda espera 60
segundos (`shutdown /t 60`), então dá para desistir com `cancelar_desligamento`.

A **lista de apps liberados mora só no PC**: o agente a reporta no cabeçalho `X-Pc-Apps` de cada
consulta, e o caminho do `.exe` nunca sai do `config.properties` dele. O modelo escolhe um nome da
lista, ou nada.

#### Operações E2 e rotinas

`rodar_rotina` oferece ao modelo somente nomes, descrições e capacidades anunciados pelo agente.
O cadastro real fica em `rotinas.properties` no PC. Toda rotina exige uma nova mensagem do usuário:
`confirmo` ou `pode prosseguir`, por exemplo. O agente valida sua própria política local. A
confirmação é consumida em até dois minutos e fica vinculada ao nome e ao pedido original; o modelo
não pode confirmá-la no mesmo turno. O texto original segue ao agente para uso por stdin na rotina
Claude.

O protocolo E2 usa `/api/pc/v2`: catálogo, busca com prazo de posse, confirmação de recebimento,
marcação de início, resultado, consulta por ID e sinal de cancelamento. O ID permanece o mesmo em
reentregas. Estados e prazos são gravados no volume `pc-operacoes` em `PC_OPERACOES_PASTA`. Após 30
segundos de espera na ferramenta, consulte `status_operacao_pc` pelo ID; essa espera não cancela a
execução. O resultado tardio gera no máximo uma tentativa de aviso e continua consultável pelo ID.
`resultado_incerto` requer decisão humana: não envie automaticamente o mesmo comando de novo.

Para implantar, publique primeiro a VPS com as rotas `/api/pc/v2` e só então atualize o agente do
Windows. As rotas `/api/pc` antigas permanecem durante a migração. O agente novo anuncia rotinas
somente após carregar um cadastro local válido. Execute `mvn -q -B package` nos dois projetos antes
de publicar.

O E2 também transporta os resultados de `criar_arquivo`, `captura_tela`, `listar_arquivos` e
`ler_arquivo`. O upload binário de `criar_arquivo` e as imagens ficam no volume `pc-operacoes`;
dados estruturados e estado ficam no diário. O servidor valida ação, tipo e tamanho antes de aceitar
o resultado. Um retry idêntico recebe a mesma confirmação. Anexos tardios são enviados ao Telegram
ou registrados no histórico da HUD; o comando continua consultável pelo ID até a retenção terminar.
Ao restaurar backup, restaure o volume inteiro, inclusive `arquivos` e `imagens`, junto do diário.

#### O despertar

O agente **acorda sozinho**: duas palmas, ou a palavra **"Jarvis"** dita isolada, abrem a HUD em
tela cheia e disparam a apresentação falada. Isso acontece **sem passar pela VPS** — o agente abre o
Chrome em `<urlHud>?inicio=1` e é a HUD que pede a introdução a `POST /api/inicializacao`. A VPS só
entra quando a tela **já** estava aberta: aí o agente chama `POST /api/pc/falar` e o Jarvis diz "Já
estou aqui, senhor.". Nesse endpoint o agente manda a **chave** de uma frase do catálogo fechado
`FalaDaTela`, nunca o texto.

### Arquivos no PC e Google Sheets

| Você pede | Ferramenta usada |
|---|---|
| "cria um relatório em PDF com isso" | `criar_arquivo` |
| "quais arquivos eu tenho?" | `listar_arquivos` |
| "lê a tabela de exemplo" | `ler_arquivo` |
| "muda o valor da célula C4" / "troca todas as ocorrências de X" | `editar_arquivo` |
| "desfaz a última edição" | `desfazer_edicao` (com confirmação) |
| "apaga o relatório antigo" | `apagar_arquivo` (com confirmação) |
| "restaura o que eu apaguei" / "o que tem na lixeira?" | `restaurar_arquivo` |
| "toda sexta atualiza essa planilha" | `agendar_tarefa_planilha` / `listar_tarefas_planilha` / `revogar_tarefa_planilha` |
| "cria uma planilha no meu Google" | `criar_planilha_google` |

#### Criar, listar e ler

`criar_arquivo` gera MD, TXT, DOCX, PDF, XLSX e CSV no agente do PC, dentro da pasta
`pastaArquivos` (padrão: Documentos/Jarvis). O comando leva dados estruturados, sem caminhos
nem código executável. O agente faz upload binário em `/api/pc/arquivos/{id}` com `PC_TOKEN`;
o resultado JSON apenas indica o ID e o estado. O limite do arquivo é 8 MiB. O arquivo é
enviado por `sendDocument` no Telegram e aparece como anexo temporário na HUD.

`listar_arquivos` pesquisa os arquivos dessa pasta por nome e formato, com páginas de até 50 itens.
O agente cadastra arquivos criados e também arquivos suportados que já estavam na pasta após validar
formato, tamanho e segurança do caminho. O catálogo `.jarvis-catalogo.properties` persiste no PC;
seu ID estável é diferente do ID temporário do upload. Cada item informa nome, formato, tamanho,
modificação e estado (`disponivel`, `alterado` ou `removido`). Resultados com nomes parecidos são
apresentados para escolha.

`ler_arquivo` recebe exclusivamente um ID do catálogo. TXT/MD e DOCX usam `inicio` e
`maximoCaracteres`; PDF usa `paginaInicial`, `quantidadePaginas` e `inicio` dentro da página;
XLSX usa `aba` e intervalo de linhas/colunas; CSV usa `delimitador`, `linhaInicial` e
`quantidadeLinhas`. A resposta traz indicador de truncamento e ponto de continuação. Uma linha
CSV ou célula XLSX grande usa `inicioLinha` ou `inicioCelula` com o próximo deslocamento retornado;
`fragmentoLinha` e `fragmentoCelula` transportam partes do JSON do item. A leitura limita o arquivo
a 8 MiB, o tempo a 12 segundos e o JSON de resultado a 28.000 bytes. Fórmulas
XLSX são devolvidas como dados com valores em cache possivelmente desatualizados; nada é executado.
PDF digitalizado sem texto informa que OCR não está implementado. Com o agente offline, os arquivos
originais locais não estão disponíveis. Google Sheets nativo não faz parte desta consulta.

#### Editar

`editar_arquivo` edita `.txt`, `.md`, `.json` e `.xlsx` diretamente na `pastaArquivos` do agente.
Use o ID e `versaoEsperada` (SHA-256) de `listar_arquivos`/`ler_arquivo` e leia o
arquivo antes de propor operações. São aceitas `substituir_trecho` (uma ocorrência
exata), `substituir_todas_ocorrencias` (busca literal, com opção de ignorar maiúsculas),
`acrescentar_ao_final` e `substituir_conteudo` (somente após leitura integral
e pedido explícito de reescrita). O agente devolve prévia limitada e `editId`;
sobrescrever `original` exige nova mensagem autenticada. `copia` cria arquivo novo
sem sobrescrever. `desfazer_edicao` recebe somente `editId`, confere backup e hash
atual, e também exige nova mensagem autenticada. Os backups ficam fora da listagem
comum no agente Windows. Se o arquivo foi salvo mas o upload falhou, o resultado
informa “salvo, entrega falhou” e o Jarvis não repete a edição.
O pedido “desfaça a última edição” usa o registro da última edição original concluída
enquanto o servidor estiver em execução; após reinício, informe o `editId` pela
consulta técnica de status.
No JSON, `atualizar_valor` exige JSON Pointer, tipo e valor anterior esperados, e altera
somente um escalar existente. O agente recusa JSON inválido, chaves repetidas, caminho
ausente e tipo divergente. A serialização pode mudar espaços e apresentação lexical.
Não há inserção, remoção, merge nem patch arbitrário de JSON.

Pedidos como “edite esse arquivo” usam somente o único anexo TXT/MD da resposta
imediatamente anterior do mesmo usuário e canal, entregue há no máximo dez minutos.
Sem essa referência, o Jarvis pede o nome exato. Se a edição falhar, o mesmo pedido
não cria um arquivo substituto. IDs e hashes ficam fora das mensagens comuns.

No XLSX, leia a versão atual e use `buscaValor` em `ler_arquivo` para localizar a célula;
`buscaCabecalho`, `tabela` e `aba` restringem a busca. Havendo mais de uma correspondência,
o Jarvis pede desambiguação. A proposta mostra aba, célula, tipo e antes/depois.
O usuário pode dizer “tabela exemplo” para `tabela_exemplo.xlsx`: a busca ignora
separadores e extensão, e o agente confirma que o apelido identifica um único XLSX.
Na leitura XLSX, `tipo` e `valorAnterior` já vêm no formato aceito pela edição:
por exemplo, uma célula booleana usa `booleano` e `false`, sem aspas.
As operações implementadas são atualizar/preencher célula, acrescentar/inserir linha,
inserir coluna, criar aba e expandir filtro. `atualizar_por_item` recebe o valor do item e o cabeçalho
da coluna, resolve a célula no agente do PC e recusa item ou cabeçalho ambíguo.
Linhas acrescentadas podem expandir tabela estruturada e
intervalos de séries de gráficos com referências diretas; referências não reconhecidas
fazem a edição falhar. Mesclas, proteção, links externos e estruturas que a biblioteca
não consiga preservar também são recusados. Fórmulas não são avaliadas: o arquivo é
marcado para recálculo quando aberto. Substituir fórmula por outro tipo exige autorização
explícita. Texto começando por `=`, `+`, `-` ou `@` continua texto.

##### Localização e inclusão em XLSX

Ao ler uma aba, `ler_arquivo` retorna `ultimaLinhaFisica`, `proximaLinhaFisica`,
`intervaloFiltro` (ou null) e `tabelas` com nome/intervalo (até 50, com `tabelasTruncadas`
quando necessário). O fim físico inclui notas e linhas formatadas. Uma consulta com os
quatro limites de linha/coluna informa também células ausentes: `existe=false`,
`tipo=vazio`, `valorAnterior=null`. Uma célula BLANK existente tem `existe=true`.

| Operação XLSX | Campos obrigatórios além de `tipo` |
| --- | --- |
| `atualizar_celula`, `preencher_celula_vazia` | `aba`, `celula`, `tipoAnterior`, `valorAnterior`, `tipoNovo`, `valorNovo` |
| `atualizar_por_item` | `itemValor`, `cabecalhoAlvo`, `tipoAnterior`, `valorAnterior`, `tipoNovo`, `valorNovo` |
| `acrescentar_linha` | `aba`, `linhaEsperada` = próxima linha física; cria linha vazia |
| `inserir_linha`, `inserir_coluna` | `aba` e `linha` ou `coluna`, com índice começando em 1 |
| `criar_aba` | `aba` |
| `expandir_filtro` | `aba`, `intervaloAnterior`, `intervaloNovo`, por exemplo `A1:H3` → `A1:H4` |

`atualizar_celula` exige célula existente. `preencher_celula_vazia` aceita célula ausente,
com tipo anterior `vazio` e valor anterior JSON `null`. Para uma tabela em A1:H3 com notas
a partir da linha 5, leia A4:H4, proponha os preenchimentos e `expandir_filtro` na mesma
edição. Não use `acrescentar_linha` com linha 4 se o fim físico estiver na linha 9.

A expansão mantém cabeçalho e colunas, atualiza o filtro e seu nome definido, e inclui
somente linhas originalmente vazias, preenchidas na mesma proposta. Recusa notas/dados
preexistentes nessas linhas, mesclas, tabelas estruturadas na aba, ordenação ou critérios
de filtro ativos e mudanças estruturais na mesma aba. Não recalcula fórmulas nem aplica
critérios de filtragem. As demais operações continuam com as limitações anteriores.

Conferência visual no LibreOffice: cabeçalho, larguras, notas e três abas do exemplo foram
preservados, sem aviso de reparo. O Calc ampliou o filtro em memória até a nota contígua
ao abrir seu menu, embora o XLSX grave A1:H4 corretamente. A prévia avisa quando há
conteúdo imediatamente abaixo; não movemos notas nem inserimos espaçadores implicitamente.
Esse comportamento de expansão automática também é descrito na
[ajuda do LibreOffice](https://help.libreoffice.org/latest/en-GB/text/scalc/guide/autofilter.html).

Falhas conhecidas retornam `dados.codigoErro`, `etapa`, `operacao` e `indiceOperacao`
(começando em 1; 0 se a falha precede uma operação). Exemplos: `CELULA_INEXISTENTE`,
`POSICAO_DIVERGENTE`, `CONFLITO_DE_CELULA`, `CONFLITO_DE_FILTRO`, `RECURSO_INCOMPATIVEL`
e `ACESSO_NEGADO`. Erros inesperados usam `FALHA_INTERNA`; falhas de I/O sem causa mais
específica usam `FALHA_DE_IO`. Não atribuir uma recusa de validação ao disco nem repetir
os mesmos parâmetros. `RESULTADO_INCERTO` exige consultar o estado antes de nova edição.
O agente registra ID, etapa, operação, código e classes da exceção/causa, sem valores
das células ou credenciais; esse resumo permanece no diário após o envio à VPS.

Para tarefas recorrentes, `agendar_tarefa_planilha` registra instrução, agenda cron de
seis campos e autorização para um XLSX inteiro após confirmação autenticada. Cada execução
lê a versão atual, localiza o alvo e usa o mesmo fluxo de proposta, gravação e backup,
sem pedir endereço de célula nem nova confirmação. `revogar_tarefa_planilha` cancela a
autorização. Se só for possível omitir gráficos numa cópia simplificada, o original fica
intacto e a criação da cópia exige confirmação naquela execução. Tarefas perdidas durante
uma parada não são repetidas. Os limites padrão de edição são 1 MiB por arquivo e
resultado (`PC_EDICAO_MAX_BYTES` e `PC_EDICAO_MAX_BYTES_RESULTADO`), com no máximo 10
operações por proposta (`PC_EDICAO_MAX_OPERACOES`); XLSX também limita o ZIP a 256 entradas,
32 MiB expandidos, 16 MiB por entrada e 100 mil células, sem desligar as proteções do POI.

#### Apagar e restaurar

Apagar arquivo da pasta do Jarvis é **reversível**: `apagar_arquivo` recebe só IDs de
`listar_arquivos` e move os arquivos para a lixeira do agente, em
`<pastaArquivos>/.lixeira/AAAA-MM-DD/`, como um registro (nome, formato, hash e data) ao lado dos
bytes originais. Nada é destruído na hora.

Por isso mesmo **nenhum dos dois pede confirmação**: apagar responde na hora ("Apaguei nota.md.
Fica na lixeira por 30 dias; se quiser, eu volto.") e restaurar traz de volta do mesmo jeito. Um
"Posso?" por arquivo, para algo que se desfaz numa frase, era atrito sem contrapartida.

**O que ainda pergunta é apagar em massa.** Até cinco arquivos no mesmo pedido saem direto; a
chamada que passar disso não move nada e devolve **uma** pergunta para a lista inteira — "Vou mover
12 arquivos para a lixeira: a.md, b.md e mais 10. Posso?". Os nomes vêm do catálogo do PC, nunca do
que o modelo escreveu; ID fora do catálogo, lista acima de 50 e qualquer nome de pasta protegida
(a própria `.lixeira`, os backups de edição) recusam a chamada inteira, sem apagar os outros.
Restaurar nunca sobrescreve: se já existir arquivo com aquele nome, o agente restaura como
`nome-1.ext` e avisa qual nome usou.

Sem `idLixeira`, `restaurar_arquivo` lista o que está na lixeira (nome, formato, data e prazo) para
o Jarvis apresentar as opções; com `idLixeira`, restaura aquele item. **A restauração devolve um ID
de catálogo novo** — a entrada antiga saiu do catálogo no movimento, então propostas de edição e
leituras registradas com o ID velho não valem mais.

O prazo é `lixeira.diasRetencao` no `config.properties` do PC (padrão 30 dias), e é o número que
o Jarvis fala: ele sai do próprio resultado do movimento, não de um valor repetido na VPS. Passado
o prazo, o item é apagado de vez; o expurgo roda na partida do agente e uma vez por dia. Arquivo aberto em
outro programa (o Excel numa planilha) e edição em voo no mesmo arquivo recusam o movimento, com o
arquivo intacto.

#### Google Sheets

`criar_planilha_google` cria uma planilha online nova somente após autorização OAuth pela HUD.
Configure `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI` e uma
`GOOGLE_CHAVE_COFRE` de 32 bytes em base64. Cadastre a URL de callback no Google Cloud,
habilite Drive API e Sheets API e mantenha o volume `google` (em `GOOGLE_PASTA_CREDENCIAIS`)
para o refresh token cifrado.
O escopo solicitado é `drive.file`; a ferramenta não altera planilhas existentes nem habilita
compartilhamento público. Sem configuração ou autorização Google, o Jarvis continua iniciando
normalmente. Só `criar_planilha_google` retorna um erro para a planilha online solicitada e
oferece XLSX local para importação; `criar_arquivo` continua disponível.

### Computer use: tarefa visual no PC

`tarefa_no_pc` é último recurso: use somente quando resposta direta, busca, arquivo, rotina
cadastrada ou ação fixa não resolver. A ferramenta guarda objetivo, usuário e pedido de origem;
para um pedido interativo que possa ser feito visualmente no PC, o Jarvis deve oferecer esse caminho
antes de alegar falta de ferramenta. Se o recurso estiver desativado ou o agente offline, deve dizer isso.
O cadastro `app.<nome>` restringe somente `controlar_pc/abrir_app`; no computer use visual, o agente
pode clicar no menu Iniciar para abrir outro aplicativo após a confirmação, sem comprar nem fazer login.
Uma **nova** mensagem como `confirmo`, `confirmo a tarefa` ou `pode prosseguir` (Telegram ou HUD) inicia a tarefa sem
devolver a decisão ao modelo. A mesma guarda autoriza `desligar`, `reiniciar` e `rodar_rotina`
na produção. `cancelar` encerra a intenção pendente. A confirmação vale dois minutos e serve uma vez;
as mensagens antigas com código ainda são aceitas. Os avisos usam um nome curto derivado do objetivo,
enquanto o ID permanece na API para consultas específicas. Pelo Telegram, status e cancelamento usam
a última tarefa do usuário quando nenhum ID é informado. Uma confirmação ainda pendente vive no servidor
Jarvis e não possui ID de tarefa no agente do PC: `cancelar_tarefa_no_pc` sem ID a cancela primeiro.
Um novo pedido autenticado do mesmo usuário também substitui a confirmação anterior sem executá-la.

**O recurso já foi usado e validado no PC real**: captura e escala no monitor principal, mudança de
resolução, interferência por mouse/teclado, atalho local e bandeja, queda de rede, cancelamento
remoto e uma tarefa completa até o resultado. Builds automatizados não cobrem entrada nativa do
Windows nem disponibilidade dos modelos na conta, então é a conferência no PC que vale como prova.

Para ligar, `COMPUTER_USE_ATIVO=true` aqui e `computer.use.ativo=true` no `config.properties` do
agente — os dois lados, de propósito. O padrão continua `false`, para quem clona o projeto não
ganhar controle de mouse e teclado sem pedir. O provedor padrão é Anthropic;
`COMPUTER_USE_PROVEDOR=openai` exige `OPENAI_API_KEY` e acesso ao modelo configurado. Revalide
modelos e formatos de ferramenta na documentação oficial antes de trocar versões.

Os tetos são 35 respostas do modelo (`COMPUTER_USE_MAX_CICLOS`), 75 ações individuais
(`COMPUTER_USE_MAX_ACOES`) e cinco minutos (`COMPUTER_USE_DURACAO_SEGUNDOS`). Cada ação vai pelo E2
com ID próprio, sem repetição após início registrado. Capturas da tarefa não são enviadas
automaticamente ao Telegram; são descartadas do diário da VPS após a rodada ou em até cinco minutos.
No agente, imagens de resultado não entregue expiram após cinco minutos.

A HUD consulta `GET /api/computer/ultima`, acompanha `computer.progresso` e pode pedir parada em
`POST /api/computer/{id}/cancelar`. O Telegram usa `status_tarefa_no_pc` e
`cancelar_tarefa_no_pc` sem ID para a última tarefa, ou com ID para uma tarefa específica. O atalho
local **Ctrl+Alt+Shift+F12** e o ícone da bandeja param novas ações sem rede. A ação já iniciada pode
terminar antes da parada; nesse caso o estado é `resultado_incerto`. O cancelamento remoto interrompe
a espera do servidor e bloqueia a próxima ação; sua latência depende da ação em curso e da rede. Com
rede indisponível, use a parada local. O monitor principal é o único capturado e controlado.
Alteração de geometria ou entrada do usuário entre ações interrompe a tarefa.

## API para a HUD

Além do Telegram, o Jarvis sobe um servidor web pequeno (porta `JARVIS_PORTA`, padrão 8090) para
alimentar a tela da HUD.

| Rota | O que faz |
|---|---|
| `POST /api/briefing` | Gera um briefing e devolve o JSON. Se já existe um de menos de 60 s, devolve esse (o campo `geradoAgora` diz qual foi o caso) |
| `GET /api/briefing/ultimo` | O último briefing gerado. `404` enquanto não houver nenhum |
| `GET /api/eventos` | SSE unificado de briefing, conversa, tela, voz e computer use, com keep-alive a cada 25 s |
| `GET /api/briefing/eventos` | Alias do mesmo SSE, mantido para a HUD antiga |
| `POST /api/conversa` | Recebe `{texto}` (1–8.000 caracteres); aceita com `202 {id}` e executa em virtual thread; `409` se já houver pedido HUD pendente |
| `GET /api/conversa/ultima` | Último resultado terminal da HUD, inclusive erro; `204` enquanto não existir |
| `GET /api/conversa/historico` | Dez trocas públicas compartilhadas, sessão, revisão, pedido HUD pendente e última resposta |
| `POST /api/conversa/limpar` | Limpa histórico dos dois canais e última resposta HUD; `204`, ou `409` durante pedido HUD |
| `GET /api/anexos/{id}` | Arquivo registrado por ferramenta; exige token, expira em uma hora; ausente/expirado retorna `404` |
| `POST /api/tela/estado` | A HUD informa o que está aberto na tela: `{janelas:[{id, tipo, titulo, estado, fixado, emFoco, tocando}], historicoAberto, briefingNaTela}`. `204`, ou `400` com o motivo |
| `POST /api/inicializacao` | A abertura falada: as frases que o Jarvis diz quando a tela acende, mais `sitesOk`/`sitesTotal` |
| `GET /api/voz/status` | Provedor de voz em uso e cota restante |
| `POST /api/voz/sintetizar` e `GET /api/voz/audio/{id}` | Síntese pela ElevenLabs e o MP3 em cache |
| `GET /api/computer/ultima`, `GET /api/computer/{id}`, `POST /api/computer/{id}/cancelar` | A tarefa visual do PC: estado e parada |
| `GET /api/google/oauth/iniciar` e `/api/google/oauth/callback` | A autorização do Google Sheets |
| `GET /api/saude` | `{"status":"ok"}`. Único endpoint sem token |

O agente do PC tem rotas próprias, em `/api/pc` e `/api/pc/v2`, protegidas pelo `PC_TOKEN` e não
pelo `HUD_TOKEN`.

### Autenticação

Todos os endpoints (menos `/api/saude`) exigem o token de `HUD_TOKEN`:

```bash
curl -X POST -H "Authorization: Bearer $HUD_TOKEN" http://localhost:8090/api/briefing
```

Gere o token com `openssl rand -hex 32`. **Se `HUD_TOKEN` ficar vazio, a API recusa tudo com 401** —
em dúvida, fecha. A comparação é feita em tempo constante, sobre o SHA-256 dos dois valores.

O 401 sai com os cabeçalhos de CORS (o filtro de CORS roda antes do de token), então o navegador
consegue ler `{"erro":"token ausente ou invalido"}` em vez de mostrar um erro de rede opaco — é o
que deixa a HUD dizer "token inválido" em vez de "API fora do ar".

O `EventSource` do navegador não sabe mandar header. Somente GET de `/api/eventos`, do alias
`/api/briefing/eventos`, de `/api/anexos/{id}` e do áudio da voz aceita também `?token=` — e o
`?token=` **não vale em `/api/pc`**. A HUD baixa anexos com Bearer e URLs de objeto locais; nunca
acrescenta credenciais a links externos.

```js
const fonte = new EventSource(`https://seu-dominio/api/eventos?token=${token}`);
fonte.addEventListener("gerando",  () => mostrarSpinner());
fonte.addEventListener("briefing", e => desenhar(JSON.parse(e.data)));
```

Conectar no stream não dispara pedidos. A HUD consulta briefing, histórico e última resposta
para restaurar a tela; repete a recuperação a cada reconexão. Um comentário de keep-alive vai
a cada 25 s. Não registre query strings com credenciais: o exemplo nginx desliga o access log
em `/api/`.

### Conversa compartilhada com o Telegram

A HUD usa o mesmo `chatId` de `TELEGRAM_USUARIO_PERMITIDO` e o mesmo `Assistente`. O que você
disser num canal continua no outro, mas respostas solicitadas pela HUD nunca são enviadas pelo
`TelegramClient`. A execução de conversas e tarefas é serializada pelo `ReentrantLock` justo do
`Assistente`; consultas ao retrato público do histórico não aguardam o modelo. Um pedido HUD pode
aguardar o Telegram; um segundo pedido HUD recebe `409`, sem fila adicional.

O SSE preserva `gerando` e `briefing` e acrescenta:

- `conversa.pensando`: `{id, canal, texto, estado, iniciadoEm}`; estado `aguardando` ou `processando`.
- `conversa.ferramenta`: `{id, canal, nome, descricao, passo, total}`; subetapas são opcionais.
- `conversa.resposta`: `{id, canal, texto, anexos, confirmacao, concluidoEm, erro}`.
- `conversa.erro`: mesmo resultado terminal, com mensagem pública em `erro`.
- `conversa.historico`: `{sessao, revisao}` para invalidar o histórico de qualquer canal.
- `tela.card` e `tela.comando`: as janelas da HUD (acima).
- `voz.falar`: `{texto}`, uma frase avulsa para a tela dizer, sem card nem janela.
- `computer.progresso`: o andamento da tarefa visual no PC.

Respostas do Telegram atualizam apenas o histórico na HUD. O `/limpar` também invalida esse
histórico. São expostos somente pedidos, respostas públicas e metadados; pensamentos e resultados
internos de ferramentas não entram nos DTOs.

`GET /api/conversa/historico` inclui `ultima` no mesmo retrato atômico, além de `emAndamento`.
O identificador de sessão muda no reinício. A HUD deduplica por ID/revisão, recupera respostas
perdidas e não repete automaticamente um POST de resultado incerto.

### Anexos e confirmações

`Ferramenta.executarDetalhado` devolve um `ResultadoFerramenta`, que pode trazer artefatos
explícitos (`Path`, nome e MIME), referências HTTP/HTTPS e uma confirmação
`{codigo, descricao, expiraEm}`. O registro copia apenas esses artefatos para uma pasta temporária
própria: 10 MiB por arquivo, 50 MiB no total e validade de uma hora. Uma limpeza periódica remove
expirados. Texto produzido pelo modelo nunca é interpretado como caminho de arquivo. Imagens raster
têm preview; os demais arquivos são entregues como download.

É por esse caminho que `criar_arquivo`, `captura_tela` e `ler_arquivo` entregam arquivo e imagem à
HUD e ao Telegram. Os botões de confirmação da HUD aparecem com os metadados explícitos e enviam
`confirmo <código>` ou `cancelo <código>`; para as ações protegidas do PC (desligar, reiniciar,
rotina, tarefa visual, sobrescrever arquivo, desfazer edição) basta uma **nova mensagem
autenticada**, consumida pelo servidor sem voltar ao modelo.

### O JSON do briefing

Cada seção vem nas **duas formas**: o `conteudo` em prosa (é o que a HUD fala, e o mesmo texto que
vai para o Telegram) e o `dados` estruturado, para a tela desenhar número grande, lista e imagem.
O `titulo` em minúsculas é o discriminador — `clima`, `lembretes de hoje`, `sistemas`, `notícias`,
sempre nessa ordem. Seção que falhou vem com `dados: null` e a explicação no `conteudo`.

Em `lembretes de hoje`, cada item traz um campo `recorrencia` — vazio em lembrete de uma só vez, ou
o texto da série ("todos os dias", "dias úteis", "dia 5 de cada mês"). É campo novo e opcional: a HUD
que não o conhece continua desenhando o card como antes.

```json
{
  "geradoEm": "2026-10-13T08:00:00-03:00",
  "fusoHorario": "America/Sao_Paulo",
  "nomeUsuario": "Anderson",
  "secoes": [
    {
      "titulo": "clima",
      "conteudo": "Curitiba: 18 °C agora (céu limpo), mínima 14 °C e máxima 22 °C\nChance de chuva: 30%",
      "dados": {
        "cidade": "Curitiba", "temperaturaAtual": 18, "minima": 14, "maxima": 22,
        "chanceChuvaPct": 30, "descricao": "céu limpo"
      }
    },
    {
      "titulo": "lembretes de hoje",
      "conteudo": "09:00 Enviar o currículo",
      "dados": { "itens": [{ "hora": "09:00", "texto": "Enviar o currículo", "recorrencia": "" }] }
    },
    {
      "titulo": "sistemas",
      "conteudo": "agendabarber-api: HTTP 200 em 180 ms (OK)\nServidor: CPU 12%, memória 1.2 GB/2.0 GB",
      "dados": {
        "sites": [{ "nome": "agendabarber-api", "resultado": "HTTP 200 em 180 ms (OK)" }],
        "servidor": "Servidor: CPU 12%, memória 1.2 GB/2.0 GB"
      }
    },
    {
      "titulo": "notícias",
      "conteudo": "• [tecnologia · G1] Manchete aqui",
      "dados": {
        "itens": [{
          "titulo": "Manchete aqui", "tema": "tecnologia", "fonte": "G1",
          "link": "https://g1.globo.com/...", "imagemUrl": "https://g1.globo.com/foto.jpg"
        }]
      }
    }
  ],
  "abertura": "Bom dia, senhor. Seu briefing está pronto.",
  "texto": "Bom dia, senhor. Seu briefing está pronto. terça-feira, 13/10 às 08:00\n\nCLIMA\n...",
  "geradoAgora": true
}
```

O `link` e o `imagemUrl` da notícia **só existem no `dados`**: a prosa não os carrega. O `imagemUrl`
vem vazio quando o feed não trouxe foto, então quem desenha precisa ter um estado sem imagem.

### A saudação

O cumprimento é **decidido aqui**, uma vez por briefing, a partir do instante de geração no fuso
configurado em `FUSO_HORARIO`: **bom dia das 05h às 11h59, boa tarde das 12h às 17h59 e boa noite nos
demais horários** (`dev.jarvis.briefing.Saudacao`). Dele sai uma **abertura canônica** — a mesma
string no campo `abertura`, no começo do `texto` do Telegram e no primeiro trecho da narração.

Por isso o modelo não escolhe mais o período do dia: o `NarradorDeBriefing` pede só "Seu briefing
está pronto." no trecho `[0]`, e o cumprimento é colado pelo aplicativo. Um modelo que inventasse
"bom dia" às dez da noite seria a única fonte de divergência entre o que a HUD mostra e o que ela
fala.

O `fusoHorario` acompanha o `geradoEm` para que a HUD formate a data no relógio do servidor em vez
do relógio do navegador. É campo novo: um consumidor antigo simplesmente o ignora.

O cache do briefing (30 min no serviço, 60 s no `POST`) **não atravessa a virada da data nem a
troca de período**: às 11h59 um briefing guardado ainda serve, às 12h00 ele é refeito, para não
abrir com "bom dia" depois do meio-dia. A abertura falada da HUD tem cache próprio, de 2 minutos,
que é o que impede palmas repetidas de gerarem duas chamadas ao Claude.

### CORS e HTTPS

`HUD_ORIGEM` lista as origens liberadas, separadas por vírgula (ex.:
`https://jarvis-hud-gamma.vercel.app,http://localhost:4200`). Vazio = nenhuma origem de navegador.

O `docker-compose` publica a porta **só em `127.0.0.1`**, porque quem deve expor isso na internet é
o nginx, com HTTPS:

```nginx
location /api/ {
    access_log off;                     # nunca gravar token da query string
    proxy_pass http://127.0.0.1:8090;
    proxy_http_version 1.1;
    proxy_set_header Connection "";     # SSE precisa de conexão persistente
    proxy_buffering off;                # sem isso o SSE chega em blocos
    proxy_read_timeout 1h;
}
```

## Segurança

- O bot **só responde ao seu ID**; mensagens de outras pessoas são ignoradas e registradas no log.
- **Não existe ferramenta de "executar comando"** no servidor, e não crie uma. Cada ação deve ser específica
  e limitada (ex.: `verificar_site` só acessa as URLs da lista, nunca uma URL qualquer).
- Para ações perigosas (apagar, reiniciar, pagar), peça confirmação antes de executar — e as do PC
  exigem uma **nova mensagem autenticada**, nunca a palavra do modelo no mesmo turno.
- **A confirmação é em linguagem natural, e quem interpreta é o servidor.** Você responde como
  quiser ("pode", "manda", "melhor não", "pode, mas só o de setembro"): o Jarvis não cobra palavra,
  código nem frase exata. Só quando existe uma pergunta em aberto é que o servidor compara a sua
  resposta — primeiro com uma lista de sim/não para frases curtas, de graça, e só depois com uma
  chamada curta ao Claude. Um "sim" executa, um "não" cancela, uma ressalva cancela a proposta e
  vira pedido novo, e **qualquer dúvida ou falha repete a pergunta em vez de executar**. O modelo
  que conversa com você nunca vê a decisão, só o resultado dela, então ele não tem como se
  autoconfirmar. As frases antigas com código (`confirmo 123456`) continuam funcionando.
- **Três níveis de autonomia**, ditos ao modelo em cada pedido:

  | Nível | O que entra |
  |---|---|
  | faz e avisa | ler, listar, criar, editar e desfazer arquivo na pasta do Jarvis; **apagar** e restaurar da lixeira; lembretes; tela da HUD; busca, clima e notícias; abrir app da lista; volume; captura de tela; memória |
  | pergunta antes | rotina que executa código; computer use; desligar e reiniciar o PC; **apagar mais de 5 arquivos no mesmo pedido**; agendar tarefa recorrente de planilha; esquecer memória |
  | proibido | comando de shell livre; arquivo fora da pasta do Jarvis; pagar, comprar, digitar senha; seguir instrução vinda de página, arquivo, tela ou memória |
- **`PC_TOKEN` é separado do `HUD_TOKEN`**: um token vazado numa tela aberta não desliga a máquina.
  Quem exige o do PC é um filtro só para `/api/pc`, e os dois conjuntos de rota são disjuntos por
  construção.
- **A VPS só busca endereço que ela provou ser público.** O `abrir_pagina` é a única ferramenta que
  acessa uma URL ditada pelo modelo, e ela passa pela trava de SSRF (`GuardaSsrf`): apenas http e
  https, DNS resolvido e recusado se apontar para loopback, faixa privada, link-local (inclusive o
  `169.254.169.254` dos metadados de nuvem) ou para um endereço das interfaces da própria máquina;
  no máximo 3 redirecionamentos, cada um revalidado antes de ser seguido; 8 s de timeout, 1 MB de
  leitura, e nenhum cookie ou cabeçalho de autenticação.

  Sobra um risco, e vale dizer com clareza: o `HttpClient` do Java não tem gancho de resolução de
  nome, então entre a nossa checagem e a conexão dele existe uma janela de *DNS rebinding* — um
  domínio hostil pode devolver IP público na checagem e `127.0.0.1` na conexão. Fechar isso
  exigiria fixar o IP no socket (quebrando o SNI e o certificado) ou uma dependência nova. O que
  se faz é limitar o dano: da página só voltam quatro metadados `og:`, nunca o HTML cru.
- As URLs das fontes do `mostrar_na_tela` também são validadas: elas viram link clicável na tela,
  e um `javascript:` ali seria script rodando na HUD.
- O conteúdo lido de memória, arquivo ou página é **dado não confiável**: não concede permissão nem
  autoriza ação nenhuma.
- O `.env` tem segredos: ele está no `.gitignore` e no `.dockerignore`; mantenha assim.
- A `config/personalidade.md` é versionada (repositório privado) e **não é lugar de segredo**: token,
  chave de API e senha ficam só no `.env`. Ela entra no container por volume somente leitura.

## Custos

O Jarvis registra cada resposta dos modelos Anthropic e, quando escolhido para computer use, OpenAI.
`custo_do_jarvis` responde quanto gastou hoje, nos últimos sete dias ou nos últimos trinta, por
origem e por modelo. Os números ficam num JSON por dia em `CUSTO_PASTA` (padrão
`/data/jarvis/custo`, volume `custo`), com troca atômica, `.bak` e retenção de 90 dias. O fuso
usado para separar os dias é `FUSO_HORARIO`. `CUSTO_TETO_DIARIO_USD` é opcional: ao cruzar 80% e
100% do teto, o Jarvis manda um aviso no Telegram uma vez por limite por dia. O teto não bloqueia
o serviço.

## O que o Jarvis fez sozinho

Ele edita arquivo, desfaz edição, apaga e restaura da lixeira, cria arquivo e anota memória **sem
perguntar**. Para isso não ficar invisível, cada uma dessas ações vai para um diário: um JSON por dia
em `DIARIO_PASTA` (padrão `/data/jarvis/diario`, volume `diario`), com troca atômica, `.bak` e
retenção de 30 dias. As ações que você **aprovou** entram no mesmo diário — rotina, tarefa no
monitor, desligar o PC —, marcadas como aprovadas. Apagar arquivo fica do lado de quem não
perguntou mesmo quando você aprovou um lote: o caminho normal é sem pergunta, e duas marcas para a
mesma ação diriam menos, não mais.

Pergunte a qualquer momento: *"o que você fez hoje?"*, *"o que mudou nos meus arquivos ontem?"*,
*"o que você anotou essa semana?"*. A ferramenta `acoes_do_jarvis` cobre hoje, ontem e os últimos
sete dias, e dá para pedir só uma parte (arquivos, memória ou PC).

O briefing também avisa: havendo ação feita por conta própria desde o briefing anterior, aparece uma
seção curta **"o que fiz sozinho"** com a contagem por tipo ("3 edições de arquivo, 1 anotação na
memória"). Sem nada feito sozinho, a seção não aparece — um briefing que diz "não fiz nada" todo dia
treina você a ignorar justamente a seção que importa no dia em que ela tiver conteúdo. O detalhe
fica para a ferramenta.

A descrição de cada linha é montada pelo servidor, a partir do nome real do arquivo ou do assunto
gravado — não é o modelo que escreve o que você lê sobre o que ele fez.

Os preços vêm do `.env`, não do código. `CUSTO_PRECOS` usa
`modelo=entrada/saida/cache_escrita/cache_leitura`, em dólares por milhão de tokens;
`CUSTO_PRECO_BUSCA_WEB` usa dólares por mil buscas. Para o Haiku 5.5, há uma segunda faixa no
`CUSTO_PRECOS_CONTEXTO_LONGO`, no formato `modelo@limiar=...`. O limiar soma tokens de entrada,
cache escrito e cache lido. Acima de 100 mil tokens de prompt, a tarifa maior vale para a chamada
inteira, inclusive saída e cache. O `.env.example` traz as duas faixas do modelo padrão;
[confira os valores atuais na Anthropic](https://platform.claude.com/docs/en/models/haiku-5-5/overview)
antes de usar. Modelo sem preço mantém a contagem de tokens, mas marca o valor em dólares como
incompleto e gera um WARN por dia.

O texto da personalidade usa cache de prompt de cinco minutos quando alcança o mínimo de tokens
do modelo; `cache_escrito` e `cache_lido` no log ajudam a conferir seu uso. O console da Anthropic
continua sendo a referência para a cobrança efetiva e permite definir um limite mensal.

Se trocar `ANTHROPIC_MODELO`, ajuste `ANTHROPIC_CACHE_MINIMO_TOKENS` junto: o menor prefixo cacheável muda
por modelo (512 tokens no Haiku 5.5 e no Sonnet 5.5, 4096 no Haiku 4.5).

Fora o modelo, há três contas que podem aparecer: a **busca na web** da Anthropic (configure o
preço por mil buscas, além dos tokens dos resultados), a **ElevenLabs** quando
`VOZ_PROVEDOR=elevenlabs` (com
`VOZ_RESERVA_PCT` guardando uma fatia da cota para a voz nunca acabar no meio de um briefing, e
`VOZ_AVISO_PERCENTUAL` avisando antes disso — veja [Proatividade](#proatividade)) e a
**YouTube Data API**, cuja cota diária de 10.000 unidades dá umas 100 buscas por dia.

## Proatividade

Quase tudo que o Jarvis diz é resposta a um pedido. O que ele diz **sem** ser chamado sai por um
caminho só, e esse caminho tem três regras:

1. **Horário de silêncio.** Dentro de `PROATIVO_SILENCIO` (padrão `23:00-07:00`, no seu fuso) nada
   sai. O aviso não é descartado: entra numa fila e é entregue junto, numa mensagem só, logo depois
   do fim da janela, sob "Enquanto o senhor dormia:". Um site que caiu às 2h ainda importa às 7h; o
   que não importa é o telefone apitando às 2h.
2. **Sem repetir.** "O site caiu" é notícia; "o site continua caído" você já sabe. O mesmo estado
   não é anunciado duas vezes.
3. **Um destino só**, o `TELEGRAM_USUARIO_PERMITIDO`.

Hoje dois assuntos falam primeiro:

**Os sites.** O Jarvis acompanha os `SITES_MONITORADOS` a cada `SITES_INTERVALO_MINUTOS` e avisa
quando um cai e quando volta. Um site só é dado como fora depois de **duas** checagens seguidas com
falha — uma requisição sozinha erra por um deploy de trinta segundos ou um DNS lento, e avisar na
primeira treinaria você a ignorar o aviso. A volta é anunciada na primeira checagem boa. Com uma
rotina associada em `SITES_ACOES`, o aviso termina num *"Posso rodar a rotina X para tentar
resolver?"*. Quanto cada site ficou no ar fica guardado por 30 dias em `PROATIVO_PASTA`.

**A cota de voz da ElevenLabs.** Passando de `VOZ_AVISO_PERCENTUAL` (padrão 80) do uso do período,
ele avisa **antes** de a voz acabar: *"A cota de voz está em 80%. No ritmo atual, dura até 04/11."*
A projeção vem do uso médio dos últimos dias, que o Jarvis guarda em disco; sem dois dias completos
de uso ele avisa sem projetar, em vez de chutar uma data, e quando no ritmo atual a cota chega à
renovação sem acabar, ele diz isso. É uma mensagem por período de cobrança. Os avisos que já
existiam — cota na reserva, chave recusada, cota renovada — passaram para o mesmo caminho, então
nenhum deles toca o telefone de madrugada. `VOZ_AVISO_PERCENTUAL=0` desliga só o aviso antecipado.

## Backup e restauração

Execute o backup com o container parado para copiar os volumes de memória, de lembretes e do
histórico no mesmo
ponto no tempo. No diretório
do Compose, agende este procedimento diariamente; o exemplo abaixo guarda o arquivo em uma pasta privada
da VPS e descarta arquivos de conteúdo com mais de 30 dias:

```bash
set -e
umask 077
mkdir -p backups-memoria
copia=$(mktemp -d)
trap 'docker compose start jarvis; rm -rf -- "$copia"' EXIT
docker compose stop jarvis
mkdir -p "$copia/memoria" "$copia/exclusoes" "$copia/lembretes" "$copia/historico"
docker run --rm --volumes-from jarvis -v "$copia:/backup" alpine:3.20 sh -c \
  'cp -a /data/jarvis/memoria/. /backup/memoria/; cp -a /data/jarvis/memoria-exclusoes/. /backup/exclusoes/; cp -a /data/jarvis/lembretes/. /backup/lembretes/; cp -a /data/jarvis/historico/. /backup/historico/'
tar -C "$copia" -czf "backups-memoria/memoria-$(date +%Y%m%d-%H%M%S).tar.gz" memoria exclusoes lembretes historico
tar -C "$copia" -czf backups-memoria/exclusoes-mais-recentes.tar.gz exclusoes
find backups-memoria -maxdepth 1 -name 'memoria-*.tar.gz' -mtime +30 -delete
```

Guarde `exclusoes-mais-recentes.tar.gz` fora da VPS também e atualize essa cópia após exclusões. Para
restaurar, mantenha o volume atual de exclusões e forneça o arquivo **mais recente** de marcadores.
Confira os nomes dos arquivos antes de executar:

```bash
set -e
umask 077
copia=$(mktemp -d)
trap 'rm -rf -- "$copia"' EXIT
tar -xzf backups-memoria/memoria-AAAAMMDD-HHMMSS.tar.gz -C "$copia"
tar -xzf backups-memoria/exclusoes-mais-recentes.tar.gz -C "$copia"
docker compose stop jarvis
docker run --rm --volumes-from jarvis -v "$copia:/backup:ro" alpine:3.20 sh -c '
  set -e
  find /data/jarvis/memoria -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
  cp -a /backup/memoria/. /data/jarvis/memoria/
  cp -an /backup/exclusoes/. /data/jarvis/memoria-exclusoes/
  # Os lembretes voltam inteiros: o Jarvis reagenda na partida e avisa o que venceu no meio.
  find /data/jarvis/lembretes -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
  cp -a /backup/lembretes/. /data/jarvis/lembretes/
  # O histórico é conversa: restaure só se quiser o contexto de volta. Turno com mais de
  # HISTORICO_VALIDADE_HORAS é descartado na partida, e o que foi esquecido não está aqui.
  find /data/jarvis/historico -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
  cp -a /backup/historico/. /data/jarvis/historico/
  chown -R 1001:1001 /data/jarvis/memoria /data/jarvis/memoria-exclusoes /data/jarvis/lembretes     /data/jarvis/historico
'
docker compose start jarvis
```

Teste a restauração em volumes separados antes de usar em produção. Backups de conteúdo são retidos por
até 30 dias; uma exclusão não remove imediatamente cópias antigas. Perder o volume e a cópia mais recente
dos marcadores pode fazer um backup antigo ressuscitar informações esquecidas; recuse a restauração nessa
situação.

O diário do E2 do PC (`PC_OPERACOES_PASTA`, volume `pc-operacoes`) deve ser restaurado **inteiro**,
inclusive `arquivos` e `imagens`: é o que deduplica comando reentregue e sustenta a reconciliação de
`resultado_incerto`.

## Limitações atuais

- Lembretes, histórico da conversa, memória pessoal, diário do PC, diário de ações, disponibilidade
  dos sites, cache de voz e uso diário de voz
  têm volume próprio e sobrevivem ao restart. O que **não** sobrevive: o retrato da tela, o cache de buscas no YouTube,
  a fila de comandos do PC e qualquer confirmação pendente. Depois de um restart o Jarvis fica cego
  para a tela até a HUD repostar o estado, e uma confirmação em curso precisa ser pedida de novo.
- Um restart na madrugada perde a fila do silêncio: o que estava acumulado para ser entregue às 7h
  vai embora, e o monitor volta sem saber que um site já estava fora (precisa de duas checagens
  novas para avisar outra vez). O aviso de cota de voz é a exceção — o marcador de "já avisei neste
  período" está em disco, então ele não se repete.
- O diário de ações guarda até 500 ações por dia. Passando disso, ele conta quantas ficaram de fora e
  a resposta avisa que a lista está incompleta; o que foi recusado não volta.
- No Telegram, recebe somente texto (nada de áudio ou foto). Na HUD, o navegador converte voz em
  texto e sintetiza a resposta — ou a ElevenLabs sintetiza, com `VOZ_PROVEDOR=elevenlabs`.
- Atende **uma execução por vez**: conversas, tarefas e limpeza passam pelo mesmo `ReentrantLock`
  justo do `Assistente`. Um segundo pedido vindo da HUD recebe `409` em vez de entrar em fila.
- A cota da YouTube Data API é de 10.000 unidades por dia e cada busca custa 100, ou seja umas
  100 buscas diárias. O cache de 24 h por consulta estica isso; estourando, o Jarvis avisa.
- A formatação do Telegram cobre o básico (negrito, código e listas); itálico, links e citações ainda saem
  como texto comum.
- O computer use é supervisionado por desenho: monitor principal só, tetos de ciclos/ações/tempo, e
  qualquer entrada do usuário entre ações encerra a tarefa.
- A edição de arquivo não consegue prometer compare-and-swap perfeito: um editor externo que não
  respeite lock ainda pode alterar o arquivo entre a última checagem e o move atômico.

## Próximos passos sugeridos

1. Receber áudio do Telegram e transcrever, para falar com o Jarvis por voz também lá.
2. Ferramenta que consulta a API do AgendaBarber (agendamentos do dia, faturamento).
3. Tirar da memória o que ainda é volátil: fila de comandos do PC e confirmações pendentes, hoje
   perdidas num restart.
4. Reconhecer imagem e documento recebidos no Telegram, e não só enviá-los.
