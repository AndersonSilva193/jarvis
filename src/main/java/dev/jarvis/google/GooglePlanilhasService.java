package dev.jarvis.google;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.PedidoArquivo;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** OAuth e criação de planilhas com HTTP direto; nenhum token entra no prompt ou nos logs. */
@Service
public class GooglePlanilhasService {
    private static final String ESCOPO = "https://www.googleapis.com/auth/drive.file";
    private record Pendente(Instant expira) { }
    private final Map<String, Pendente> estados = new ConcurrentHashMap<>();
    private final HttpClient http;
    private final JsonMapper json;
    private final JarvisProperties.Google config;
    private final SecureRandom aleatorio = new SecureRandom();

    public GooglePlanilhasService(HttpClient http, JsonMapper json, JarvisProperties props) {
        this.http = http; this.json = json; this.config = props.google();
    }

    public boolean configurado() {
        if (config == null || !preenchido(config.clientId()) || !preenchido(config.clientSecret())
                || !preenchido(config.redirectUri()) || !preenchido(config.chaveCofre())) return false;
        try {
            URI retorno = URI.create(config.redirectUri());
            return "https".equalsIgnoreCase(retorno.getScheme()) && retorno.getHost() != null
                    && Base64.getDecoder().decode(config.chaveCofre()).length == 32;
        } catch (IllegalArgumentException e) { return false; }
    }

    public boolean autorizado() {
        if (!configurado()) return false;
        try { return Files.isRegularFile(caminhoCredencial()); }
        catch (RuntimeException e) { return false; }
    }

    public String iniciar() throws IOException {
        if (!configurado()) throw new IOException("Integração Google não configurada.");
        byte[] nonce = new byte[32]; aleatorio.nextBytes(nonce);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        estados.put(state, new Pendente(Instant.now().plus(Duration.ofMinutes(10))));
        return "https://accounts.google.com/o/oauth2/v2/auth?response_type=code&access_type=offline"
                + "&prompt=consent&scope=" + codificar(ESCOPO)
                + "&client_id=" + codificar(config.clientId())
                + "&redirect_uri=" + codificar(config.redirectUri())
                + "&state=" + codificar(state);
    }

    public void concluir(String state, String code) throws Exception {
        if (!preenchido(state)) throw new IOException("Autorização sem state.");
        Pendente pendente = estados.remove(state);
        if (pendente == null || !Instant.now().isBefore(pendente.expira()) || !preenchido(code))
            throw new IOException("Autorização vencida ou inválida.");
        ObjectNode token = token(Map.of("grant_type", "authorization_code", "code", code,
                "client_id", config.clientId(), "client_secret", config.clientSecret(),
                "redirect_uri", config.redirectUri()));
        String refresh = Json.texto(token.path("refresh_token"));
        if (refresh.isBlank()) throw new IOException("Google não devolveu refresh token.");
        guardarSegredo(refresh);
    }

    public String criar(JsonNode pedido) throws Exception {
        return criar(pedido, pedido.toString());
    }

