# Jarvis

Assistente pessoal no Telegram, feito em Java 21 + Spring Boot 4, que usa o Claude para entender pedidos em
linguagem natural e executar ferramentas reais. Roda 24h numa VPS dentro de um container Docker.

## O que ele já faz

| Você pede no Telegram | Ferramenta usada |
|---|---|
| "a API do AgendaBarber está no ar?" | `verificar_site` |
| "como está o servidor?" | `status_servidor` |
| "me lembra de mandar o currículo amanhã às 9h" | `criar_lembrete` |
| "quais lembretes eu tenho?" / "cancela o 2" | `listar_lembretes` / `cancelar_lembrete` |
| "mostra na tela a tabela do Brasileirão" | `mostrar_na_tela` |
| "põe um lo-fi para estudar" | `tocar_youtube` |
| "abre essa página na tela" | `abrir_pagina` |
| "fecha aquele card" / "limpa a tela" | `controlar_tela` |

Além disso, todo dia útil às 8h ele manda um resumo de bom dia (clima, lembretes, sites e notícias).

Ele responde no personagem descrito em `config/personalidade.md` — um arquivo de texto que você edita na
VPS e ele relê sozinho, sem rebuild e sem reiniciar o container.

## Como funciona

```
Telegram ──► TelegramPoller ──► Assistente ──► Claude (API)
                                    │   ▲            │
                                    ▼   │  "chame a ferramenta X"
                               Ferramentas ◄─────────┘
```

1. O `TelegramPoller` pergunta ao Telegram, em loop, se chegou mensagem (long polling, sem precisar de domínio
   nem porta aberta).
2. O `Assistente` manda a conversa e a lista de ferramentas para o Claude.
3. Se o Claude pedir uma ferramenta, o Java executa e devolve o resultado. Isso se repete até ele responder.
4. A resposta volta para você no Telegram.

### O prompt de sistema

Toda chamada ao Claude leva o `system` em **dois blocos**, nesta ordem:

| Bloco | Conteúdo | Cache |
|---|---|---|
| fixo | o texto de `config/personalidade.md`, byte a byte | `cache_control: ephemeral` |
| dinâmico | data e hora com fuso, canal do pedido, regras técnicas e de formato | nunca |

A ordem importa: o cache de prompt da Anthropic casa **prefixo exato**, então qualquer coisa que muda a cada
pedido (a começar pelo relógio) tem de ficar *depois* do bloco cacheado. O marcador de cache só é enviado
quando a personalidade passa de `ANTHROPIC_CACHE_MINIMO_TOKENS` — abaixo disso a API o ignoraria calada.

O **canal** diz ao Jarvis como escrever: `telegram` aceita negrito, listas e blocos de código; `hud` e
`briefing` viram áudio, então são frases curtas sem markdown; `tarefa` começa pelo resultado, com uma
linha de status. Cada chamada ao Claude registra no log quanto consumiu:

```
Claude: entrada=812 saída=96 cache_escrito=0 cache_lido=2310
```

`cache_escrito` aparece na primeira chamada (quando o prefixo é gravado) e `cache_lido` nas seguintes. Se as
duas ficarem sempre em zero, o cache não está pegando.

## Passo 1: pegar as credenciais

