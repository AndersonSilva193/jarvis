package dev.jarvis.ferramentas;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.GuardaDeConfirmacao;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.telegram.TelegramClient;
import dev.jarvis.util.Json;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Controla o PC Windows do usuário pelo agente que roda nele.
 *
 * A ferramenta só enfileira: quem executa é o agente, que busca o comando por long polling. Por
 * isso ela espera o resultado — mas <b>30 segundos no máximo</b>, bem menos que a validade de 60 s
 * do comando. A espera acontece dentro do lock do {@code Assistente}, então ela é tempo em que
 * nenhum outro pedido roda; é também por isso que o PC offline é respondido na hora, antes de
 * enfileirar qualquer coisa.
 */
@Component
public class ControlarPcFerramenta implements Ferramenta {

    private static final Logger log = LoggerFactory.getLogger(ControlarPcFerramenta.class);

    /** Teto da espera pelo resultado. O comando continua valendo 60 s no {@code PcService}. */
    private static final Duration ESPERA_PADRAO = Duration.ofSeconds(30);
    private static final int MAX_URL = 2000;
    /** Quanto o print fica em disco depois do turno. O AnexosService já copiou o que importava. */
    private static final Duration VIDA_DO_PRINT = Duration.ofMinutes(5);

    private final PcService pc;
    private final GuardaDeConfirmacao guarda;
    private final TelegramClient telegram;
    private final JsonMapper json;
    private final long chatId;
    private final Duration espera;

    private Path pastaDePrints;

    @Autowired
    public ControlarPcFerramenta(PcService pc, GuardaDeConfirmacao guarda, TelegramClient telegram,
            JsonMapper json, JarvisProperties props) {
        this(pc, guarda, telegram, json, props, ESPERA_PADRAO);
    }

    ControlarPcFerramenta(PcService pc, GuardaDeConfirmacao guarda, TelegramClient telegram,
            JsonMapper json, JarvisProperties props, Duration espera) {
        this.pc = pc;
        this.guarda = guarda;
        this.telegram = telegram;
        this.json = json;
        this.chatId = props.telegram() == null ? 0 : props.telegram().usuarioPermitido();
        this.espera = espera;
    }

    @Override
    public String nome() {
        return "controlar_pc";
    }

    @Override
    public String descricao() {
        List<String> apps = pc.appsConhecidos();
        String liberados = apps.isEmpty() ? "nenhum (o agente não reportou nenhum)" : String.join(", ", apps);
        return """
                Controla o PC Windows do usuário pelo agente que roda nele. Use quando o pedido for \
                sobre "o PC", "o computador", "a máquina" — fechar o aplicativo HUD usa modo_repouso; mexer nos cards da tela \
                usa controlar_tela.

                As ações: abrir_app abre um programa (ponha o nome em 'argumento'; os liberados são \
                %s, e essa lista mora no PC — só ele decide o que aceita). abrir_url abre um \
                endereço no navegador do PC e só aceita https (URL completa em 'argumento'). \
                abrir_hud abre a tela do Jarvis no PC, em tela cheia. bloquear_tela tranca a \
                sessão. volume pede 'argumento' igual a subir, baixar ou mudo (mudo alterna). \
                captura_tela tira um print, que chega no Telegram e na tela. status_pc diz como o \
                PC está: processador, memória, disco e bateria. cancelar_desligamento aborta um \
                desligamento já agendado.

                Duas ações são do despertar por palmas, e só valem com o agente escutando: \
                modo_repouso faz o PC voltar a ouvir o gatilho (use em "fechar HUD", "modo repouso", "pode \
                descansar"), e falso_positivo registra que o último despertar foi engano (use em \
                "foi engano", "não te chamei") — esta devolve quantos alarmes falsos já houve, \
                número que ajuda o usuário a ajustar a sensibilidade.

                desligar e reiniciar são irreversíveis e pedem confirmação em dois passos: chame \
                sem 'codigo_confirmacao' e a ferramenta devolve um código de 4 dígitos. Diga esse \
                código ao usuário e ESPERE ele repetir — não chame a ferramenta de novo por conta \
                própria, em nenhuma hipótese. Quando ele repetir, chame outra vez com a mesma \
                'acao' e 'codigo_confirmacao' igual ao que ele disse. O código vale 2 minutos, \
                serve uma única vez, e um código errado cancela a confirmação (aí é preciso pedir \
                outro). O PC avisa e espera 60 segundos antes de desligar de verdade, então ainda \
                dá para desistir com cancelar_desligamento.

                As respostas são curtas de propósito: confirme em uma frase, sem repetir detalhe.\
                """.formatted(liberados);
    }

