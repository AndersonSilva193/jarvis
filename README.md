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

Além disso, todo dia útil às 8h ele manda um resumo de bom dia (sites, servidor e lembretes).

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

docker compose up -d --build   # compila e sobe
docker compose logs -f         # acompanha os logs (Ctrl+C para sair)
```

Mudou o `.env` ou o código? Rode `docker compose up -d --build` de novo.

Para rodar no seu PC (sem Docker), exporte as mesmas variáveis do `.env` e rode `mvn spring-boot:run`.

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
| `GET /api/briefing/eventos` | Stream SSE: `gerando` quando um briefing começa e `briefing` quando fica pronto — inclusive os disparados pelo `/bomdia` e pelo cron das 8h |
| `GET /api/saude` | `{"status":"ok"}`. Único endpoint sem token |

### Autenticação

Todos os endpoints (menos `/api/saude`) exigem o token de `HUD_TOKEN`:

```bash
curl -X POST -H "Authorization: Bearer $HUD_TOKEN" http://localhost:8090/api/briefing
```

Gere o token com `openssl rand -hex 32`. **Se `HUD_TOKEN` ficar vazio, a API recusa tudo com 401** —
em dúvida, fecha. A comparação é feita em tempo constante, sobre o SHA-256 dos dois valores.

O `EventSource` do navegador não sabe mandar header, então **só a rota de eventos** aceita o token
na query string:

```js
const fonte = new EventSource(`https://seu-dominio/api/briefing/eventos?token=${token}`);
fonte.addEventListener("gerando",  () => mostrarSpinner());
fonte.addEventListener("briefing", e => desenhar(JSON.parse(e.data)));
```

Ao conectar, o último briefing é enviado na hora, para a HUD não abrir em branco. Um comentário de
keep-alive vai a cada 25 s, para a conexão não morrer em proxy.

O JSON traz as seções estruturadas **e** o texto pronto, então a HUD escolhe o que usar:

```json
{
  "geradoEm": "2026-10-13T08:00:00-03:00",
  "nomeUsuario": "Anderson",
  "secoes": [{ "titulo": "clima", "conteudo": "Curitiba: 18 °C agora..." }],
  "texto": "Bom dia, Anderson! terça-feira, 13/10 às 08:00

CLIMA
...",
  "geradoAgora": true
}
```

### CORS e HTTPS

`HUD_ORIGEM` lista as origens liberadas, separadas por vírgula (ex.:
`https://jarvis-hud.vercel.app,http://localhost:4200`). Vazio = nenhuma origem de navegador.

O `docker-compose` publica a porta **só em `127.0.0.1`**, porque quem deve expor isso na internet é
o nginx, com HTTPS:

```nginx
location /api/ {
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
- Para ações perigosas (apagar, reiniciar, pagar), peça confirmação antes de executar.
- O `.env` tem segredos: ele está no `.gitignore` e no `.dockerignore`; mantenha assim.

## Custos

Com o modelo padrão (Claude Haiku 4.5: US$ 1 por milhão de tokens de entrada e US$ 5 por milhão de saída),
uma mensagem comum costuma custar bem menos de 1 centavo de dólar. Você acompanha o gasto no console da
Anthropic e pode definir um limite mensal por lá.

## Limitações atuais

- Lembretes e histórico ficam **em memória**: somem se o container reiniciar.
- Só entende texto (nada de áudio ou foto ainda).
- Atende uma mensagem por vez.

## Próximos passos sugeridos

1. Salvar lembretes e histórico num banco (Postgres/MySQL com Spring Data JPA).
2. Ferramenta que consulta a API do AgendaBarber (agendamentos do dia, faturamento).
3. Receber áudio do Telegram e transcrever, para falar com o Jarvis por voz.
4. Ferramentas com confirmação ("tem certeza?") para ações que alteram dados.