1. **Bot do Telegram:** fale com o [@BotFather](https://t.me/BotFather), envie `/newbot`, escolha nome e
   usuário. Ele te dá o **token**.
2. **Chave do Claude:** crie em [console.anthropic.com](https://console.anthropic.com), em *API Keys*.
   A API é paga por uso (créditos pré-pagos); adicione alguns dólares para começar.
3. **Seu ID do Telegram:** mande qualquer mensagem para o [@userinfobot](https://t.me/userinfobot), ou deixe
   `TELEGRAM_USUARIO_PERMITIDO=0`, suba o bot, mande um "oi" e veja o ID nos logs.

## Passo 2: subir na VPS

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

### A personalidade

`config/personalidade.md` é o system prompt fixo: identidade, tom, o que o Jarvis faz e o que não faz. Ele
é versionado junto com o código (o repositório é privado), mas **não entra na imagem**: chega ao container
por volume, montado como somente leitura em `/app/config`.

É justamente por isso que editar o texto **não** exige rebuild — o Jarvis compara a data de modificação do
arquivo a cada chamada e relê quando ela muda. Se o arquivo faltar ou estiver em branco, o bot sobe com uma
personalidade mínima de reserva e avisa no log, em vez de deixar de responder.

Segredo nenhum entra aqui: token, chave de API e afins continuam só no `.env`.

## Criando uma ferramenta nova

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

Dicas: a `descricao()` é o que faz o modelo escolher a ferramenta certa, então capriche nela. E devolva
textos curtos e objetivos: tudo que a ferramenta retorna vira tokens (custo).

## API para a HUD

Além do Telegram, o Jarvis sobe um servidor web pequeno (porta `JARVIS_PORTA`, padrão 8090) para
alimentar uma tela de HUD.

| Rota | O que faz |
|---|---|
| `POST /api/briefing` | Gera um briefing e devolve o JSON. Se já existe um de menos de 60 s, devolve esse (o campo `geradoAgora` diz qual foi o caso) |
| `GET /api/briefing/ultimo` | O último briefing gerado. `404` enquanto não houver nenhum |
| `GET /api/eventos` | SSE unificado de briefing e conversa, com keep-alive a cada 25 s |
| `GET /api/briefing/eventos` | Alias do mesmo SSE, mantido para a HUD antiga |
| `POST /api/conversa` | Recebe `{texto}` (1–8.000 caracteres); aceita com `202 {id}` e executa em virtual thread; `409` se já houver pedido HUD pendente |
| `GET /api/conversa/ultima` | Último resultado terminal da HUD, inclusive erro; `204` enquanto não existir |
| `GET /api/conversa/historico` | Dez trocas públicas compartilhadas, sessão, revisão, pedido HUD pendente e última resposta |
| `POST /api/conversa/limpar` | Limpa histórico dos dois canais e última resposta HUD; `204`, ou `409` durante pedido HUD |
| `GET /api/anexos/{id}` | Arquivo registrado por ferramenta; exige token, expira em uma hora; ausente/expirado retorna `404` |
| `POST /api/tela/estado` | A HUD informa o que está aberto na tela: `{janelas:[{id, tipo, titulo, estado, fixado, emFoco, tocando}], historicoAberto, briefingNaTela}`. `204`, ou `400` com o motivo |
| `GET /api/saude` | `{"status":"ok"}`. Único endpoint sem token |

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
`/api/briefing/eventos` e de `/api/anexos/{id}` aceita também `?token=`. A HUD baixa anexos com
Bearer e URLs de objeto locais; nunca acrescenta credenciais a links externos.

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
`TelegramClient`. A execução de conversas e tarefas é serializada por `ReentrantLock`; consultas
ao retrato público do histórico não aguardam o modelo. Um pedido HUD pode aguardar o Telegram;
um segundo pedido HUD recebe `409`, sem fila adicional.

O SSE preserva `gerando` e `briefing` e acrescenta:

- `conversa.pensando`: `{id, canal, texto, estado, iniciadoEm}`; estado `aguardando` ou `processando`.
- `conversa.ferramenta`: `{id, canal, nome, descricao, passo, total}`; subetapas são opcionais.
- `conversa.resposta`: `{id, canal, texto, anexos, confirmacao, concluidoEm, erro}`.
- `conversa.erro`: mesmo resultado terminal, com mensagem pública em `erro`.
- `conversa.historico`: `{sessao, revisao}` para invalidar o histórico de qualquer canal.

Respostas do Telegram atualizam apenas o histórico na HUD. O `/limpar` também invalida esse
histórico. São expostos somente pedidos, respostas públicas e metadados; pensamentos e resultados
internos de ferramentas não entram nos DTOs. Tudo permanece em memória e é perdido ao reiniciar.

`GET /api/conversa/historico` inclui `ultima` no mesmo retrato atômico, além de `emAndamento`.
O identificador de sessão muda no reinício. A HUD deduplica por ID/revisão, recupera respostas
perdidas e não repete automaticamente um POST de resultado incerto.

### Extensão para anexos e confirmações

`Ferramenta.executarDetalhado` adapta o retorno textual existente para `ResultadoFerramenta`.
Uma ferramenta futura poderá devolver artefatos explícitos (`Path`, nome e MIME), referências
HTTP/HTTPS e confirmação `{codigo, descricao, expiraEm}`. O registro copia apenas esses artefatos
para uma pasta temporária própria: 10 MiB por arquivo, 50 MiB no total e validade de uma hora.
Uma limpeza periódica remove expirados. Texto produzido pelo modelo nunca é interpretado como
caminho de arquivo. Imagens raster têm preview; os demais arquivos são entregues como download.

Esta entrega prepara o contrato e a interface; não adiciona ferramentas de criação de arquivos,
captura/controle de tela nem execução de ações protegidas. As ferramentas atuais não emitem
confirmação. Os botões da HUD só aparecem com metadados explícitos, enviando `confirmo <código>`
ou `cancelo <código>`; a ferramenta futura deverá gerar, validar e executar a ação correspondente.

Cada seção vem nas **duas formas**: o `conteudo` em prosa (é o que a HUD fala, e o mesmo texto que
vai para o Telegram) e o `dados` estruturado, para a tela desenhar número grande, lista e imagem.
O `titulo` em minúsculas é o discriminador — `clima`, `lembretes de hoje`, `sistemas`, `notícias`,
sempre nessa ordem. Seção que falhou vem com `dados: null` e a explicação no `conteudo`.

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
      "dados": { "itens": [{ "hora": "09:00", "texto": "Enviar o currículo" }] }
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
configurado em `JARVIS_ZONA`: **bom dia das 05h às 11h59, boa tarde das 12h às 17h59 e boa noite nos
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
abrir com "bom dia" depois do meio-dia.

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

### A tela dirigida pelo Jarvis

Além de mostrar o briefing, a HUD agora é dirigida pelo Jarvis: ele abre cards e comanda os que já
estão abertos. Funciona para pedido feito na própria HUD **e** no Telegram — "mostra na tela o
vídeo X" pelo celular abre a janela na tela de casa.

Cinco tipos de card: `pesquisa` (ranking, ficha, lista, linha do tempo ou destaque), `video`,
`musica`, `pagina` e `noticia`. Dois eventos novos no SSE:

| Evento | Carga |
|---|---|
| `tela.card` | `{id, tipo, titulo, dados, origem, criadoEm}` — abre uma janela |
| `tela.comando` | `{acao, alvo, valor}` — mexe no que já está aberto |

As ações de `tela.comando`: `limpar`, `fechar`, `fechar_tudo`, `minimizar`, `maximizar`,
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

## Segurança

- O bot **só responde ao seu ID**; mensagens de outras pessoas são ignoradas e registradas no log.
- **Não existe ferramenta de "executar comando"** no servidor, e não crie uma. Cada ação deve ser específica
  e limitada (ex.: `verificar_site` só acessa as URLs da lista, nunca uma URL qualquer).
- Para ações perigosas (apagar, reiniciar, pagar), peça confirmação antes de executar.
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
- O `.env` tem segredos: ele está no `.gitignore` e no `.dockerignore`; mantenha assim.
- A `config/personalidade.md` é versionada (repositório privado) e **não é lugar de segredo**: token,
  chave de API e senha ficam só no `.env`. Ela entra no container por volume somente leitura.

## Custos

Com o modelo padrão (Claude Haiku 5.5: US$ 0,10 por milhão de tokens de entrada e US$ 0,50 por milhão de
saída), uma mensagem comum custa uma fração de centavo de dólar. O texto da personalidade vai em cache de
prompt, então ele é pago cheio uma vez e sai por cerca de um décimo disso nas chamadas seguintes — é o que
os campos `cache_escrito` e `cache_lido` do log mostram. Você acompanha o gasto no console da Anthropic e
pode definir um limite mensal por lá.

Se trocar `ANTHROPIC_MODELO`, ajuste `ANTHROPIC_CACHE_MINIMO_TOKENS` junto: o menor prefixo cacheável muda
por modelo (512 tokens no Haiku 5.5 e no Sonnet 5.5, 4096 no Haiku 4.5).

## Limitações atuais

- Lembretes e histórico ficam **em memória**: somem se o container reiniciar.
- No Telegram, recebe somente texto. Na HUD, o navegador converte voz em texto e sintetiza a resposta.
- Atende uma mensagem por vez.
- O retrato da tela e o cache de buscas no YouTube ficam **em memória**: depois de um restart o
  Jarvis fica cego para a tela até a HUD repostar o estado.
- A cota da YouTube Data API é de 10.000 unidades por dia e cada busca custa 100, ou seja umas
  100 buscas diárias. O cache de 24 h por consulta estica isso; estourando, o Jarvis avisa.
- A formatação do Telegram cobre o básico (negrito, código e listas); itálico, links e citações ainda saem
  como texto comum.

## Próximos passos sugeridos

1. Salvar lembretes e histórico num banco (Postgres/MySQL com Spring Data JPA).
2. Ferramenta que consulta a API do AgendaBarber (agendamentos do dia, faturamento).
3. Receber áudio do Telegram e transcrever, para falar com o Jarvis por voz.
4. Ferramentas com confirmação ("tem certeza?") para ações que alteram dados.

## Validar a conversa e disponibilizar

Rode `mvn -q -B package` aqui e `npm test -- --watch=false` / `npm run build` em `jarvis-hud`.
Os testes usam modelo, reconhecimento e síntese simulados; nenhuma mensagem é enviada ao Telegram.
Incluem aceitação assíncrona, exclusão entre canais, SSE novo/alias, recuperação, autenticação,
anexos e metadados de confirmação simulados.

Quando for publicar, disponibilize primeiro este backend (mantém o alias da HUD antiga) e depois
a nova HUD. Não são necessárias dependências nem variáveis de ambiente adicionais. A validação
com microfone físico deve ser feita no navegador, seguindo o roteiro do README da HUD.

### Compatibilidade com janelas da HUD

O estado aceita também `resposta` e todas as janelas minimizadas, sem o antigo teto de 24.
A HUD envia primeiro o foco e a mídia em reprodução; o contexto continua limitado a 12 linhas.
`limpar` preserva cards fixados; `fechar_tudo` fecha inclusive os fixados.
Instale esta versão da API antes da HUD com janelas. O formato dos eventos SSE permanece igual.
