package dev.jarvis.memoria;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.ArquivoAtomico;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Os procedimentos que o Jarvis aprendeu fazendo — as "skills" do Hermes Agent.
 *
 * <p>A memória guarda <b>quem o dono é</b>; isto guarda <b>como fazer</b> uma classe de tarefa do
 * jeito que ele quer: os passos na ordem, as ferramentas que funcionaram e as armadilhas. Quem
 * escreve é o {@link RevisorDeProcedimentos}, depois de um turno com várias ferramentas; quem lê é
 * o modelo da conversa, pela {@code ler_procedimento}, quando o pedido casa com o "quando" de um
 * deles. Só o índice (nome e "quando") vai ao prompt de toda conversa — o corpo é carregado sob
 * demanda, como no Hermes, para o prompt não crescer com cada coisa aprendida.
 *
 * <p>Um arquivo Markdown por procedimento em {@code <MEMORIA_PASTA>/procedimentos/usuarios/<id>/},
 * com um cabeçalho JSON numa linha de comentário. Pasta derivada, como a do {@code CacheDeVoz}:
 * não vale configuração nova para uma subpasta de um volume que já está montado. O nome do arquivo
 * é o {@code nome} validado contra {@link #NOME} — nenhuma entrada do modelo vira caminho.
 *
 * <p>Esquecer não apaga: move para {@code .removidos}, então nada aqui é irreversível.
 */
@Service
public class ProcedimentosService {
    private static final Logger log = LoggerFactory.getLogger(ProcedimentosService.class);

    public static final int LIMITE_PROCEDIMENTOS = 40;
    public static final int LIMITE_CORPO = 3_000;
    public static final int LIMITE_TITULO = 80;
    public static final int LIMITE_QUANDO = 200;
    /** Teto do índice que vai ao bloco dinâmico do system, aviso de corte incluído. */
    public static final int LIMITE_INDICE = 1_200;
    private static final long LIMITE_BYTES = 16_384;
    private static final String AVISO_INDICE = "(há mais procedimentos; peça a lista ao dono)\n";

    /** O nome é o arquivo: minúsculas, números e hífen, como o slug de projeto da memória. */
    public static final Pattern NOME = Pattern.compile("[a-z0-9][a-z0-9-]{1,49}");
    private static final Pattern CABECALHO = Pattern.compile("\\A<!-- jarvis:procedimento (\\{[^\\n]*}) -->\\n");
    /**
     * Segredo com valor. Mais estreito que o filtro da memória de propósito: procedimento técnico
     * fala de "token" e de "chave" o tempo todo ("conferir o custo em tokens"), e recusar a palavra
     * recusaria metade do que vale aprender. O que não pode entrar é o valor.
     */
    private static final Pattern SEGREDO = Pattern.compile(
            "(?i)(-----BEGIN [A-Z ]*PRIVATE KEY|\\bsk-[a-z0-9_-]{12,}|eyJ[A-Za-z0-9_-]{20,}\\."
                    + "|(senha|password|api[_ -]?key|token|secret)\\s*[:=]\\s*\\S{6,})");

    public record Procedimento(String nome, String titulo, String quando, String corpo,
                               long revisao, Instant atualizadoEm) { }

    /** O que uma gravação fez: {@code atualizado} distingue reescrever de aprender. */
    public record Gravado(String nome, String titulo, boolean atualizado) { }

    private final Path raiz;
    private final JsonMapper json;
    private final Clock relogio;

    @Autowired
    public ProcedimentosService(JarvisProperties props, JsonMapper json) {
        this(pastaDe(props), json, Clock.systemUTC());
    }

    public ProcedimentosService(Path raiz, JsonMapper json, Clock relogio) {
        this.raiz = raiz.toAbsolutePath().normalize();
        this.json = json;
        this.relogio = relogio;
    }

    private static Path pastaDe(JarvisProperties props) {
        String memoria = props.memoria() == null || props.memoria().pasta() == null
                || props.memoria().pasta().isBlank() ? "/data/jarvis/memoria" : props.memoria().pasta();
        return Path.of(memoria).resolve("procedimentos");
    }

    /** Todos os procedimentos do usuário, por nome. Arquivo ilegível fica de fora com um WARN. */
    public synchronized List<Procedimento> listar(long usuario) throws IOException {
        Path pasta = pastaUsuario(usuario);
        if (!Files.isDirectory(pasta, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Procedimento> lista = new ArrayList<>();
        try (DirectoryStream<Path> arquivos = Files.newDirectoryStream(pasta, "*.md")) {
            for (Path arquivo : arquivos) {
                if (!Files.isRegularFile(arquivo, LinkOption.NOFOLLOW_LINKS)) continue;
                String nome = arquivo.getFileName().toString();
                nome = nome.substring(0, nome.length() - 3);
                if (!NOME.matcher(nome).matches()) continue;
                ler(arquivo, nome).ifPresent(lista::add);
            }
        }
        lista.sort(Comparator.comparing(Procedimento::nome));
        return lista;
    }

    /** O procedimento daquele nome, ou vazio. Nome fora do padrão é vazio, nunca caminho. */
    public synchronized Optional<Procedimento> ler(long usuario, String nome) throws IOException {
        if (usuario <= 0 || nome == null || !NOME.matcher(nome).matches()) return Optional.empty();
        Path arquivo = pastaUsuario(usuario).resolve(nome + ".md");
        if (!Files.isRegularFile(arquivo, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        return ler(arquivo, nome);
    }

    /**
     * Cria ou reescreve. Reescrever substitui o corpo inteiro, e a versão anterior fica no
     * {@code .bak} do {@link ArquivoAtomico}. Conteúdo igual ao gravado não grava e devolve
     * {@code null}, para o revisor não avisar o que não mudou.
     *
     * @throws IllegalArgumentException campo fora do limite, nome inválido, segredo, ou o teto de
     *                                  procedimentos atingido por um nome novo
     */
    public synchronized Gravado gravar(long usuario, String nome, String titulo, String quando,
                                       String corpo) throws IOException {
        if (usuario <= 0) throw new IllegalArgumentException("usuário");
        nome = nome == null ? "" : nome.trim();
        titulo = umaLinha(titulo);
        quando = umaLinha(quando);
        corpo = corpo == null ? "" : corpo.strip().replace("\r\n", "\n");
        if (!NOME.matcher(nome).matches()) throw new IllegalArgumentException("nome");
        if (titulo.isEmpty() || titulo.length() > LIMITE_TITULO) throw new IllegalArgumentException("titulo");
        if (quando.isEmpty() || quando.length() > LIMITE_QUANDO) throw new IllegalArgumentException("quando");
        if (corpo.isEmpty() || corpo.length() > LIMITE_CORPO) throw new IllegalArgumentException("corpo");
        if (corpo.contains("<!-- jarvis:")) throw new IllegalArgumentException("corpo");
        for (String campo : List.of(titulo, quando, corpo)) {
            if (SEGREDO.matcher(campo).find()) throw new IllegalArgumentException("segredo");
        }

        Optional<Procedimento> atual = ler(usuario, nome);
        if (atual.isEmpty() && listar(usuario).size() >= LIMITE_PROCEDIMENTOS) {
            throw new IllegalArgumentException("limite");
        }
        if (atual.isPresent() && atual.get().titulo().equals(titulo)
                && atual.get().quando().equals(quando) && atual.get().corpo().equals(corpo)) {
            return null;
        }
        long revisao = atual.map(p -> p.revisao() + 1).orElse(1L);
        ObjectNode cabecalho = json.createObjectNode().put("titulo", titulo).put("quando", quando)
                .put("revisao", revisao).put("atualizadoEm", relogio.instant().toString());
        Path pasta = pastaUsuario(usuario);
        conferirPasta(pasta);
        ArquivoAtomico.gravar(pasta.resolve(nome + ".md"),
                "<!-- jarvis:procedimento " + json.writeValueAsString(cabecalho) + " -->\n" + corpo + "\n");
        return new Gravado(nome, titulo, atual.isPresent());
    }

    /**
     * Tira de vista movendo para {@code .removidos}, com o instante no nome. Devolve o título do que
     * saiu, ou vazio quando não havia nada com aquele nome.
     */
    public synchronized Optional<String> esquecer(long usuario, String nome) throws IOException {
        Optional<Procedimento> atual = ler(usuario, nome);
        if (atual.isEmpty()) return Optional.empty();
        Path pasta = pastaUsuario(usuario);
        Path removidos = pasta.resolve(".removidos");
        conferirPasta(removidos);
        Path origem = pasta.resolve(nome + ".md");
        Files.move(origem, removidos.resolve(nome + "-" + relogio.millis() + ".md"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(ArquivoAtomico.backupDe(origem));
        return Optional.of(atual.get().titulo());
    }

    /**
     * O índice que vai ao bloco dinâmico do system: uma linha por procedimento, nome e "quando".
     * Vazio quando não há nada, e aí a seção nem aparece no prompt.
     */
    public String indice(long usuario) {
        if (usuario <= 0) return "";
        List<Procedimento> todos;
        try {
            todos = listar(usuario);
        } catch (IOException | RuntimeException e) {
            log.warn("Procedimentos indisponíveis para o contexto do usuário {}: {}", usuario,
                    e.getClass().getSimpleName());
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Procedimento p : todos) {
            String linha = "- " + p.nome() + ": " + p.quando() + "\n";
            if (sb.length() + linha.length() > LIMITE_INDICE - AVISO_INDICE.length()) {
                sb.append(AVISO_INDICE);
                break;
            }
            sb.append(linha);
        }
        return sb.toString();
    }

    private Optional<Procedimento> ler(Path arquivo, String nome) {
        try {
            String bruto = ArquivoAtomico.ler(arquivo, LIMITE_BYTES);
            Matcher m = CABECALHO.matcher(bruto);
            if (!m.find()) {
                log.warn("Procedimento sem cabeçalho ignorado: {}", nome);
                return Optional.empty();
            }
            JsonNode cabecalho = json.readTree(m.group(1));
            String corpo = bruto.substring(m.end()).strip();
            return Optional.of(new Procedimento(nome, Json.texto(cabecalho.path("titulo")),
                    Json.texto(cabecalho.path("quando")), corpo, Json.numero(cabecalho.path("revisao")),
                    instante(Json.texto(cabecalho.path("atualizadoEm")))));
        } catch (IOException | RuntimeException e) {
            log.warn("Procedimento ilegível ignorado: {} ({})", nome, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Instant instante(String texto) {
        try {
            return Instant.parse(texto);
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }

    private Path pastaUsuario(long usuario) {
        return raiz.resolve("usuarios").resolve(Long.toString(usuario));
    }

    /** A pasta tem de ser pasta de verdade: um link simbólico ali levaria a escrita para fora. */
    private void conferirPasta(Path pasta) throws IOException {
        Files.createDirectories(pasta);
        for (Path p = pasta; p != null && p.startsWith(raiz); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IOException("pasta de procedimentos é link simbólico");
        }
    }

    private static String umaLinha(String texto) {
        return texto == null ? "" : texto.replaceAll("\\s+", " ").trim();
    }
}