    public String criar(JsonNode pedido, String comandoId) throws Exception {
        if (!autorizado()) throw new IOException("Google não autorizado; crie XLSX para importar.");
        String erro = PedidoArquivo.validar(pedido);
        if (!erro.isEmpty() || !"xlsx".equals(Json.texto(pedido.path("formato"))))
            throw new IOException(erro.isEmpty() ? "Planilha Google exige formato xlsx." : erro);
        String acesso = acesso();
        String idComando = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest
                .getInstance("SHA-256").digest(comandoId
                        .getBytes(StandardCharsets.UTF_8))).substring(0, 40);
        String hashDados = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest
                .getInstance("SHA-256").digest(pedido.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 40);
        String id = procurar(acesso, idComando, hashDados);
        if (id == null) {
            ObjectNode corpo = json.createObjectNode();
            corpo.put("name", Json.texto(pedido.path("nome")));
            corpo.put("mimeType", "application/vnd.google-apps.spreadsheet");
            corpo.putObject("appProperties").put("jarvisComando", idComando)
                    .put("jarvisDados", hashDados);
            try {
                JsonNode criado = chamar("POST", "https://www.googleapis.com/drive/v3/files?fields=id,webViewLink",
                        acesso, corpo);
                id = Json.texto(criado.path("id"));
            } catch (IOException e) {
                id = procurar(acesso, idComando, hashDados);
                if (id == null) throw new IOException("Criação incerta no Google; consulte novamente antes de repetir.", e);
            }
        }
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) throw new IOException("Google não devolveu ID válido.");
        preencher(acesso, id, pedido);
        return "https://docs.google.com/spreadsheets/d/" + id + "/edit";
    }

    private String procurar(String acesso, String idComando, String hashDados) throws Exception {
        String q = "appProperties has { key='jarvisComando' and value='" + idComando
                + "' } and trashed=false";
        JsonNode resposta = chamar("GET", "https://www.googleapis.com/drive/v3/files?q=" + codificar(q)
                + "&fields=files(id,appProperties)&pageSize=10", acesso, null);
        JsonNode arquivos = resposta.path("files");
        if (!arquivos.isArray() || arquivos.isEmpty()) return null;
        JsonNode arquivo = arquivos.get(0);
        if (!hashDados.equals(Json.texto(arquivo.path("appProperties").path("jarvisDados"))))
            throw new IOException("ID do comando já usado com dados diferentes.");
        return Json.texto(arquivo.path("id"));
    }

    private void preencher(String acesso, String id, JsonNode pedido) throws Exception {
        JsonNode atual = chamar("GET", "https://sheets.googleapis.com/v4/spreadsheets/" + id
                + "?fields=sheets.properties", acesso, null);
        ArrayNode requests = json.createArrayNode();
        ObjectNode localidade = requests.addObject().putObject("updateSpreadsheetProperties");
        localidade.putObject("properties").put("locale", "pt_BR")
                .put("timeZone", "America/Sao_Paulo");
        localidade.put("fields", "locale,timeZone");
        JsonNode abas = pedido.path("abas");
        JsonNode existentes = atual.path("sheets");
        int primeira = existentes.isArray() && !existentes.isEmpty()
                ? (int) Json.numero(existentes.get(0).path("properties").path("sheetId")) : 0;
        for (int a = 0; a < abas.size(); a++) {
            JsonNode aba = abas.get(a);
            JsonNode fontes = pedido.path("fontes");
            int linhasFontes = a == 0 && fontes.isArray() && !fontes.isEmpty() ? fontes.size() + 2 : 0;
            boolean jaExiste = existentes.isArray() && a < existentes.size();
            int sheetId = jaExiste ? (int) Json.numero(existentes.get(a).path("properties").path("sheetId"))
                    : 1000 + a;
            ObjectNode propriedades = json.createObjectNode().put("sheetId", sheetId)
                    .put("title", Json.texto(aba.path("nome")));
            propriedades.putObject("gridProperties").put("frozenRowCount", 1)
                    .put("rowCount", Math.max(2, aba.path("linhas").size() + 1 + linhasFontes))
                    .put("columnCount", aba.path("colunas").size());
            if (jaExiste) requests.addObject().putObject("updateSheetProperties")
                    .set("properties", propriedades);
            else requests.addObject().putObject("addSheet").set("properties", propriedades);
            if (jaExiste) ((ObjectNode) requests.get(requests.size()-1).path("updateSheetProperties"))
                    .put("fields", "title,gridProperties");
            ObjectNode update = requests.addObject().putObject("updateCells");
            update.putObject("start").put("sheetId", sheetId).put("rowIndex", 0).put("columnIndex", 0);
            update.put("fields", "userEnteredValue,userEnteredFormat");
            ArrayNode rows = update.putArray("rows");
            ArrayNode cabecalho = rows.addObject().putArray("values");
            for (JsonNode coluna : aba.path("colunas")) {
                ObjectNode cel = cabecalho.addObject();
                cel.putObject("userEnteredValue").put("stringValue", Json.texto(coluna.path("nome")));
                cel.putObject("userEnteredFormat").putObject("textFormat").put("bold", true);
            }
            for (JsonNode linha : aba.path("linhas")) {
                ArrayNode valores = rows.addObject().putArray("values");
                for (JsonNode entrada : linha) {
                    ObjectNode cel = valores.addObject();
                    String tipo = Json.texto(entrada.path("tipo"));
                    JsonNode valorNo = entrada.path("valor");
                    String valor = valorNo.isTextual() ? valorNo.stringValue() : valorNo.toString();
                    ObjectNode dado = cel.putObject("userEnteredValue");
                    switch (tipo) {
                        case "numero", "moeda" -> {
                            dado.put("numberValue", Double.parseDouble(valor.replace(',', '.')));
                            cel.putObject("userEnteredFormat").putObject("numberFormat")
                                    .put("type", tipo.equals("moeda") ? "CURRENCY" : "NUMBER")
                                    .put("pattern", tipo.equals("moeda") ? "R$ #,##0.00" : "#,##0.########");
                        }
                        case "booleano" -> dado.put("boolValue", Boolean.parseBoolean(valor));
                        case "formula" -> dado.put("formulaValue", valor.startsWith("=") ? valor : "=" + valor);
                        case "data" -> {
                            double serial = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30),
                                    LocalDate.parse(valor));
                            dado.put("numberValue", serial);
                            cel.putObject("userEnteredFormat").putObject("numberFormat")
                                    .put("type", "DATE").put("pattern", "dd/mm/yyyy");
                        }
                        default -> dado.put("stringValue", valor);
                    }
                }
            }
            if (linhasFontes > 0) {
                rows.addObject().putArray("values");
                rows.addObject().putArray("values").addObject().putObject("userEnteredValue")
                        .put("stringValue", "Fontes e data de consulta");
                for (JsonNode fonte : fontes) rows.addObject().putArray("values").addObject()
                        .putObject("userEnteredValue").put("stringValue",
                                Json.texto(fonte.path("titulo")) + " — "
                                + Json.texto(fonte.path("url")) + " (consulta: "
                                + Json.texto(fonte.path("consultadoEm")) + ")");
            }
            requests.addObject().putObject("setBasicFilter").putObject("filter").putObject("range")
                    .put("sheetId", sheetId).put("startRowIndex", 0)
                    .put("endRowIndex", Math.max(2, aba.path("linhas").size() + 1))
                    .put("startColumnIndex", 0).put("endColumnIndex", aba.path("colunas").size());
        }
        ObjectNode corpo = json.createObjectNode(); corpo.set("requests", requests);
        chamar("POST", "https://sheets.googleapis.com/v4/spreadsheets/" + id + ":batchUpdate",
                acesso, corpo);
    }

    private String acesso() throws Exception {
        ObjectNode resposta;
        try {
            resposta = token(Map.of("grant_type", "refresh_token", "refresh_token", lerSegredo(),
                    "client_id", config.clientId(), "client_secret", config.clientSecret()));
        } catch (IOException e) { throw new IOException("Autorização Google expirada ou revogada; reconecte pela HUD.", e); }
        String acesso = Json.texto(resposta.path("access_token"));
        if (acesso.isBlank()) throw new IOException("Autorização Google expirada; conecte novamente.");
        return acesso;
    }

    private ObjectNode token(Map<String, String> campos) throws Exception {
        StringBuilder corpo = new StringBuilder();
        for (var par : campos.entrySet()) {
            if (!corpo.isEmpty()) corpo.append('&');
            corpo.append(codificar(par.getKey())).append('=').append(codificar(par.getValue()));
        }
        HttpRequest pedido = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(corpo.toString())).build();
        HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() != 200) throw new IOException("Google recusou a autorização (HTTP "
                + resposta.statusCode() + ").");
        return (ObjectNode) json.readTree(resposta.body());
    }

    private JsonNode chamar(String metodo, String url, String acesso, JsonNode corpo) throws Exception {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40))
                .header("Authorization", "Bearer " + acesso);
        if ("POST".equals(metodo)) pedido.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)));
        else pedido.GET();
        HttpResponse<String> resposta = http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() / 100 != 2) throw new IOException("Google API respondeu HTTP "
                + resposta.statusCode());
        return json.readTree(resposta.body());
    }

    private Path caminhoCredencial() {
        String pasta = config.pastaCredenciais() == null || config.pastaCredenciais().isBlank()
                ? "/data/google" : config.pastaCredenciais();
        return Path.of(pasta).resolve("refresh-token.enc");
    }

    private void guardarSegredo(String valor) throws Exception {
        byte[] iv = new byte[12]; aleatorio.nextBytes(iv);
        Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
        cifra.init(Cipher.ENCRYPT_MODE, chave(), new GCMParameterSpec(128, iv));
        byte[] cifrado = cifra.doFinal(valor.getBytes(StandardCharsets.UTF_8));
        Path destino = caminhoCredencial(); Files.createDirectories(destino.getParent());
        Path temporario = Files.createTempFile(destino.getParent(), "token-", ".tmp");
        byte[] pacote = new byte[iv.length + cifrado.length];
        System.arraycopy(iv, 0, pacote, 0, iv.length);
        System.arraycopy(cifrado, 0, pacote, iv.length, cifrado.length);
        Files.write(temporario, pacote);
        Files.move(temporario, destino, StandardCopyOption.REPLACE_EXISTING);
    }

    private String lerSegredo() throws Exception {
        byte[] pacote = Files.readAllBytes(caminhoCredencial());
        if (pacote.length < 29) throw new IOException("Credencial Google inválida.");
        Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
        cifra.init(Cipher.DECRYPT_MODE, chave(), new GCMParameterSpec(128,
                java.util.Arrays.copyOfRange(pacote, 0, 12)));
        return new String(cifra.doFinal(pacote, 12, pacote.length - 12), StandardCharsets.UTF_8);
    }

    private SecretKeySpec chave() throws IOException {
        try {
            byte[] bytes = Base64.getDecoder().decode(config.chaveCofre());
            if (bytes.length != 32) throw new IOException("GOOGLE_CHAVE_COFRE deve ter 32 bytes em base64.");
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException e) { throw new IOException("GOOGLE_CHAVE_COFRE inválida."); }
    }

    private static boolean preenchido(String valor) { return valor != null && !valor.isBlank(); }
    private static String codificar(String valor) { return URLEncoder.encode(valor, StandardCharsets.UTF_8); }
}
