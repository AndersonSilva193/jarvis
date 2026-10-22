package dev.jarvis.memoria;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Armazém Markdown da memória. Nenhuma entrada do modelo é interpretada como caminho. */
@Service
public class MemoriaService {
    private static final Logger log = LoggerFactory.getLogger(MemoriaService.class);
    public static final int LIMITE_CONTEUDO = 2_000;
    public static final int LIMITE_REGISTROS = 500;
    public static final int LIMITE_CONTEXTO = 4_000;
    public static final int LIMITE_BUSCA = 5;
    /** Teto do resumo que vai para o system, aviso de corte incluído. */
    public static final int LIMITE_RESUMO = 1_500;
    /** Teto da lista de assuntos entregue ao anotador: ela é prompt, e 500 linhas custariam caro. */
    public static final int LIMITE_ASSUNTOS = 120;
    /**
     * O anotador só grava o que o usuário disse, então o tipo não é escolha dele nem do modelo:
     * {@code observacao_verificada} pede evidência de ferramenta local e {@code hipotese} seria
     * palpite gravado como fato.
     */
    private static final String TIPO_DO_ANOTADOR = "declaracao_usuario";
    private static final String AVISO_RESUMO = "(há mais registros; use buscar_memoria)\n";
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern PROJETO = Pattern.compile("[a-z0-9][a-z0-9-]{0,49}");
    private static final Pattern BLOCO = Pattern.compile("(?s)<!-- jarvis:inicio (\\{[^\\n]*}) -->\\n(.*?)\\n<!-- jarvis:fim -->\\n?");
    private static final Pattern SEGREDO = Pattern.compile("(?i)(senha|password|api[_ -]?key|token|chave[_ -]?api|secret|-----BEGIN [A-Z ]*PRIVATE KEY|sk-[a-z0-9_-]{12,}|eyJ[A-Za-z0-9_-]{20,}\\.)");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm z", Locale.of("pt", "BR"));

    public record Registro(String id, String categoria, String projeto, String assunto, String conteudo,
                           String tipo, String origem, Instant criadoEm, Instant atualizadoEm,
                           long revisao, String ultimoPedido) { }
    public record Achado(String id, String assunto, String projeto, String tipo, String trecho, long revisao) { }
    /** O que o anotador gravou, para o aviso da resposta seguinte e para o desfazer. */
    public record Anotacao(String id, String assunto, String conteudo, boolean atualizado) { }

    private final Path raiz;
    private final Path exclusoes;
    private final JsonMapper json;
    private final Clock relogio;
    private final ZoneId zona;

    @Autowired
    public MemoriaService(JarvisProperties props, JsonMapper json) {
        this(props.memoria() == null ? Path.of("/data/jarvis/memoria") : Path.of(props.memoria().pasta()),
                props.memoria() == null ? Path.of("/data/jarvis/memoria-exclusoes")
                        : Path.of(props.memoria().pastaExclusoes()), json, Clock.systemUTC(), props.zona());
    }

    public MemoriaService(Path raiz, Path exclusoes, JsonMapper json, Clock relogio, ZoneId zona) {
        this.raiz = raiz.toAbsolutePath().normalize();
        this.exclusoes = exclusoes.toAbsolutePath().normalize();
        if (this.raiz.equals(this.exclusoes) || this.raiz.startsWith(this.exclusoes)
                || this.exclusoes.startsWith(this.raiz)) throw new IllegalArgumentException("Pastas de memória sobrepostas");
        this.json = json;
        this.relogio = relogio;
        this.zona = zona;
    }

    public String dataLocal(Instant instante) { return DATA.format(instante.atZone(zona)); }

