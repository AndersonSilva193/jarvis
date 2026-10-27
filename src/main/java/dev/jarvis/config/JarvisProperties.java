package dev.jarvis.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Todas as configurações do bot, lidas do application.yml (que por sua vez lê variáveis de ambiente / .env).
 */
@ConfigurationProperties(prefix = "jarvis")
public record JarvisProperties(
        String nomeUsuario,
        String fusoHorario,
        /* Formato: "nome=https://url,outro=https://url2" */
        String sites,
        Telegram telegram,
        Anthropic anthropic,
        Clima clima,
        Noticias noticias,
        Hud hud,
        Personalidade personalidade,
        Voz voz,
        BuscaWeb buscaWeb,
        Youtube youtube,
        Pc pc,
        ComputerUse computerUse,
        Google google,
        Memoria memoria,
        Lembretes lembretes,
        Historico historico,
        Custo custo,
        Diario diario,
        Proativo proativo) {

    private static final Logger log = LoggerFactory.getLogger(JarvisProperties.class);

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public JarvisProperties { }

    /**
     * A aridade de antes dos avisos proativos.
     *
     * Mesmo motivo de todos os atalhos daqui: os testes que informam o bloco {@code Diario} montam
     * este record <b>por posição</b>, e um componente novo no fim os quebraria todos de uma vez.
     */
    public JarvisProperties(String nomeUsuario, String fusoHorario, String sites, Telegram telegram,
            Anthropic anthropic, Clima clima, Noticias noticias, Hud hud,
            Personalidade personalidade, Voz voz, BuscaWeb buscaWeb, Youtube youtube, Pc pc,
            ComputerUse computerUse, Google google, Memoria memoria, Lembretes lembretes,
            Historico historico, Custo custo, Diario diario) {
        this(nomeUsuario, fusoHorario, sites, telegram, anthropic, clima, noticias, hud,
                personalidade, voz, buscaWeb, youtube, pc, computerUse, google, memoria, lembretes,
                historico, custo, diario, null);
    }

    /**
     * A aridade de antes do diário de ações.
     *
     * Existe pelo mesmo motivo dos outros atalhos abaixo: os testes que informam o bloco
     * {@code Custo} montam este record <b>por posição</b>, e um componente novo no fim os quebraria
     * todos de uma vez. Quem precisa do diário informa o componente.
     */
    public JarvisProperties(String nomeUsuario, String fusoHorario, String sites, Telegram telegram,
            Anthropic anthropic, Clima clima, Noticias noticias, Hud hud,
            Personalidade personalidade, Voz voz, BuscaWeb buscaWeb, Youtube youtube, Pc pc,
            ComputerUse computerUse, Google google, Memoria memoria, Lembretes lembretes,
            Historico historico, Custo custo) {
        this(nomeUsuario, fusoHorario, sites, telegram, anthropic, clima, noticias, hud,
                personalidade, voz, buscaWeb, youtube, pc, computerUse, google, memoria, lembretes,
                historico, custo, null);
    }

    public JarvisProperties(String nomeUsuario, String fusoHorario, String sites, Telegram telegram,
            Anthropic anthropic, Clima clima, Noticias noticias, Hud hud,
            Personalidade personalidade, Voz voz, BuscaWeb buscaWeb, Youtube youtube, Pc pc) {
        this(nomeUsuario, fusoHorario, sites, telegram, anthropic, clima, noticias, hud,
                personalidade, voz, buscaWeb, youtube, pc, null, null, null, null, null, null);
    }

    public JarvisProperties(String nomeUsuario, String fusoHorario, String sites, Telegram telegram,
            Anthropic anthropic, Clima clima, Noticias noticias, Hud hud,
            Personalidade personalidade, Voz voz, BuscaWeb buscaWeb, Youtube youtube, Pc pc,
            Google google) {
        this(nomeUsuario, fusoHorario, sites, telegram, anthropic, clima, noticias, hud,
                personalidade, voz, buscaWeb, youtube, pc, null, google, null, null, null, null);
    }

    public JarvisProperties(String nomeUsuario, String fusoHorario, String sites, Telegram telegram,
            Anthropic anthropic, Clima clima, Noticias noticias, Hud hud,
            Personalidade personalidade, Voz voz, BuscaWeb buscaWeb, Youtube youtube, Pc pc,
            Google google, Memoria memoria) {
        this(nomeUsuario, fusoHorario, sites, telegram, anthropic, clima, noticias, hud,
                personalidade, voz, buscaWeb, youtube, pc, null, google, memoria, null, null, null);
    }

    public JarvisProperties(String nomeUsuario, String fusoHorario, String sites, Telegram telegram,
            Anthropic anthropic, Clima clima, Noticias noticias, Hud hud,
            Personalidade personalidade, Voz voz, BuscaWeb buscaWeb, Youtube youtube, Pc pc,
            ComputerUse computerUse, Google google, Memoria memoria) {
        this(nomeUsuario, fusoHorario, sites, telegram, anthropic, clima, noticias, hud,
                personalidade, voz, buscaWeb, youtube, pc, computerUse, google, memoria, null, null, null);
    }

    /** Computer use fica desligado ate ser configurado e homologado no Windows. */
    public record ComputerUse(boolean ativo, String provedor, String modeloAnthropic,
            String modeloOpenai, String openaiApiKey, int maxCiclos, int maxAcoes,
            int duracaoSegundos) { }

    public record Telegram(String token, long usuarioPermitido) {
    }

    /**
     * Acesso à Messages API.
     *
     * @param cacheMinimoTokens menor prefixo que o modelo aceita cachear. Abaixo disso a API ignora o
     *                          cache_control em silêncio, então nem mandamos o marcador. Muda por
     *                          modelo: 512 no Haiku 5.5 e no Sonnet 5.5, 4096 no Haiku 4.5.
     * @param cacheTtl          quanto tempo uma entrada de cache vive: {@code 5m} ou {@code 1h}. O
     *                          mesmo valor vai no marcador do bloco fixo e no cache automático da
     *                          conversa, porque a API exige que o TTL mais longo apareça primeiro —
     *                          misturar os dois seria um 400. Use {@link #ttlDeCache()} para ler.
     * @param esforcos          quanto cada tipo de chamada pensa. Nulo é legítimo: sem o bloco,
     *                          nenhum {@code output_config} é emitido e o corpo do pedido fica
     *                          byte a byte igual ao de antes desta configuração existir.
     */
    public record Anthropic(String apiKey, String modelo, int maxTokens, int cacheMinimoTokens,
            String cacheTtl, Esforcos esforcos) {

        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Anthropic {
        }

        /** A aridade de antes do esforço por origem, para os testes que montam este bloco. */
        public Anthropic(String apiKey, String modelo, int maxTokens, int cacheMinimoTokens,
                String cacheTtl) {
            this(apiKey, modelo, maxTokens, cacheMinimoTokens, cacheTtl, null);
        }

        /** A aridade de antes do TTL configurável, para os testes que montam este bloco. */
        public Anthropic(String apiKey, String modelo, int maxTokens, int cacheMinimoTokens) {
            this(apiKey, modelo, maxTokens, cacheMinimoTokens, "5m");
        }

        /**
         * O {@code output_config.effort} de cada caminho que fala com o modelo, em texto cru.
         *
         * <p>Cru de propósito, como o {@code cacheTtl} e os valores de {@link Custo}: quem valida
         * é o {@code Esforco.porChave} do {@code ClaudeClient}, e um
         * {@code ESFORCO_CONVERSA=médio} no {@code .env} tem de cair no padrão com um WARN — não
         * estourar {@code BindException} e impedir o Jarvis de subir.
         *
         * <p>O valor é <b>fixo por perfil</b>, nunca escolhido pedido a pedido: trocar o esforço
         * no meio de uma conversa invalida o cache de prompt que o passo anterior acabou de
         * escrever. É por isso que a configuração é por origem e é resolvida uma vez, na subida.
         */
        public record Esforcos(String conversa, String briefing, String inicializacao,
                String apoio, String tarefa) {
        }

        /**
         * O TTL em forma aceita pela API: {@code 1h} quando foi isso que pediram, {@code 5m} em
         * qualquer outro caso.
         *
         * <p>Valor torto no {@code .env} cai no padrão em vez de virar um HTTP 400 a cada pedido —
         * é a mesma leniência dos acessores de custo.
         */
        public String ttlDeCache() {
            return cacheTtl != null && "1h".equals(cacheTtl.trim().toLowerCase(java.util.Locale.ROOT))
                    ? "1h" : "5m";
        }
    }

    /**
     * Busca na web pela ferramenta server-side da Messages API: a definição vai no array de
     * ferramentas e quem executa é o servidor da Anthropic, não o Jarvis.
     *
     * @param tipo    versão do tipo da ferramenta. "web_search_20250305" é a básica e roda em
     *                qualquer modelo atual; as versões de 2026 fazem filtragem dinâmica, que roda a
     *                busca dentro do code execution e exige modelo com programmatic tool calling.
     *                Confira pelo Models API (capabilities.server_tools.web_search) antes de trocar.
     * @param maxUsos teto de buscas por pedido. Cada busca é cobrada à parte dos tokens.
     */
    public record BuscaWeb(boolean ativa, String tipo, int maxUsos) {
    }

    /** Cidade do clima e suas coordenadas (a Open-Meteo trabalha com latitude/longitude). */
    public record Clima(String cidade, double latitude, double longitude) {
    }

    /** Feeds de notícias no formato "tema|url,tema|url". */
    public record Noticias(String feeds) {
    }

    /** API da HUD: token exigido nos endpoints e origens liberadas no CORS. */
    public record Hud(String token, String origens) {
    }

    /**
     * Voz da HUD.
     *
     * @param provedor       "elevenlabs" ou "navegador". Em "navegador" nada é enviado à ElevenLabs.
     * @param reservaPct     fatia da cota que o Jarvis se recusa a gastar, em por cento do limite.
     * @param avisoPercentual uso da cota que dispara o aviso antecipado, uma vez por período de
     *                        cobrança. Zero desliga o aviso.
     */
    public record Voz(String provedor, String apiKey, String vozId, String modelo, int reservaPct,
            int avisoPercentual) {

        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Voz {
        }

        /** A aridade de antes do aviso antecipado, para os testes que montam este bloco. */
        public Voz(String provedor, String apiKey, String vozId, String modelo, int reservaPct) {
            this(provedor, apiKey, vozId, modelo, reservaPct, 80);
        }
    }

    /** Arquivo markdown com a personalidade do Jarvis, lido em tempo de execução (sem rebuild). */
    public record Personalidade(String arquivo) {
    }

    /**
     * Chave da YouTube Data API v3, usada pela ferramenta que toca vídeo e música na HUD.
     *
     * Cada {@code search.list} custa 100 das 10.000 unidades diárias de cota — daí o cache de
     * 24 horas por consulta no {@code YoutubeService}. Sem chave, a ferramenta diz isso ao modelo.
     */
    public record Youtube(String apiKey) {
    }

    /**
     * Agente que roda no PC Windows e busca comandos por long polling.
     *
     * O token é separado do {@code HUD_TOKEN} de propósito: quem tem a HUD não manda na máquina, e
     * um token vazado numa tela aberta não vira desligamento de PC. A lista de programas liberados
     * <b>não</b> mora aqui — ela é do {@code config.properties} do agente, que a reporta no
     * cabeçalho {@code X-Pc-Apps} de cada consulta. Assim o enum de {@code abrir_app} tem uma
     * única fonte de verdade, no PC.
     */
    public record Pc(String token, String operacoesPasta, int edicaoMaxBytes,
            int edicaoMaxOperacoes, int edicaoMaxBytesResultado) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Pc { }
        public Pc(String token, String operacoesPasta) {
            this(token, operacoesPasta, 1024 * 1024, 10, 1024 * 1024);
        }
        public Pc(String token) { this(token, "/data/pc/operacoes.json"); }
    }

    /** Credenciais OAuth da integração opcional com uma conta Google. */
    public record Google(String clientId, String clientSecret, String redirectUri,
            String chaveCofre, String pastaCredenciais) { }

    /**
     * Volumes separados para registros e marcadores permanentes de exclusão.
     *
     * @param anotadorAtivo          se o Jarvis anota sozinho os fatos duráveis que o usuário conta,
     *                               sem ele precisar pedir. Desligado, só as ferramentas explícitas
     *                               gravam.
     * @param anotadorOciosoMinutos  quanto tempo sem turno novo fecha o trecho e manda o anotador
     *                               olhar a conversa. O outro gatilho é a contagem de turnos.
     */
    public record Memoria(String pasta, String pastaExclusoes, boolean anotadorAtivo,
            int anotadorOciosoMinutos) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Memoria { }
        public Memoria(String pasta, String pastaExclusoes) { this(pasta, pastaExclusoes, true, 5); }
    }

    /** Arquivo JSON onde os lembretes sobrevivem ao restart do container. */
    public record Lembretes(String arquivo) { }

    /**
     * Pasta onde o histórico da conversa sobrevive ao restart: um arquivo JSON por chat.
     *
     * @param validadeHoras         idade máxima de um turno restaurado. Conversa de ontem não
     *                              ajuda a responder a de hoje, e guardar o que o dono falou por
     *                              tempo indeterminado não é o acordo.
     * @param turnosCompletos       quantos turnos do fim ficam inteiros, com os resultados de
     *                              ferramenta. É {@code Integer} de propósito: <b>zero é um valor
     *                              legítimo</b> (compactar todos), então a regra
     *                              "não positivo vira o padrão" dos outros acessores mentiria aqui.
     * @param maxCaracteresResposta teto da resposta guardada no turno compacto. {@code int} porque
     *                              zero caractere não quer dizer nada.
     */
    public record Historico(String pasta, int validadeHoras,
            Integer turnosCompletos, int maxCaracteresResposta) {

        /** A aridade de antes do histórico compacto, pelo motivo de sempre: construção por posição. */
        public Historico(String pasta, int validadeHoras) {
            this(pasta, validadeHoras, null, 0);
        }
    }

    /**
     * O medidor de custo: onde o diário por dia é gravado, a tabela de preços e o teto que avisa.
     *
     * Os valores opcionais são String, e não double, de propósito. O padrão pedido é "vazio = sem
     * teto", e {@code CUSTO_TETO_DIARIO_USD=} no .env estouraria {@code BindException} num campo
     * numérico — {@code ${VAR:default}} só usa o default quando a variável é <i>indefinida</i>, não
     * quando é vazia, e aí o Jarvis não subiria por causa de uma linha em branco. A conversão mora
     * nos acessores lá embaixo, como já acontece com {@code sites}.
     *
     * @param precos        "modelo=entrada/saida/cache_escrita/cache_leitura", em dólares por
     *                      MILHÃO de tokens, separados por ponto e vírgula. Preço não fica no
     *                      código: ele muda sem aviso, e número errado sobre dinheiro é pior que
     *                      número ausente.
     * @param precoBuscaWeb dólares por MIL buscas — denominador diferente do de cima, porque é
     *                      assim que a Anthropic publica. Vazio deixa a busca fora da conta.
     * @param tetoDiarioUsd gasto do dia que dispara aviso em 80% e em 100%. Vazio = sem teto. O
     *                      teto avisa no Telegram; nunca bloqueia o Jarvis.
     * @param precosContextoLongo "modelo@limiar=entrada/saida/cache_escrita/cache_leitura";
     *                           acima do limiar, a tarifa vale para a chamada inteira.
     */
    public record Custo(String pasta, String precos, String precoBuscaWeb, String tetoDiarioUsd,
            String precosContextoLongo) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Custo { }
        public Custo(String pasta, String precos) { this(pasta, precos, "", "", ""); }
        public Custo(String pasta, String precos, String precoBuscaWeb, String tetoDiarioUsd) {
            this(pasta, precos, precoBuscaWeb, tetoDiarioUsd, "");
        }
    }

    /**
     * Pasta do diário de ações: um JSON por dia com o que o Jarvis fez sozinho e o que fez depois
     * de um "sim". Volume próprio, porque autonomia sem registro é autonomia que não se audita.
     *
     * <p>Só a pasta é configurável. Os 30 dias de retenção e o teto de ações por dia são constantes
     * do {@code DiarioDeAcoes}, pela mesma regra do {@code MedidorDeCusto}: não vale um componente
     * num record construído por posição em dezenas de testes para um número que ninguém vai mexer.
     */
    public record Diario(String pasta) { }

    /**
     * Os avisos que o Jarvis manda sem ter sido chamado.
     *
     * <p>{@code sitesIntervaloMinutos} e {@code sitesAcoes} moram aqui, e não num bloco
     * {@code sites}, por um motivo bem concreto: {@code jarvis.sites} já é o <b>escalar</b> com a
     * lista de URLs, e {@code jarvis.sites.algo} colidiria com ele no bind.
     *
     * @param silencio faixa de horas em que nada sai, no formato {@code "23:00-07:00"}. Quem a
     *                 interpreta é a {@code JanelaDeSilencio}, uma vez só — aqui fica o texto cru,
     *                 como nos preços do {@code Custo}.
     * @param sitesAcoes {@code nome_do_site=nome_da_rotina}, separados por vírgula. A rotina só
     *                   existe se o agente do PC a anunciar; site fora de SITES_MONITORADOS é
     *                   ignorado com WARN.
     */
    public record Proativo(String silencio, String pasta, int sitesIntervaloMinutos,
            String sitesAcoes) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Proativo { }
        public Proativo(String silencio, String pasta) { this(silencio, pasta, 5, ""); }
    }

    /** Se a busca no YouTube está disponível. Sem chave, a ferramenta avisa em vez de falhar. */
    public boolean youtubeConfigurado() {
        return youtube != null && youtube.apiKey() != null && !youtube.apiKey().isBlank();
    }

    /** Token do agente do PC. Vazio = /api/pc recusa tudo com 401 (em dúvida, fecha). */
    public String tokenDoPc() {
        return pc == null || pc.token() == null ? "" : pc.token();
    }

    /** Se o controle do PC está configurado. Sem token, a rota do agente recusa tudo. */
    public boolean pcConfigurado() {
        return !tokenDoPc().isBlank();
    }

    /** Caminho do arquivo de lembretes. Dentro do container é o volume montado em /data/jarvis. */
    public String arquivoDeLembretes() {
        String caminho = lembretes == null ? null : lembretes.arquivo();
        return caminho == null || caminho.isBlank() ? "/data/jarvis/lembretes/lembretes.json" : caminho;
    }

    /** Pasta do histórico. Dentro do container é o volume montado em /data/jarvis/historico. */
    public Path pastaDoHistorico() {
        String caminho = historico == null ? null : historico.pasta();
        return Path.of(caminho == null || caminho.isBlank() ? "/data/jarvis/historico" : caminho);
    }

    /**
     * Validade de um turno salvo, com 72 h de padrão e piso de 1 h (zero desligaria o recurso).
     *
     * Três dias, e não um: com 24 h a conversa de sexta-feira já não existia na segunda, e o
     * assunto que o dono deixou pela metade morria no fim de semana.
     */
    public java.time.Duration validadeDoHistorico() {
        int horas = historico == null || historico.validadeHoras() <= 0 ? 72 : historico.validadeHoras();
        return java.time.Duration.ofHours(horas);
    }

    /**
     * Quantos turnos do fim do histórico ficam inteiros, com as idas e vindas de ferramenta.
     *
     * <p>Um de padrão: é o que faz "apaga o segundo" e "abre esse" continuarem funcionando logo
     * depois de uma listagem. Os anteriores viram pedido, resposta e uma nota do que foi usado.
     * Aparado a dez, que é a própria janela do histórico — pedir mais não guarda mais nada.
     */
    public int turnosCompletosNoHistorico() {
        Integer quantos = historico == null ? null : historico.turnosCompletos();
        return quantos == null ? 1 : Math.clamp(quantos, 0, 10);
    }

    /**
     * Teto da resposta guardada num turno compacto, com 1500 de padrão e piso de 200 — abaixo
     * disso não sobra resposta, e um turno que só diz "[…]" é pior que nenhum.
     */
    public int maxCaracteresDaRespostaNoHistorico() {
        int teto = historico == null || historico.maxCaracteresResposta() <= 0 ? 1500
                : historico.maxCaracteresResposta();
        return Math.max(200, teto);
    }

    /** Se o Jarvis anota sozinho. Null-safe: quem monta as properties à mão fica sem o recurso. */
    public boolean anotadorDeMemoriaAtivo() {
        return memoria != null && memoria.anotadorAtivo();
    }

    /**
     * Quanto tempo parado fecha o trecho do anotador, com 5 minutos de padrão e piso de 1 minuto —
     * zero ou negativo desligaria o gatilho de ociosidade sem desligar o recurso.
     */
    public java.time.Duration ociosidadeDoAnotador() {
        int minutos = memoria == null || memoria.anotadorOciosoMinutos() <= 0
                ? 5 : memoria.anotadorOciosoMinutos();
        return java.time.Duration.ofMinutes(minutos);
    }

    /** Pasta do diário de custo. Dentro do container é o volume montado em /data/jarvis/custo. */
    public Path pastaDeCusto() {
        String caminho = custo == null ? null : custo.pasta();
        return Path.of(caminho == null || caminho.isBlank() ? "/data/jarvis/custo" : caminho);
    }

    /** Pasta do diário de ações. Dentro do container é o volume montado em /data/jarvis/diario. */
    public Path pastaDoDiario() {
        String caminho = diario == null ? null : diario.pasta();
        return Path.of(caminho == null || caminho.isBlank() ? "/data/jarvis/diario" : caminho);
    }

    /** Pasta do histórico de disponibilidade, no volume montado em /data/jarvis/proativo. */
    public Path pastaDoProativo() {
        String caminho = proativo == null ? null : proativo.pasta();
        return Path.of(caminho == null || caminho.isBlank() ? "/data/jarvis/proativo" : caminho);
    }

    /** A faixa de silêncio crua. Quem a interpreta é a {@code JanelaDeSilencio}, uma vez só. */
    public String silencioProativo() {
        String faixa = proativo == null ? null : proativo.silencio();
        return faixa == null ? "" : faixa;
    }

    /**
     * Rotina associada a cada site, para o aviso de queda poder oferecer uma saída.
     *
     * <p>Site que não está em {@link #sitesMonitorados()} é <b>recusado com WARN</b>, e não
     * simplesmente guardado: uma rotina amarrada a um nome com typo nunca dispararia, e o dono
     * descobriria isso justamente no dia em que precisasse dela.
     */
    public Map<String, String> acoesDeSites() {
        Map<String, String> mapa = new LinkedHashMap<>();
        String config = proativo == null ? null : proativo.sitesAcoes();
        if (config == null || config.isBlank()) {
            return mapa;
        }
        Map<String, String> conhecidos = sitesMonitorados();
        for (String par : config.split(",")) {
            String entrada = par.trim();
            if (entrada.isEmpty()) {
                continue;
            }
            String[] partes = entrada.split("=", 2);
            if (partes.length < 2 || partes[0].isBlank() || partes[1].isBlank()) {
                log.warn("SITES_ACOES: entrada mal formada ignorada: {}", entrada);
                continue;
            }
            String site = partes[0].trim();
            String rotina = partes[1].trim();
            if (!conhecidos.containsKey(site)) {
                log.warn("SITES_ACOES: site '{}' não está em SITES_MONITORADOS; entrada ignorada",
                        site);
                continue;
            }
            if (mapa.containsKey(site)) {
                log.warn("SITES_ACOES: site '{}' repetido; mantida a primeira rotina e ignorada "
                        + "a rotina '{}'", site, rotina);
                continue;
            }
            mapa.put(site, rotina);
        }
        return mapa;
    }

    /** Minutos entre duas checagens do monitor de sites, com 5 de padrão e piso de 1. */
    public int intervaloDeChecagemDeSites() {
        int minutos = proativo == null || proativo.sitesIntervaloMinutos() <= 0
                ? 5 : proativo.sitesIntervaloMinutos();
        return Math.max(1, minutos);
    }

    /** A tabela de preços crua. Quem a interpreta é a {@code TabelaDePrecos}, uma vez só. */
    public String precosDeCusto() {
        String precos = custo == null ? null : custo.precos();
        return precos == null ? "" : precos;
    }

    /** Sobrescritas de preço para prompts acima do limiar configurado por modelo. */
    public String precosDeContextoLongo() {
        String precos = custo == null ? null : custo.precosContextoLongo();
        return precos == null ? "" : precos;
    }

    /** Dólares por mil buscas na web. Vazio ou torto vira zero: a busca fica fora da conta. */
    public double precoDaBuscaWeb() {
        return numeroDeCusto(custo == null ? null : custo.precoBuscaWeb(), "CUSTO_PRECO_BUSCA_WEB");
    }

    /** Teto de gasto do dia. Zero = sem teto, que é o padrão de quem não configurou nada. */
    public double tetoDiarioDeCusto() {
        return numeroDeCusto(custo == null ? null : custo.tetoDiarioUsd(), "CUSTO_TETO_DIARIO_USD");
    }

    public boolean tetoDeCustoAtivo() {
        return tetoDiarioDeCusto() > 0;
    }

    /**
     * Lê um dólar da configuração. Vazio é o "não configurado" esperado e sai calado; texto torto
     * avisa e vira zero — um typo no .env não pode impedir o Jarvis de subir.
     */
    private static double numeroDeCusto(String bruto, String variavel) {
        if (bruto == null || bruto.isBlank()) {
            return 0;
        }
        try {
            double valor = Double.parseDouble(bruto.trim());
            return Double.isFinite(valor) ? Math.max(0, valor) : 0;
        } catch (NumberFormatException e) {
            log.warn("{}: '{}' não é um número; seguindo como se estivesse vazio", variavel, bruto.trim());
            return 0;
        }
    }

    public ZoneId zona() {
        return ZoneId.of(fusoHorario == null || fusoHorario.isBlank() ? "America/Sao_Paulo" : fusoHorario);
    }

    /**
     * Se a busca na web está ligada. Null-safe de propósito: quem monta as properties à mão (testes,
     * chamadas internas) deixa o bloco de fora e a busca simplesmente fica desligada.
     */
    public boolean buscaWebAtiva() {
        return buscaWeb != null && buscaWeb.ativa();
    }

    /** Caminho do arquivo de personalidade. Dentro do container é o volume montado em /app/config. */
    public Path arquivoDaPersonalidade() {
        String caminho = personalidade == null ? null : personalidade.arquivo();
        return Path.of(caminho == null || caminho.isBlank() ? "/app/config/personalidade.md" : caminho);
    }

    /** Origens liberadas no CORS da HUD. Lista vazia significa nenhuma origem de navegador. */
    public List<String> origensDaHud() {
        String config = hud == null ? null : hud.origens();
        if (config == null || config.isBlank()) {
            return List.of();
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(origem -> !origem.isBlank())
                .toList();
    }

    /**
     * Verdadeiro quando a ElevenLabs está escolhida E tem chave e voz configuradas. Faltando
     * qualquer um dos três, a HUD fala pela voz do navegador — melhor do que descobrir isso
     * no meio de um briefing mudo.
     */
    public boolean vozRemotaConfigurada() {
        return voz != null
                && "elevenlabs".equalsIgnoreCase(voz.provedor())
                && voz.apiKey() != null && !voz.apiKey().isBlank()
                && voz.vozId() != null && !voz.vozId().isBlank();
    }

    /** Modelo de voz, com o Flash v2.5 de padrão: fala português e custa metade por caractere. */
    public String modeloDeVoz() {
        String modelo = voz == null ? null : voz.modelo();
        return modelo == null || modelo.isBlank() ? "eleven_flash_v2_5" : modelo;
    }

    /** Fatia intocável da cota. Limitada a 0-100 para um valor errado no .env não travar a voz. */
    public int reservaDeVozPct() {
        return Math.clamp(voz == null ? 5 : voz.reservaPct(), 0, 100);
    }

    /**
     * Uso da cota que dispara o aviso antecipado de voz. Zero desliga; fora de 0-100 é aparado.
     *
     * <p>Aparado como a reserva, e pelo mesmo motivo: um número torto no {@code .env} não pode nem
     * calar o aviso para sempre nem fazê-lo sair a cada consulta.
     */
    public int avisoDeVozPct() {
        return Math.clamp(voz == null ? 80 : voz.avisoPercentual(), 0, 100);
    }

    /**
     * Converte a string de feeds em um mapa tema -> lista de URLs, mantendo a ordem.
     * O mesmo tema pode aparecer várias vezes ("tecnologia|url1,tecnologia|url2"): os feeds se somam.
     */
    public Map<String, List<String>> feedsPorTema() {
        Map<String, List<String>> mapa = new LinkedHashMap<>();
        String config = noticias == null ? null : noticias.feeds();
        if (config == null || config.isBlank()) {
            return mapa;
        }
        for (String par : config.split(",")) {
            String[] partes = par.trim().split("\\|", 2);
            if (partes.length == 2 && !partes[0].isBlank() && !partes[1].isBlank()) {
                mapa.computeIfAbsent(partes[0].trim(), tema -> new ArrayList<>()).add(partes[1].trim());
            }
        }
        return mapa;
    }

    /**
     * Converte a string de sites monitorados em um mapa nome -> URL, mantendo a ordem.
     *
     * Nome repetido mantém a <b>primeira</b> ocorrência e avisa em WARN: antes a segunda
     * sobrescrevia a primeira em silêncio, e um site simplesmente deixava de ser checado.
     * Entrada mal formada (sem {@code =}, nome vazio, URL que não seja http/https) também
     * é ignorada com WARN, em vez de virar uma requisição que falha em todo briefing.
     */
    public Map<String, String> sitesMonitorados() {
        Map<String, String> mapa = new LinkedHashMap<>();
        if (sites == null || sites.isBlank()) {
            return mapa;
        }
        for (String par : sites.split(",")) {
            String entrada = par.trim();
            if (entrada.isEmpty()) {
                continue;
            }
            String[] partes = entrada.split("=", 2);
            if (partes.length < 2) {
                log.warn("SITES_MONITORADOS: entrada sem '=' ignorada: {}", semQuery(entrada));
                continue;
            }
            String nome = partes[0].trim();
            String url = partes[1].trim();
            if (nome.isBlank()) {
                log.warn("SITES_MONITORADOS: entrada sem nome ignorada (url {})", semQuery(url));
                continue;
            }
            if (!urlDeSiteValida(url)) {
                log.warn("SITES_MONITORADOS: url invalida no site '{}', entrada ignorada: {}",
                        nome, semQuery(url));
                continue;
            }
            if (mapa.containsKey(nome)) {
                log.warn("SITES_MONITORADOS: nome '{}' repetido; mantida a primeira url e ignorada "
                        + "a seguinte: {}", nome, semQuery(url));
                continue;
            }
            mapa.put(nome, url);
        }
        return mapa;
    }

    /** Só http e https, com host: o resto nunca daria uma checagem útil. */
    private static boolean urlDeSiteValida(String url) {
        if (url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String esquema = uri.getScheme();
            return uri.getHost() != null && !uri.getHost().isBlank()
                    && ("http".equalsIgnoreCase(esquema) || "https".equalsIgnoreCase(esquema));
        } catch (IllegalArgumentException naoEhUri) {
            return false;
        }
    }

    /**
     * Corta a query string antes de a URL entrar no log: ela é o lugar onde token e chave
     * costumam viajar, e aviso de configuração não é motivo para escrever segredo em disco.
     */
    static String semQuery(String url) {
        int interrogacao = url.indexOf('?');
        return interrogacao < 0 ? url : url.substring(0, interrogacao) + "?...";
    }
}