    @Override
    public ObjectNode esquemaEntrada() {
        List<String> apps = pc.appsConhecidos();
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        ObjectNode campos = esquema.putObject("properties");

        ObjectNode acao = campos.putObject("acao");
        acao.put("type", "string");
        acao.put("description", "O que fazer no PC.");
        var opcoes = acao.putArray("enum");
        // abrir_app só entra quando o agente reportou algum app: oferecer uma ação que vai
        // recusar tudo só gasta token e faz o modelo tentar à toa.
        ComandoPc.ACOES.stream()
                .filter(nome -> !"criar_arquivo".equals(nome))
                .filter(nome -> !"abrir_app".equals(nome) || !apps.isEmpty())
                .forEach(opcoes::add);

        campos.putObject("argumento").put("type", "string")
                .put("description", "O complemento da ação: o nome do app em abrir_app ("
                        + (apps.isEmpty() ? "nenhum liberado" : String.join(", ", apps))
                        + "), a URL https em abrir_url, ou subir/baixar/mudo em volume. "
                        + "As outras ações não usam este campo.");
        campos.putObject("codigo_confirmacao").put("type", "string")
                .put("description", "Os 4 dígitos que o usuário repetiu, só em desligar e reiniciar.");

        esquema.putArray("required").add("acao");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) throws Exception {
        return executarDetalhado(entrada, new OuvinteProgresso() { }).texto();
    }