    /** Após restaurar conteúdo antigo, os marcadores atuais continuam impedindo sua leitura. */
    @PostConstruct
    public void reaplicarExclusoes() throws IOException {
        if (!Files.exists(raiz, LinkOption.NOFOLLOW_LINKS)) return;
        conferirDiretorio(raiz);
        Path usuarios = raiz.resolve("usuarios");
        if (!Files.exists(usuarios, LinkOption.NOFOLLOW_LINKS)) return;
        conferirDiretorio(usuarios);
        try (DirectoryStream<Path> pastas = Files.newDirectoryStream(usuarios)) {
            for (Path pasta : pastas) {
                conferirDiretorio(pasta);
                long usuario;
                try { usuario = Long.parseLong(pasta.getFileName().toString()); }
                catch (NumberFormatException e) { throw new IOException("Pasta de usuário inválida"); }
                comBloqueio(usuario, () -> {
                    for (Path arquivo : arquivosAtivos(usuario)) {
                        List<Registro> registros = lerArquivo(arquivo);
                        List<Registro> ativos = new ArrayList<>();
                        for (Registro r : registros) {
                            if (marcador(usuario, r.id())) apagarHistorico(usuario, r.id());
                            else ativos.add(r);
                        }
                        if (ativos.size() != registros.size()) gravar(arquivo, ativos);
                    }
                    return null;
                });
            }
        }
    }

    public List<Achado> buscar(long usuario, String consulta, String projeto) throws IOException {
        validarUsuario(usuario);
        String termo = normalizar(validarTexto(consulta, 120, "consulta"));
        if (termo.isEmpty()) throw new IllegalArgumentException("Consulta sem palavras-chave");
        String escopo = projeto == null || projeto.isBlank() ? "" : validarProjeto(projeto);
        List<Achado> achados = new ArrayList<>();
        for (Registro r : todos(usuario)) {
            if (!escopo.isEmpty() && !escopo.equals(r.projeto())) continue;
            String alvo = normalizar(r.assunto() + " " + r.projeto() + " " + r.conteudo());
            String[] palavras = termo.split("\\s+");
            boolean combina = true;
            for (String palavra : palavras) if (!alvo.contains(palavra)) { combina = false; break; }
            if (combina) achados.add(new Achado(r.id(), r.assunto(), r.projeto(), r.tipo(),
                    r.conteudo().substring(0, Math.min(240, r.conteudo().length())), r.revisao()));
        }
        achados.sort(Comparator.comparing(Achado::assunto));
        if (escopo.isEmpty() && achados.stream().map(Achado::projeto).distinct().count() > 1)
            return achados.stream().limit(LIMITE_BUSCA)
                    .map(a -> new Achado(a.id(), a.assunto(), a.projeto(), a.tipo(), "", a.revisao())).toList();
        return achados.stream().limit(LIMITE_BUSCA).toList();
    }

    /**
     * O que o Jarvis sabe sobre o usuário, compacto, para o bloco <b>dinâmico</b> do system.
     *
     * <p>Entram todos os registros de {@code perfil} e de {@code preferencias} e os de
     * {@code projeto} cujo identificador ou assunto aparece na mensagem do pedido (comparação sem
     * acento e sem maiúsculas, a mesma de {@link #buscar}). {@code episodio} fica de fora de
     * propósito: é justamente o que sobra para {@code buscar_memoria}.
     *
     * <p>Teto de {@link #LIMITE_RESUMO} caracteres, aviso de corte incluído. Quem cai fora é sempre
     * o registro mais antigo, e o aviso vai para o modelo porque um resumo truncado em silêncio o
     * faria afirmar que não existe o que foi cortado.
     *
     * <p>Não propaga erro: memória ilegível devolve resumo vazio. Isto roda na montagem do system de
     * todo pedido autenticado — um arquivo torto não pode derrubar o turno.
     */
    public String resumoParaContexto(long usuario, String mensagem) {
        if (usuario <= 0) return "";
        List<Registro> registros;
        try {
            registros = todos(usuario);
        } catch (IOException | RuntimeException e) {
            log.warn("Memória indisponível para o contexto do usuário {}: {}", usuario, e.getMessage());
            return "";
        }
        String alvo = normalizar(mensagem == null ? "" : mensagem);
        List<String> linhas = registros.stream()
                .filter(r -> switch (r.categoria()) {
                    case "perfil", "preferencias" -> true;
                    case "projeto" -> citadoNaMensagem(alvo, r);
                    default -> false;
                })
                // Mais novo primeiro: assim o corte por tamanho tira sempre o mais antigo.
                .sorted(Comparator.comparing(Registro::criadoEm).reversed())
                .map(r -> "- " + r.assunto() + ": " + umaLinha(r.conteudo()) + "\n")
                .toList();
        int total = linhas.stream().mapToInt(String::length).sum();
        int teto = total <= LIMITE_RESUMO ? LIMITE_RESUMO : LIMITE_RESUMO - AVISO_RESUMO.length();
        StringBuilder resumo = new StringBuilder();
        boolean cortou = false;
        for (String linha : linhas) {
            if (resumo.length() + linha.length() > teto) { cortou = true; break; }
            resumo.append(linha);
        }
        if (cortou) resumo.append(AVISO_RESUMO);
        return resumo.toString();
    }

