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
import dev.jarvis.aprovacao.Aprovacoes;
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
    private final Aprovacoes aprovacoes;
    private final TelegramClient telegram;
    private final JsonMapper json;
    private final long chatId;
    private final Duration espera;

    private Path pastaDePrints;

    @Autowired
    public ControlarPcFerramenta(PcService pc, TelegramClient telegram,
            JsonMapper json, JarvisProperties props, Aprovacoes aprovacoes) {
        this(pc, telegram, json, props, ESPERA_PADRAO, aprovacoes);
    }

    ControlarPcFerramenta(PcService pc, TelegramClient telegram,
            JsonMapper json, JarvisProperties props, Duration espera, Aprovacoes aprovacoes) {
        this.pc = pc;
        this.aprovacoes = aprovacoes;
        this.telegram = telegram;
        this.json = json;
        this.chatId = props.telegram() == null ? 0 : props.telegram().usuarioPermitido();
        this.espera = espera;
    }

    @Override
    public String nome() {
        return "controlar_pc";
    }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.PC; }
    @Override public boolean simples() { return true; }

    @Override
    public String descricao() {
        List<String> apps = pc.appsConhecidos();
        String liberados = apps.isEmpty() ? "nenhum" : String.join(", ", apps);
        if (liberados.length() > 50) {
            int corte = liberados.lastIndexOf(", ", 47);
            liberados = corte > 0 ? liberados.substring(0, corte) + ", …"
                    : "veja o enum de abrir_app";
        }
        return "Controla o PC Windows: abrir_app (liberados: " + liberados + "), abrir_url HTTPS, "
                + "abrir_hud, bloquear_tela, captura_tela, status_pc e volume (subir, baixar, mudo). "
                + "modo_repouso fecha a HUD e volta a ouvir o gatilho; falso_positivo registra engano. "
                + "Desligar ou reiniciar devolve pergunta de aprovação: chame uma vez, repita a pergunta "
                + "e espere a resposta, sem pedir palavra, código nem frase exata. Não repita a ação "
                + "para confirmar. cancelar_desligamento aborta. Apps fora da lista: tarefa_no_pc "
                + "após aprovação. Cards: controlar_tela. Nunca abra shell livre.";
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
                .filter(nome -> !"rodar_rotina".equals(nome))
                .filter(nome -> !List.of("preparar_edicao", "aplicar_edicao",
                        "preparar_desfazer", "aplicar_desfazer").contains(nome))
                .filter(nome -> !"computer_acao".equals(nome))
                .filter(nome -> !ComandoPc.LIXEIRA.contains(nome))
                .filter(nome -> !ComandoPc.INFERENCIA.contains(nome))
                .filter(nome -> !"abrir_app".equals(nome) || !apps.isEmpty())
                .forEach(opcoes::add);

        campos.putObject("argumento").put("type", "string")
                .put("description", "O complemento da ação: o nome do app em abrir_app ("
                        + (apps.isEmpty() ? "nenhum liberado" : String.join(", ", apps))
                        + "), a URL https em abrir_url, ou subir/baixar/mudo em volume. "
                        + "As outras ações não usam este campo.");

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

        if (!ComandoPc.acaoValida(acao)) {
            return ResultadoFerramenta.texto("Não conheço a ação '" + acao + "' no PC. As válidas: "
                    + ComandoPc.ACOES + ".");
        }
        if ("criar_arquivo".equals(acao))
            return ResultadoFerramenta.texto("Use a ferramenta criar_arquivo para gerar arquivos.");
        if ("rodar_rotina".equals(acao))
            return ResultadoFerramenta.texto("Use a ferramenta rodar_rotina para executar cadastros manuais.");
        if ("computer_acao".equals(acao))
            return ResultadoFerramenta.texto("Ação interna do computer use; use tarefa_no_pc.");
        if (ComandoPc.INFERENCIA.contains(acao))
            return ResultadoFerramenta.texto("Ação interna do Jarvis; não está disponível aqui.");
        if (ComandoPc.LIXEIRA.contains(acao))
            return ResultadoFerramenta.texto("Use apagar_arquivo para mover à lixeira e "
                    + "restaurar_arquivo para trazer de volta.");
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
            try {
                String pergunta = "Vou " + acao + " o PC em 60 segundos. Posso?";
                // Tipo próprio, e não o "pc" genérico: é por ele que o diário
                // de ações distingue "desliguei o PC" de "apaguei um arquivo" sem farejar texto.
                var confirmacao = aprovacoes.pedir(Aprovacoes.TIPO_PC_ENERGIA, acao + " o PC",
                        pergunta, Aprovacoes.Risco.CRITICO,
                        progresso.usuarioAutenticado(), progresso.idPedido(), () -> {
                            if (!pc.online()) return "PC offline; ação não iniciada.";
                            ComandoPc ordem = ComandoPc.nova(acao, efetivo, Instant.now());
                            pc.enfileirar(ordem, progresso.canal(), progresso.usuarioAutenticado());
                            pc.pedirAviso(ordem.id());
                            return "Ação confirmada. Operação " + ordem.id() + " enviada ao PC.";
                        });
                return new ResultadoFerramenta(pergunta,
                        List.of(), List.of(), confirmacao, List.of(), List.of());
            } catch (IllegalArgumentException | IllegalStateException falhaConfirmacao) {
                return ResultadoFerramenta.texto(falhaConfirmacao.getMessage());
            }
        }
        // cancelar_desligamento não é perigosa e vai ao PC como qualquer outra: ela aborta o
        // 'shutdown /t 60' que já está correndo lá. Uma aprovação ainda em aberto não é assunto
        // dela — morre com "cancelar" do usuário, que é o caminho autenticado, ou por expirar.
        ComandoPc comando = ComandoPc.nova(acao, efetivo, Instant.now());
        ResultadoPc resultado;
        try {
            resultado = esperar(pc.enfileirar(comando, progresso.canal(), progresso.usuarioAutenticado()));
        } catch (TimeoutException e) {
            pc.pedirAviso(comando.id());
            return ResultadoFerramenta.texto("Comando " + comando.id() + " no PC: "
                    + pc.estadoE2(comando.id()).map(o -> o.estado()).orElse("sem confirmação")
                    + ". Consulte status_operacao_pc pelo ID.");
        } catch (InterruptedException e) {
            pc.pedirAviso(comando.id());
            Thread.currentThread().interrupt();
            return ResultadoFerramenta.texto("Comando " + comando.id() + " no PC: "
                    + pc.estadoE2(comando.id()).map(o -> o.estado()).orElse("sem confirmação")
                    + ". Consulte status_operacao_pc pelo ID.");
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
