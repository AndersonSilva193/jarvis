package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.GuardaDeConfirmacao;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.telegram.TelegramClient;
import dev.jarvis.telegram.TelegramRecusouException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A ferramenta controlar_pc: validação por ação, confirmação em dois passos e a captura. */
class ControlarPcFerramentaTest {

    private static final long CHAT = 42;
    private static final Pattern CODIGO = Pattern.compile("\\b(\\d{4})\\b");
    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x42 };

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JarvisProperties PROPS = new JarvisProperties("Anderson",
            "America/Sao_Paulo", "", new JarvisProperties.Telegram("teste", CHAT),
            null, null, null, null, null, null, null, null,
            new JarvisProperties.Pc("token-do-pc"));

    private final RelogioFalso relogio = new RelogioFalso();
    private final PcService pc = new PcService(relogio);
    private final GuardaDeConfirmacao guarda = new GuardaDeConfirmacao(relogio);
    private final TelegramClient telegram = mock(TelegramClient.class);

    private final ControlarPcFerramenta ferramenta = new ControlarPcFerramenta(pc, guarda, telegram,
            JSON, PROPS, Duration.ofMillis(300));

    @AfterEach
    void limpar() {
        ferramenta.fechar();
    }

    /** Põe o PC online e com a lista de apps que o agente reportaria. */
    private void pcOnline(String apps) {
        pc.proximoComando(Duration.ofMillis(10), apps);
    }

    private static ObjectNode entrada(String acao, String argumento, String codigo) {
        ObjectNode entrada = JSON.createObjectNode();
        entrada.put("acao", acao);
        if (argumento != null) {
            entrada.put("argumento", argumento);
        }
        if (codigo != null) {
            entrada.put("codigo_confirmacao", codigo);
        }
        return entrada;
    }

    private String texto(String acao, String argumento) {
        return chamar(entrada(acao, argumento, null)).texto();
    }

    private ResultadoFerramenta chamar(ObjectNode entrada) {
        return ferramenta.executarDetalhado(entrada, new OuvinteProgresso() { });
    }

    /**
     * Chama a ferramenta com um agente de mentira do outro lado: uma thread pega o comando da
     * fila e posta a resposta, que é o que o PC de verdade faria.
     */
    private ResultadoFerramenta comAgente(ObjectNode entrada, boolean ok, String mensagem,
            byte[] imagem) {
        Thread agente = new Thread(() -> {
            Optional<ComandoPc> comando = pc.proximoComando(Duration.ofSeconds(2), null);
            comando.ifPresent(c -> pc.concluir(new ResultadoPc(c.id(), ok, mensagem, imagem,
                    imagem == null ? null : "image/png")));
        });
        agente.setDaemon(true);
        agente.start();
        return chamar(entrada);
    }

    private static String codigoDe(String texto) {
        Matcher achado = CODIGO.matcher(texto);
        assertTrue(achado.find(), "esperava um código de 4 dígitos em: " + texto);
        return achado.group(1);
    }

    @Test
    @DisplayName("PC offline não enfileira nada e avisa o modelo na hora")
    void offlineNaoEnfileira() {
        String resposta = texto("bloquear_tela", null);

        assertTrue(resposta.contains("nunca se conectou"), resposta);
        assertEquals(0, pc.naFila(), "travar o turno 30 s para dizer 'offline' é o pior dos mundos");
    }

    @Test
    @DisplayName("PC que se calou há muito tempo também responde offline, com o tempo")
    void offlineComUltimoContato() {
        pcOnline("chrome");
        relogio.avancar(Duration.ofSeconds(240));

        String resposta = texto("bloquear_tela", null);

        assertTrue(resposta.contains("offline"), resposta);
        assertTrue(resposta.contains("4 min"), resposta);
        assertEquals(0, pc.naFila());
    }

    @Test
    @DisplayName("abrir_app só aceita nome da lista que o agente reportou")
    void abrirAppSoDaLista() {
        pcOnline("chrome,vscode");

        String recusado = texto("abrir_app", "excel");
        assertTrue(recusado.contains("não está na lista"), recusado);
        assertTrue(recusado.contains("chrome"), "a recusa mostra o que dá para abrir");
        assertEquals(0, pc.naFila());

        // E a caixa não importa: o modelo repete o nome como o ouviu.
        assertEquals("Feito no PC: abri", comAgente(entrada("abrir_app", "Chrome", null),
                true, "abri", null).texto());
    }

    @Test
    @DisplayName("sem app reportado, abrir_app explica onde mexer")
    void abrirAppSemLista() {
        pcOnline("");

        String resposta = texto("abrir_app", "chrome");

        assertTrue(resposta.contains("config.properties"), resposta);
        assertEquals(0, pc.naFila());
    }

    @Test
    @DisplayName("abrir_url recusa http, javascript, file e credencial na URL")
    void abrirUrlSoHttps() {
        pcOnline("chrome");

        for (String url : new String[] { "http://exemplo.com", "javascript:alert(1)",
                "file:///C:/Windows/System32", "https://usuario:senha@exemplo.com",
                "ms-msdt:/id", "exemplo.com", "https:// espaco.com" }) {
            String resposta = texto("abrir_url", url);
            assertTrue(resposta.contains("Só abro endereço https"), url + " -> " + resposta);
        }
        assertEquals(0, pc.naFila(), "nenhuma dessas podia chegar ao PC");

        assertEquals("Feito no PC: abri", comAgente(entrada("abrir_url",
                "https://exemplo.com/pagina", null), true, "abri", null).texto());
    }

    @Test
    @DisplayName("volume aceita só subir, baixar e mudo")
    void volumeTresValores() {
        pcOnline("chrome");

        String resposta = texto("volume", "muito alto");
        assertTrue(resposta.contains("subir, baixar ou mudo"), resposta);
        assertEquals(0, pc.naFila());

        assertEquals("Feito no PC.", comAgente(entrada("volume", "baixar", null),
                true, "", null).texto());
    }

    @Test
    @DisplayName("ação que não existe é recusada com a lista das válidas")
    void acaoDesconhecida() {
        pcOnline("chrome");

        String resposta = texto("formatar_disco", null);

        assertTrue(resposta.contains("Não conheço a ação"), resposta);
        assertEquals(0, pc.naFila());
    }

    @Test
    @DisplayName("desligar pede código antes e só executa com o código certo")
    void desligarPedeConfirmacao() {
        pcOnline("chrome");

        String pedido = texto("desligar", null);
        assertTrue(pedido.contains("Confirmação necessária"), pedido);
        assertTrue(pedido.contains("ESPERE"), "o modelo tem de esperar o usuário repetir");
        assertEquals(0, pc.naFila(), "a primeira chamada não enfileira nada");

        ResultadoFerramenta executado = comAgente(entrada("desligar", null, codigoDe(pedido)),
                true, "desligando em 60 s", null);
        assertTrue(executado.texto().contains("desligando"), executado.texto());
    }

    @Test
    @DisplayName("código errado, expirado, de outra ação ou sem pendência não desliga")
    void codigoQueNaoVale() {
        pcOnline("chrome");

        // Sem pendência nenhuma.
        String semPendencia = chamar(entrada("desligar", null, "1234")).texto();
        assertTrue(semPendencia.contains("Não há confirmação em aberto"), semPendencia);

        // Errado, e a tentativa queima a confirmação: nem o certo vale depois.
        String certo = codigoDe(texto("desligar", null));
        String errado = certo.equals("0000") ? "1111" : "0000";
        assertTrue(chamar(entrada("desligar", null, errado)).texto().contains("Código incorreto"));
        assertTrue(chamar(entrada("desligar", null, certo)).texto()
                .contains("Não há confirmação em aberto"), "errar uma vez cancela de vez");

        // Código de desligar não reinicia.
        String deDesligar = codigoDe(texto("desligar", null));
        assertTrue(chamar(entrada("reiniciar", null, deDesligar)).texto().contains("Código incorreto"));

        // Expirado: a ferramenta já devolve um código novo. Os 121 s também passariam dos 90 s
        // sem contato, então o agente "volta a falar" antes da segunda chamada.
        String velho = codigoDe(texto("reiniciar", null));
        relogio.avancar(Duration.ofSeconds(121));
        pcOnline("chrome");
        String expirado = chamar(entrada("reiniciar", null, velho)).texto();
        assertTrue(expirado.contains("expirou"), expirado);
        assertFalse(expirado.contains(velho), "o código novo não pode ser o que acabou de vencer");

        assertEquals(0, pc.naFila(), "em nenhum desses caminhos o PC recebeu ordem");
    }

    @Test
    @DisplayName("pedido perigoso novo invalida o código anterior")
    void pedidoNovoInvalidaAnterior() {
        pcOnline("chrome");

        String deDesligar = codigoDe(texto("desligar", null));
        texto("reiniciar", null);

        assertTrue(chamar(entrada("desligar", null, deDesligar)).texto().contains("Código incorreto"));
        assertEquals(0, pc.naFila());
    }

    @Test
    @DisplayName("cancelar_desligamento vai ao PC e esquece a confirmação pendente")
    void cancelarEsqueceConfirmacao() {
        pcOnline("chrome");
        String codigo = codigoDe(texto("desligar", null));

        comAgente(entrada("cancelar_desligamento", null, null), true, "cancelado", null);

        assertTrue(chamar(entrada("desligar", null, codigo)).texto()
                .contains("Não há confirmação em aberto"));
    }

    @Test
    @DisplayName("status_pc vai ao agente, que é quem mede processador, memória e disco")
    void statusVaiAoAgente() {
        pcOnline("chrome");

        String resposta = comAgente(entrada("status_pc", null, null), true,
                "CPU 23%, memória 11,2/32 GB, disco C: 180 GB livres de 930, bateria 87%",
                null).texto();

        assertTrue(resposta.contains("CPU 23%"), resposta);
    }

    @Test
    @DisplayName("recusa do PC chega ao modelo como recusa, não como feito")
    void recusaDoPc() {
        pcOnline("chrome");

        String resposta = comAgente(entrada("bloquear_tela", null, null), false,
                "rundll32 saiu com código 1", null).texto();

        assertTrue(resposta.startsWith("O PC recusou:"), resposta);
    }

    @Test
    @DisplayName("sem resultado na espera, avisa comando enviado sem confirmação")
    void semRespostaNaEspera() {
        pcOnline("chrome");

        // Nenhum agente do outro lado: a ferramenta desiste, mas o comando segue valendo.
        String resposta = texto("bloquear_tela", null);

        assertTrue(resposta.matches("Comando [0-9a-f-]{36} no PC: sem confirmação\\. Consulte status_operacao_pc pelo ID\\."), resposta);
        assertEquals(1, pc.naFila(), "desistir de esperar não cancela o comando");
    }

    @Test
    @DisplayName("captura_tela devolve artefato de imagem e manda a foto no Telegram")
    void capturaVaiAosDoisDestinos() throws Exception {
        pcOnline("chrome");

        ResultadoFerramenta resultado = comAgente(entrada("captura_tela", null, null),
                true, "print de 1600 px", PNG);

        assertEquals("Print do PC na tela e enviado no Telegram.", resultado.texto());
        verify(telegram).enviarFoto(eq(CHAT), any(), any(), any());

        assertEquals(1, resultado.artefatos().size());
        var artefato = resultado.artefatos().get(0);
        assertEquals("image/png", artefato.mime(), "é o mime que faz o AnexosService tratar como imagem");
        assertTrue(artefato.nome().endsWith(".png"));
        assertTrue(Files.isRegularFile(artefato.arquivo()), "o AnexosService copia o arquivo depois");
        assertArrayEqualsPng(Files.readAllBytes(artefato.arquivo()));
    }

    private static void assertArrayEqualsPng(byte[] lidos) {
        assertEquals(PNG.length, lidos.length);
        for (int i = 0; i < PNG.length; i++) {
            assertEquals(PNG[i], lidos[i], "byte " + i);
        }
    }

    @Test
    @DisplayName("Telegram fora do ar não derruba a captura_tela")
    void telegramForaNaoDerrubaCaptura() throws Exception {
        pcOnline("chrome");
        doThrow(new TelegramRecusouException("sendPhoto", 400, "PHOTO_INVALID_DIMENSIONS"))
                .when(telegram).enviarFoto(eq(CHAT), any(), any(), any());

        ResultadoFerramenta resultado = comAgente(entrada("captura_tela", null, null),
                true, "print", PNG);

        assertEquals("Print do PC na tela. Não consegui enviar no Telegram.", resultado.texto());
        assertEquals(1, resultado.artefatos().size(), "a HUD continua recebendo o print");
    }

    @Test
    @DisplayName("captura sem imagem não inventa anexo nem chama o Telegram")
    void capturaSemImagem() throws Exception {
        pcOnline("chrome");

        ResultadoFerramenta resultado = comAgente(entrada("captura_tela", null, null),
                false, "não consigo ver a tela", null);

        assertTrue(resultado.texto().contains("não consigo ver a tela"), resultado.texto());
        assertTrue(resultado.artefatos().isEmpty());
        verify(telegram, never()).enviarFoto(eq(CHAT), any(), any(), any());
    }

    @Test
    @DisplayName("o esquema lista as ações e some com abrir_app quando não há app")
    void esquemaAcompanhaALista() {
        // O enum de 'acao', não o esquema inteiro: a descrição do 'argumento' cita abrir_app de
        // qualquer jeito, para explicar o campo.
        String semApps = enumDeAcoes();
        assertFalse(semApps.contains("abrir_app"), "ação que vai recusar tudo só gasta token");
        assertTrue(semApps.contains("bloquear_tela"));

        pcOnline("chrome,spotify");
        String comApps = enumDeAcoes();
        assertTrue(comApps.contains("abrir_app"));
        assertTrue(ferramenta.esquemaEntrada().toString().contains("spotify"),
                "os nomes entram na descrição do argumento");
        assertTrue(ferramenta.descricao().contains("chrome, spotify"));

        for (String acao : ComandoPc.ACOES) {
            if ("criar_arquivo".equals(acao) || "rodar_rotina".equals(acao)
                    || "computer_acao".equals(acao)
                    || java.util.List.of("preparar_edicao", "aplicar_edicao",
                            "preparar_desfazer", "aplicar_desfazer").contains(acao)) continue; // ferramentas próprias
            assertTrue(comApps.contains(acao), "ação '" + acao + "' fora do enum");
        }
    }

    private String enumDeAcoes() {
        return ferramenta.esquemaEntrada().path("properties").path("acao").path("enum").toString();
    }

    @Test
    @DisplayName("argumento em ação que não usa argumento não chega ao PC")
    void argumentoIgnorado() {
        pcOnline("chrome");

        Thread agente = new Thread(() -> pc.proximoComando(Duration.ofSeconds(2), null)
                .ifPresent(c -> {
                    assertEquals("", c.argumento(), "o agente não tem o que fazer com isto");
                    pc.concluir(new ResultadoPc(c.id(), true, "trancado", null, null));
                }));
        agente.setDaemon(true);
        agente.start();

        assertTrue(chamar(entrada("bloquear_tela", "agora mesmo", null)).texto().contains("trancado"));
    }

    @Test
    @DisplayName("a descrição avisa quando o agente não reportou app nenhum")
    void descricaoSemApps() {
        assertTrue(ferramenta.descricao().contains("nenhum"), ferramenta.descricao());
        assertTrue(ferramenta.descricao().contains("codigo_confirmacao"),
                "o fluxo de confirmação tem de estar explicado para o modelo");
    }

    @Test
    @DisplayName("as ações do despertar chegam ao agente sem confirmação e sem argumento")
    void acoesDoDespertar() {
        pcOnline("chrome");

        // Nenhuma das duas é perigosa nem leva argumento: vão direto para a fila.
        assertTrue(comAgente(entrada("modo_repouso", null, null), true, "voltei a escutar", null)
                .texto().contains("voltei a escutar"));
        assertTrue(comAgente(entrada("falso_positivo", null, null), true, "3 alarmes falsos", null)
                .texto().contains("3 alarmes falsos"));

        // E o modelo precisa saber quando usar cada uma.
        assertTrue(ferramenta.descricao().contains("modo_repouso"), ferramenta.descricao());
        assertTrue(ferramenta.descricao().contains("falso_positivo"), ferramenta.descricao());
        assertTrue(ferramenta.esquemaEntrada().toString().contains("modo_repouso"));
    }

    @Test
    @DisplayName("o tempo sem contato é falado em segundos, minutos ou horas")
    void tempoFalado() {
        assertEquals(List.of("8 s", "4 min", "3 h"), List.of(
                ControlarPcFerramenta.comoFalar(Duration.ofSeconds(8)),
                ControlarPcFerramenta.comoFalar(Duration.ofMinutes(4)),
                ControlarPcFerramenta.comoFalar(Duration.ofHours(3))));
    }
}