    /** Projeto citado: o identificador ou o assunto do registro aparece na mensagem do pedido. */
    private static boolean citadoNaMensagem(String alvo, Registro r) {
        if (alvo.isEmpty()) return false;
        String projeto = normalizar(r.projeto());
        String assunto = normalizar(r.assunto());
        return (!projeto.isEmpty() && alvo.contains(projeto))
                || (!assunto.isEmpty() && alvo.contains(assunto));
    }

    /** Uma linha e curto: o resumo é prompt, e um registro gordo comeria o espaço de todos os outros. */
    private static String umaLinha(String conteudo) {
        String plano = conteudo.replaceAll("\\s+", " ").trim();
        return plano.length() <= 240 ? plano : plano.substring(0, 240) + "…";
    }

    public Registro ler(long usuario, String id) throws IOException {
        validarUsuario(usuario); validarId(id);
        for (Registro r : todos(usuario)) if (r.id().equals(id)) return r;
        return null;
    }

    public Registro registrar(long usuario, String categoria, String projeto, String assunto,
                              String conteudo, String tipo, String origem, String pedido) throws IOException {
        validarUsuario(usuario);
        String cat = validarCategoria(categoria);
        String proj = "projeto".equals(cat) ? validarProjeto(projeto) : "";
        String nome = validarTexto(assunto, 120, "assunto");
        String corpo = validarConteudo(conteudo);
        String classe = validarTipo(tipo);
        String fonte = validarTexto(origem, 80, "origem");
        String idPedido = validarTexto(pedido, 120, "pedido");
        return comBloqueio(usuario,
                () -> registrarTravado(usuario, cat, proj, nome, corpo, classe, fonte, idPedido));
    }

    /**
     * O corpo de {@link #registrar}, já validado e <b>já dentro</b> do bloqueio. Existe separado
     * porque o {@link #anotar} precisa decidir entre criar e atualizar sem tomar o
     * {@code FileLock} duas vezes — dois {@code FileLock} do mesmo arquivo no mesmo processo
     * estouram {@code OverlappingFileLockException}.
     */
    private Registro registrarTravado(long usuario, String cat, String proj, String nome,
            String corpo, String classe, String fonte, String idPedido) throws IOException {
        List<Registro> existentes = todos(usuario);
        for (Registro r : existentes) {
            if (mesmoAssunto(r, cat, proj, nome)) {
                if (r.conteudo().equals(corpo) && r.tipo().equals(classe)) return r;
                throw new Conflito("Assunto já registrado em " + r.id() + " revisão " + r.revisao()
                        + ". Peça uma atualização ou esclareça a contradição.");
            }
        }
        if (existentes.size() >= LIMITE_REGISTROS) throw new IllegalArgumentException("Limite de registros atingido");
        Instant agora = relogio.instant();
        Registro novo = new Registro(UUID.randomUUID().toString(), cat, proj, nome, corpo, classe,
                fonte, agora, agora, 1, idPedido);
        Path arquivo = arquivo(usuario, novo);
        List<Registro> lista = lerArquivo(arquivo);
        if (lista.size() >= 100) throw new IllegalArgumentException("Limite de registros neste arquivo atingido");
        lista.add(novo);
        gravar(arquivo, lista);
        return novo;
    }

    private static boolean mesmoAssunto(Registro r, String cat, String proj, String nome) {
        return r.categoria().equals(cat) && r.projeto().equals(proj)
                && normalizar(r.assunto()).equals(normalizar(nome));
    }