    @Override
    public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso) {
        String acao = Json.texto(entrada.path("acao")).trim();
        String argumento = Json.texto(entrada.path("argumento")).trim();
        String codigo = Json.texto(entrada.path("codigo_confirmacao")).trim();

        if (!ComandoPc.acaoValida(acao)) {
            return ResultadoFerramenta.texto("Não conheço a ação '" + acao + "' no PC. As válidas: "
                    + ComandoPc.ACOES + ".");
        }
        if ("criar_arquivo".equals(acao))
            return ResultadoFerramenta.texto("Use a ferramenta criar_arquivo para gerar arquivos.");
        // Antes de qualquer outra coisa: travar o turno 30 s para no fim dizer "o PC está
        // offline" é o pior dos mundos.
        if (!pc.online()) {
            return ResultadoFerramenta.texto(offline());
        }

        Optional<String> recusa = validarArgumento(acao, argumento);
        if (recusa.isPresent()) {
            return ResultadoFerramenta.texto(recusa.get());
        }
        // Nas ações sem argumento, o que o modelo mandou não segue para o PC: ele gosta de
        // preencher campo, e o agente não tem o que fazer com isso.
        String efetivo = ComandoPc.exigeArgumento(acao) ? normalizar(acao, argumento) : "";

        if (ComandoPc.perigosa(acao)) {
            Optional<ResultadoFerramenta> confirmacao = conferirConfirmacao(acao, efetivo, codigo);
            if (confirmacao.isPresent()) {
                return confirmacao.get();
            }
            log.warn("Ação '{}' confirmada no PC por código", acao);
        }
        if ("cancelar_desligamento".equals(acao)) {
            // Cancelou o desligamento, cancelou a pendência de confirmação.
            guarda.cancelar();
        }

        ResultadoPc resultado;
        try {
            resultado = esperar(pc.enfileirar(ComandoPc.nova(acao, efetivo, Instant.now())));
        } catch (TimeoutException e) {
            return ResultadoFerramenta.texto("Comando enviado ao PC, sem confirmação ainda.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResultadoFerramenta.texto("Comando enviado ao PC, sem confirmação ainda.");
        }

        if ("captura_tela".equals(acao)) {
            return doPrint(resultado);
        }
        if (!resultado.ok()) {
            return ResultadoFerramenta.texto("O PC recusou: " + resultado.mensagem());
        }
        return ResultadoFerramenta.texto(resultado.mensagem().isEmpty()
                ? "Feito no PC."
                : "Feito no PC: " + resultado.mensagem());
    }

    private ResultadoPc esperar(CompletableFuture<ResultadoPc> futuro)
            throws TimeoutException, InterruptedException {
        try {
            return futuro.get(espera.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            // O futuro só é completado com valor, nunca com exceção — mas não dá para prometer
            // isso ao compilador.
            throw new IllegalStateException("resultado do PC falhou", e);
        }
    }

    /** Vazio quando o argumento serve; o texto da recusa quando não. */
    private Optional<String> validarArgumento(String acao, String argumento) {
        if (!ComandoPc.exigeArgumento(acao)) {
            return Optional.empty();
        }
        if (argumento.isEmpty()) {
            return Optional.of("A ação '" + acao + "' precisa de 'argumento'.");
        }
        return switch (acao) {
            case "abrir_app" -> validarApp(argumento);
            case "abrir_url" -> validarUrl(argumento);
            case "volume" -> ComandoPc.VOLUMES.contains(argumento.toLowerCase(Locale.ROOT))
                    ? Optional.empty()
                    : Optional.of("Em volume, 'argumento' tem de ser subir, baixar ou mudo.");
            default -> Optional.empty();
        };
    }

    private Optional<String> validarApp(String argumento) {
        List<String> apps = pc.appsConhecidos();
        if (apps.isEmpty()) {
            return Optional.of("O agente não reportou nenhum app liberado. Peça para adicionar em "
                    + "config.properties no PC.");
        }
        if (apps.contains(argumento.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of("'" + argumento + "' não está na lista do agente. Liberados: "
                + String.join(", ", apps) + ". (A lista mora no config.properties do PC.)");
    }

    /**
     * Só https, com host e sem credencial embutida.
     *
     * <p>Não passa pelo {@code GuardaSsrf} de propósito: ele existe para proteger a VPS de buscar
     * endereço interno, e aqui a VPS não busca nada — a página abre no navegador <b>do usuário, na
     * máquina dele</b>, onde {@code 192.168.0.1} é o roteador da casa e a lista de faixas privadas
     * da VPS não quer dizer nada. O risco real neste ponto é esquema que executa código
     * ({@code javascript:}, {@code file:}, handlers do Windows), e o controle certo para isso é a
     * lista branca de esquema. O agente revalida antes de abrir, sem confiar nesta checagem.
     */
    private Optional<String> validarUrl(String argumento) {
        String recusa = "Só abro endereço https no PC. Peça o link completo começando com https://.";
        if (argumento.length() > MAX_URL || argumento.matches(".*[\\s\\p{Cntrl}].*")) {
            return Optional.of(recusa);
        }
        try {
            URI uri = URI.create(argumento);
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
                return Optional.of(recusa);
            }
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of(recusa);
        }
    }

    private static String normalizar(String acao, String argumento) {
        return switch (acao) {
            case "abrir_app", "volume" -> argumento.toLowerCase(Locale.ROOT);
            case "abrir_url" -> URI.create(argumento).toASCIIString();
            default -> argumento;
        };
    }

    /**
     * O passo de confirmação das ações perigosas.
     *
     * @return vazio quando está autorizado a executar; o resultado a devolver ao modelo quando não
     */
    private Optional<ResultadoFerramenta> conferirConfirmacao(String acao, String argumento, String codigo) {
        if (codigo.isEmpty()) {
            String novo = guarda.pedir(acao, argumento);
            return Optional.of(ResultadoFerramenta.texto(
                    "Confirmação necessária para " + acao + " o PC. Diga ao usuário o código " + novo
                    + " e ESPERE ele repetir; só então chame controlar_pc de novo com "
                    + "codigo_confirmacao=" + novo + ". Vale 2 minutos. Não confirme por conta própria."));
        }
        return switch (guarda.conferir(acao, argumento, codigo)) {
            case CONFIRMADA -> Optional.empty();
            case INCORRETA -> Optional.of(ResultadoFerramenta.texto("Código incorreto. Cancelei a "
                    + "confirmação por segurança; se ele insistir, peça um código novo."));
            case EXPIRADA -> Optional.of(ResultadoFerramenta.texto("O código expirou (vale 2 minutos). "
                    + "Código novo: " + guarda.pedir(acao, argumento)
                    + " — diga e espere ele repetir."));
            case INEXISTENTE -> Optional.of(ResultadoFerramenta.texto("Não há confirmação em aberto. "
                    + "Chame sem codigo_confirmacao para pedir um código."));
        };
    }

    /**
     * O print vai aos dois destinos: Telegram (um lugar só para procurar captura, qualquer que
     * seja o canal do pedido) e anexo, que é como ele aparece na HUD.
     */
    private ResultadoFerramenta doPrint(ResultadoPc resultado) {
        if (!resultado.temImagem()) {
            return ResultadoFerramenta.texto(resultado.ok()
                    ? "O PC respondeu sem imagem: " + resultado.mensagem()
                    : "O PC recusou: " + resultado.mensagem());
        }
        Path arquivo;
        try {
            arquivo = gravarPrint(resultado);
        } catch (IOException e) {
            log.warn("Não consegui gravar o print: {}", e.getMessage());
            arquivo = null;
        }
        boolean noTelegram = true;
        try {
            telegram.enviarFoto(chatId, resultado.imagem(), nomeDoPrint(resultado), "Print do PC");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            noTelegram = false;
        } catch (Exception e) {
            log.warn("sendPhoto do print falhou: {}", e.getMessage());
            noTelegram = false;
        }

        if (arquivo == null) {
            return ResultadoFerramenta.texto(noTelegram
                    ? "Print do PC enviado no Telegram."
                    : "Tirei o print, mas não consegui entregá-lo.");
        }
        // Construtor de 6 argumentos: não há fábrica para artefato. O Assistente registra o anexo
        // no fim do turno, pelo AnexosService — por isso a ferramenta não pode apagar o arquivo.
        return new ResultadoFerramenta(
                noTelegram ? "Print do PC na tela e enviado no Telegram."
                        : "Print do PC na tela. Não consegui enviar no Telegram.",
                List.of(new ResultadoFerramenta.Artefato(arquivo, "tela-do-pc"
                        + extensao(resultado), resultado.mime())),
                List.of(), null, List.of(), List.of());
    }

    private static String nomeDoPrint(ResultadoPc resultado) {
        return "tela-do-pc" + extensao(resultado);
    }

    private static String extensao(ResultadoPc resultado) {
        return "image/jpeg".equals(resultado.mime()) ? ".jpg" : ".png";
    }

    private synchronized Path gravarPrint(ResultadoPc resultado) throws IOException {
        if (pastaDePrints == null) {
            pastaDePrints = Files.createTempDirectory("jarvis-pc-");
        }
        Path arquivo = pastaDePrints.resolve(resultado.comandoId() + extensao(resultado));
        Files.write(arquivo, resultado.imagem());
        return arquivo;
    }

    /** Varre os prints já copiados pelo AnexosService. Sem isto eles ficariam até o restart. */
    @Scheduled(fixedRate = 60_000)
    synchronized void limparPrints() {
        if (pastaDePrints == null) {
            return;
        }
        Instant limite = Instant.now().minus(VIDA_DO_PRINT);
        try (var arquivos = Files.list(pastaDePrints)) {
            for (Path arquivo : arquivos.toList()) {
                if (Files.getLastModifiedTime(arquivo).toInstant().isBefore(limite)) {
                    Files.deleteIfExists(arquivo);
                }
            }
        } catch (IOException e) {
            log.debug("Não consegui limpar os prints: {}", e.getMessage());
        }
    }

    @PreDestroy
    synchronized void fechar() {
        if (pastaDePrints == null) {
            return;
        }
        try (var arquivos = Files.list(pastaDePrints)) {
            for (Path arquivo : arquivos.toList()) {
                Files.deleteIfExists(arquivo);
            }
            Files.deleteIfExists(pastaDePrints);
        } catch (IOException e) {
            log.debug("Não consegui apagar a pasta de prints: {}", e.getMessage());
        }
    }

    private String offline() {
        Optional<Duration> desde = pc.desdeOUltimoContato();
        if (desde.isEmpty()) {
            return "O PC nunca se conectou: o agente não está rodando. Não enfileirei nada.";
        }
        return "O PC está offline (sem contato há " + comoFalar(desde.get())
                + "). Não enfileirei nada; avise e ofereça tentar depois.";
    }

    /** Duração em texto curto, que também vai ser lido em voz alta na HUD. */
    static String comoFalar(Duration duracao) {
        long segundos = Math.max(duracao.toSeconds(), 0);
        if (segundos < 90) {
            return segundos + " s";
        }
        if (segundos < 5400) {
            return duracao.toMinutes() + " min";
        }
        return duracao.toHours() + " h";
    }
}