    public Registro atualizar(long usuario, String id, long revisao, String conteudo, String tipo,
                              String origem, String pedido) throws IOException {
        validarUsuario(usuario); validarId(id);
        String corpo = validarConteudo(conteudo);
        String classe = validarTipo(tipo);
        String fonte = validarTexto(origem, 80, "origem");
        String idPedido = validarTexto(pedido, 120, "pedido");
        return comBloqueio(usuario,
                () -> atualizarTravado(usuario, id, revisao, corpo, classe, fonte, idPedido));
    }

    /** O corpo de {@link #atualizar}, já validado e dentro do bloqueio. Ver {@link #registrarTravado}. */
    private Registro atualizarTravado(long usuario, String id, long revisao, String corpo,
            String classe, String fonte, String idPedido) throws IOException {
        Registro atual = ler(usuario, id);
        if (atual == null) throw new Conflito("Registro não encontrado");
        if (atual.ultimoPedido().equals(idPedido)) return atual;
        if (atual.revisao() != revisao) throw new Conflito("Revisão mudou; leia o registro novamente");
        if (atual.conteudo().equals(corpo) && atual.tipo().equals(classe)) return atual;
        Registro novo = new Registro(id, atual.categoria(), atual.projeto(), atual.assunto(), corpo,
                classe, fonte, atual.criadoEm(), relogio.instant(), revisao + 1, idPedido);
        Path arquivo = arquivo(usuario, atual);
        Path historico = pastaUsuario(usuario).resolve(".historico").resolve(id).resolve(revisao + ".md");
        gravar(historico, List.of(atual));
        List<Registro> lista = lerArquivo(arquivo);
        lista.replaceAll(r -> r.id().equals(id) ? novo : r);
        gravar(arquivo, lista);
        return novo;
    }

    /**
     * Gravação do anotador: cria o registro ou, no mesmo assunto, atualiza com histórico. Devolve
     * {@code null} quando o conteúdo já era aquele — nada mudou, nada a avisar.
     *
     * Não existe para burlar trava nenhuma: o filtro de segredo, os limites e os validadores são
     * os mesmos de {@link #registrar}. O que falta lá é o <i>upsert</i>, porque {@code registrar}
     * recusa assunto repetido com {@link Conflito}, e o anotador reencontra o mesmo assunto toda
     * vez que o dono volta a falar dele.
     *
     * Quem decide entre criar e atualizar é este método, casando o assunto no disco — o chamador
     * não aponta ID. É o que impede o modelo de mirar uma escrita num registro que ele escolheu.
     *
     * O {@code tipo} é fixo em {@code declaracao_usuario}, e não é escolha do chamador.
     */
    public Anotacao anotar(long usuario, String categoria, String projeto, String assunto,
                           String conteudo, String origem, String pedido) throws IOException {
        validarUsuario(usuario);
        String cat = validarCategoria(categoria);
        String proj = "projeto".equals(cat) ? validarProjeto(projeto) : "";
        String nome = validarTexto(assunto, 120, "assunto");
        String corpo = validarConteudo(conteudo);
        String fonte = validarTexto(origem, 80, "origem");
        String idPedido = validarTexto(pedido, 120, "pedido");
        return comBloqueio(usuario, () -> {
            for (Registro r : todos(usuario)) {
                if (!mesmoAssunto(r, cat, proj, nome)) continue;
                if (r.conteudo().equals(corpo) && r.tipo().equals(TIPO_DO_ANOTADOR)) return null;
                // A revisão vem do disco, não do modelo: aqui não há leitura prévia para conferir.
                Registro novo = atualizarTravado(usuario, r.id(), r.revisao(), corpo,
                        TIPO_DO_ANOTADOR, fonte, idPedido);
                return new Anotacao(novo.id(), novo.assunto(), novo.conteudo(), true);
            }
            Registro novo = registrarTravado(usuario, cat, proj, nome, corpo, TIPO_DO_ANOTADOR,
                    fonte, idPedido);
            return new Anotacao(novo.id(), novo.assunto(), novo.conteudo(), false);
        });
    }

    /**
     * Os assuntos já gravados, para o anotador escolher entre criar e atualizar. Só a ficha: o
     * conteúdo não vai ao prompt, que é o que torna a lista barata o bastante para ir inteira.
     */
    public List<Achado> assuntos(long usuario) throws IOException {
        validarUsuario(usuario);
        List<Achado> fichas = new ArrayList<>();
        for (Registro r : todos(usuario)) {
            fichas.add(new Achado(r.id(), r.assunto(), r.projeto(), r.categoria(), "", r.revisao()));
        }
        fichas.sort(Comparator.comparing(Achado::tipo).thenComparing(Achado::assunto));
        return fichas.size() <= LIMITE_ASSUNTOS ? fichas : fichas.subList(0, LIMITE_ASSUNTOS);
    }

    public boolean esquecer(long usuario, String id, long revisao) throws IOException {
        validarUsuario(usuario); validarId(id);
        return comBloqueio(usuario, () -> {
            Registro atual = ler(usuario, id);
            if (atual == null) return marcador(usuario, id);
            if (atual.revisao() != revisao) throw new Conflito("Revisão mudou; confirme novamente");
            Path marca = pastaExclusoes(usuario).resolve(id + ".md");
            gravarTexto(marca, "<!-- exclusao " + id + " " + relogio.instant() + " -->\n");
            Path arquivo = arquivo(usuario, atual);
            List<Registro> lista = lerArquivo(arquivo);
            lista.removeIf(r -> r.id().equals(id));
            gravar(arquivo, lista);
            apagarHistorico(usuario, id);
            return true;
        });
    }

    private void apagarHistorico(long usuario, String id) throws IOException {
        Path historico = pastaUsuario(usuario).resolve(".historico").resolve(id);
        if (!Files.exists(historico, LinkOption.NOFOLLOW_LINKS)) return;
        conferirDiretorio(historico);
        try (DirectoryStream<Path> arquivos = Files.newDirectoryStream(historico)) {
            for (Path anterior : arquivos) { conferirArquivo(anterior); Files.delete(anterior); }
        }
        Files.delete(historico);
    }

    private List<Path> arquivosAtivos(long usuario) throws IOException {
        Path base = pastaUsuario(usuario);
        List<Path> arquivos = new ArrayList<>();
        for (String nome : List.of("perfil.md", "preferencias.md")) {
            Path arquivo = base.resolve(nome);
            if (Files.exists(arquivo, LinkOption.NOFOLLOW_LINKS)) arquivos.add(arquivo);
        }
        for (String nome : List.of("projetos", "episodios")) {
            Path sub = base.resolve(nome);
            if (!Files.exists(sub, LinkOption.NOFOLLOW_LINKS)) continue;
            conferirDiretorio(sub);
            try (DirectoryStream<Path> itens = Files.newDirectoryStream(sub, "*.md")) {
                for (Path arquivo : itens) arquivos.add(arquivo);
            }
        }
        return arquivos;
    }

    private List<Registro> todos(long usuario) throws IOException {
        Path base = pastaUsuario(usuario);
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) return List.of();
        conferirDiretorio(raiz);
        conferirDiretorio(raiz.resolve("usuarios"));
        conferirDiretorio(base);
        List<Registro> lista = new ArrayList<>();
        lista.addAll(lerArquivo(base.resolve("perfil.md")));
        lista.addAll(lerArquivo(base.resolve("preferencias.md")));
        for (String pasta : List.of("projetos", "episodios")) {
            Path sub = base.resolve(pasta);
            if (!Files.exists(sub, LinkOption.NOFOLLOW_LINKS)) continue;
            conferirDiretorio(sub);
            try (DirectoryStream<Path> arquivos = Files.newDirectoryStream(sub, "*.md")) {
                for (Path arquivo : arquivos) {
                    lista.addAll(lerArquivo(arquivo));
                    if (lista.size() > LIMITE_REGISTROS) throw new IOException("Memória excede o limite de registros");
                }
            }
        }
        List<Registro> ativos = new ArrayList<>();
        for (Registro r : lista) if (!marcador(usuario, r.id())) ativos.add(r);
        return ativos;
    }

    private List<Registro> lerArquivo(Path arquivo) throws IOException {
        if (!Files.exists(arquivo, LinkOption.NOFOLLOW_LINKS)) return new ArrayList<>();
        conferirComponentes(arquivo);
        conferirArquivo(arquivo);
        if (Files.size(arquivo) > 250_000) throw new IOException("Arquivo de memória acima do limite");
        String texto = Files.readString(arquivo, StandardCharsets.UTF_8);
        if (!texto.startsWith("# Memória Jarvis\n\n")) throw new IOException("Arquivo de memória inválido");
        List<Registro> lista = new ArrayList<>();
        Matcher m = BLOCO.matcher(texto);
        int fim = "# Memória Jarvis\n\n".length();
        while (m.find()) {
            if (!texto.substring(fim, m.start()).isBlank()) throw new IOException("Arquivo de memória inválido");
            JsonNode n = json.readTree(m.group(1));
            Registro r = new Registro(Json.texto(n.path("id")), Json.texto(n.path("categoria")),
                    Json.texto(n.path("projeto")), Json.texto(n.path("assunto")), m.group(2),
                    Json.texto(n.path("tipo")), Json.texto(n.path("origem")),
                    Instant.parse(Json.texto(n.path("criadoEm"))),
                    Instant.parse(Json.texto(n.path("atualizadoEm"))),
                    Json.numero(n.path("revisao")), Json.texto(n.path("ultimoPedido")));
            validarId(r.id());
            validarCategoria(r.categoria());
            if ("projeto".equals(r.categoria())) validarProjeto(r.projeto());
            validarTipo(r.tipo());
            lista.add(r);
            fim = m.end();
        }
        if (!texto.substring(fim).isBlank()) throw new IOException("Arquivo de memória inválido");
        return lista;
    }

    private void gravar(Path destino, List<Registro> lista) throws IOException {
        StringBuilder sb = new StringBuilder("# Memória Jarvis\n\n");
        for (Registro r : lista) {
            ObjectNode n = json.createObjectNode().put("id", r.id()).put("categoria", r.categoria())
                    .put("projeto", r.projeto()).put("assunto", r.assunto()).put("tipo", r.tipo())
                    .put("origem", r.origem()).put("criadoEm", r.criadoEm().toString())
                    .put("atualizadoEm", r.atualizadoEm().toString()).put("revisao", r.revisao())
                    .put("ultimoPedido", r.ultimoPedido());
            sb.append("<!-- jarvis:inicio ").append(json.writeValueAsString(n)).append(" -->\n")
                    .append(r.conteudo()).append("\n<!-- jarvis:fim -->\n\n");
        }
        gravarTexto(destino, sb.toString());
    }

    private void gravarTexto(Path destino, String conteudo) throws IOException {
        Path pai = destino.getParent();
        criarDiretorio(pai);
        conferirComponentes(destino);
        if (Files.exists(destino, LinkOption.NOFOLLOW_LINKS)) conferirArquivo(destino);
        Path temporario = Files.createTempFile(pai, ".jarvis-", ".tmp");
        try {
            byte[] bytes = conteudo.getBytes(StandardCharsets.UTF_8);
            try (FileChannel canal = FileChannel.open(temporario, StandardOpenOption.WRITE)) {
                ByteBuffer dados = ByteBuffer.wrap(bytes);
                while (dados.hasRemaining()) canal.write(dados);
                canal.force(true);
            }
            Files.move(temporario, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporario); }
    }

    private Path arquivo(long usuario, Registro r) {
        Path base = pastaUsuario(usuario);
        return switch (r.categoria()) {
            case "perfil" -> base.resolve("perfil.md");
            case "preferencias" -> base.resolve("preferencias.md");
            case "projeto" -> base.resolve("projetos").resolve(r.projeto() + ".md");
            default -> base.resolve("episodios").resolve(LocalDate.ofInstant(r.criadoEm(), zona) + ".md");
        };
    }

    private boolean marcador(long usuario, String id) throws IOException {
        Path arquivo = pastaExclusoes(usuario).resolve(id + ".md");
        if (!Files.exists(arquivo, LinkOption.NOFOLLOW_LINKS)) return false;
        conferirDiretorio(exclusoes);
        conferirDiretorio(exclusoes.resolve("usuarios"));
        conferirDiretorio(pastaExclusoes(usuario));
        conferirArquivo(arquivo);
        return true;
    }

    private Path pastaUsuario(long usuario) { return raiz.resolve("usuarios").resolve(Long.toString(usuario)); }
    private Path pastaExclusoes(long usuario) { return exclusoes.resolve("usuarios").resolve(Long.toString(usuario)); }

    private interface Operacao<T> { T executar() throws IOException; }
    private synchronized <T> T comBloqueio(long usuario, Operacao<T> operacao) throws IOException {
        criarDiretorio(pastaUsuario(usuario));
        Path trava = pastaUsuario(usuario).resolve(".bloqueio");
        if (Files.exists(trava, LinkOption.NOFOLLOW_LINKS)) conferirArquivo(trava);
        try (FileChannel canal = FileChannel.open(trava, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignorado = canal.lock()) { return operacao.executar(); }
    }

    private void criarDiretorio(Path pasta) throws IOException {
        if (Files.exists(pasta, LinkOption.NOFOLLOW_LINKS)) {
            conferirComponentes(pasta);
            conferirDiretorio(pasta); return;
        }
        Path pai = pasta.getParent();
        if (pai != null) criarDiretorio(pai);
        Files.createDirectory(pasta);
        conferirDiretorio(pasta);
    }
    private void conferirComponentes(Path caminho) throws IOException {
        Path atual = caminho;
        while (atual != null && (atual.startsWith(raiz) || atual.startsWith(exclusoes))) {
            if (Files.isSymbolicLink(atual)) throw new IOException("Link simbólico em caminho de memória");
            atual = atual.getParent();
        }
    }
    private static void conferirDiretorio(Path pasta) throws IOException {
        if (!Files.isDirectory(pasta, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Diretório de memória inválido");
    }
    private static void conferirArquivo(Path arquivo) throws IOException {
        if (!Files.isRegularFile(arquivo, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Arquivo de memória inválido");
    }
    private static void validarUsuario(long usuario) {
        if (usuario <= 0) throw new IllegalArgumentException("Usuário não autenticado");
    }
    private static void validarId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("ID de memória inválido");
    }
    private static String validarProjeto(String projeto) {
        if (projeto == null || !PROJETO.matcher(projeto).matches())
            throw new IllegalArgumentException("Projeto deve ser um id minúsculo de até 50 caracteres");
        return projeto;
    }
    private static String validarCategoria(String categoria) {
        if (!List.of("perfil", "preferencias", "projeto", "episodio").contains(categoria))
            throw new IllegalArgumentException("Categoria inválida");
        return categoria;
    }
    private static String validarTipo(String tipo) {
        if (!List.of("declaracao_usuario", "observacao_verificada", "hipotese").contains(tipo))
            throw new IllegalArgumentException("Tipo inválido");
        return tipo;
    }
    private static String validarTexto(String valor, int maximo, String campo) {
        if (valor == null || valor.isBlank() || valor.length() > maximo || valor.indexOf('\n') >= 0)
            throw new IllegalArgumentException(campo + " inválido");
        if ("assunto".equals(campo) && SEGREDO.matcher(valor).find())
            throw new IllegalArgumentException("Assunto possivelmente secreto");
        return valor.trim();
    }
    private static String validarConteudo(String conteudo) {
        if (conteudo == null || conteudo.isBlank() || conteudo.length() > LIMITE_CONTEUDO
                || conteudo.contains("<!-- jarvis:") || SEGREDO.matcher(conteudo).find())
            throw new IllegalArgumentException("Conteúdo inválido, excessivo ou possivelmente secreto");
        return conteudo.trim();
    }
    private static String normalizar(String valor) {
        return Normalizer.normalize(valor.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^a-z0-9]+", " ").trim();
    }
    public static class Conflito extends IllegalStateException {
        public Conflito(String mensagem) { super(mensagem); }
    }
}
