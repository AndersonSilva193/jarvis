package dev.jarvis.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import dev.jarvis.pc.PcService;
import dev.jarvis.pc.ArquivosPcService;
import dev.jarvis.pc.OperacoesPcService;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.tela.FalaDaTela;
import dev.jarvis.tela.FalaDaTelaService;

/**
 * Os endpoints do agente que roda no PC Windows, protegidos pelo {@link TokenPcFiltro}.
 *
 * O PC nunca recebe conexão: ele pergunta se tem comando (long poll de 25 s) e posta o resultado.
 * Nenhuma porta aberta na máquina, nenhum domínio, nada de webhook — o mesmo desenho do
 * {@code TelegramPoller}, só do outro lado.
 *
 * O {@code /falar} é a única rota em que o agente <b>inicia</b> alguma coisa, em vez de responder
 * a um comando: é o despertar por palmas encontrando a tela já aberta. Mesmo assim ele não manda
 * texto, só a chave de uma frase do {@link FalaDaTela} — ver o javadoc de lá.
 */
@RestController
@RequestMapping("/api/pc")
public class PcController {

    /**
     * Quanto a requisição do agente fica segurada esperando comando.
     *
     * <p>Folga confortável abaixo do {@code proxy_read_timeout} de {@code /api/pc/} no nginx (ver
     * {@code deploy/nginx-jarvis.conf}), e amarrada ao {@code LIMITE_SEM_CONTATO} do
     * {@code PcService}: 90 s tolera duas voltas de 25 s perdidas.
     */
    private static final Duration ESPERA_PADRAO = Duration.ofSeconds(25);

    private final PcService pc;
    private final FalaDaTelaService fala;
    private final Duration espera;
    private final OperacoesPcService e2;
    private final ArquivosPcService arquivos;

    // Com dois construtores e nenhum anotado o Spring procuraria um sem argumentos.
    @Autowired
    public PcController(PcService pc, FalaDaTelaService fala, OperacoesPcService e2,
            ArquivosPcService arquivos) {
        this(pc, fala, ESPERA_PADRAO, e2, arquivos);
    }

    public PcController(PcService pc, FalaDaTelaService fala) {
        this(pc, fala, ESPERA_PADRAO, null, null);
    }

    /** A espera curta é só para o teste não ficar 25 segundos parado. */
    PcController(PcService pc, FalaDaTelaService fala, Duration espera) {
        this(pc, fala, espera, null, null);
    }

    PcController(PcService pc, FalaDaTelaService fala, Duration espera, OperacoesPcService e2) {
        this(pc, fala, espera, e2, null);
    }

    PcController(PcService pc, FalaDaTelaService fala, Duration espera, OperacoesPcService e2,
            ArquivosPcService arquivos) {
        this.pc = pc;
        this.fala = fala;
        this.espera = espera;
        this.e2 = e2;
        this.arquivos = arquivos;
    }

    public record ResultadoE2(String estado, String mensagem, Integer codigoSaida,
            String imagemBase64, Boolean arquivo, tools.jackson.databind.JsonNode dados) { }

    @PostMapping("/v2/catalogo")
    public ResponseEntity<?> catalogo(@RequestBody List<OperacoesPcService.RotinaPublica> rotinas) {
        e2.catalogo(rotinas);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v2/comandos")
    public ResponseEntity<?> comandosE2(@RequestHeader(value = "X-Pc-Apps", required = false) String apps)
            throws InterruptedException {
        pc.proximoComando(Duration.ZERO, apps);
        return e2.proximo(espera).<ResponseEntity<?>>map(entrega -> ResponseEntity.ok(Map.of(
                "versao", OperacoesPcService.VERSAO, "comando", PcDto.de(entrega.comando()),
                "prazoInicio", entrega.prazoInicio(), "prazoExecucao", entrega.prazoExecucao())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/v2/comandos/{id}/recebido")
    public ResponseEntity<?> recebido(@PathVariable String id) {
        return e2.recebido(id) ? ResponseEntity.noContent().build() : ResponseEntity.status(409).build();
    }

    @PostMapping("/v2/comandos/{id}/executando")
    public ResponseEntity<?> executando(@PathVariable String id) {
        return e2.executando(id) ? ResponseEntity.noContent().build() : ResponseEntity.status(409).build();
    }

    @PostMapping("/v2/comandos/{id}/resultado")
    public ResponseEntity<?> resultadoE2(@PathVariable String id, @RequestBody ResultadoE2 corpo) {
        if (corpo == null || corpo.estado() == null || corpo.mensagem() == null
                || corpo.mensagem().length() > 1000) return ResponseEntity.badRequest().build();
        var operacao = e2.estado(id);
        if (operacao.isEmpty()) return ResponseEntity.notFound().build();
        boolean arquivo = Boolean.TRUE.equals(corpo.arquivo());
        boolean imagem = corpo.imagemBase64() != null && !corpo.imagemBase64().isBlank();
        boolean dados = corpo.dados() != null && !corpo.dados().isMissingNode();
        String acao = operacao.orElseThrow().acao();
        if (((arquivo || imagem || dados) && !"concluído".equals(corpo.estado()))
                || (arquivo && !"criar_arquivo".equals(acao))
                || (imagem && !"captura_tela".equals(acao))
                || (dados && !List.of("listar_arquivos", "ler_arquivo").contains(acao)))
            return ResponseEntity.badRequest().build();
        var dto = new PcDto.Resultado(id, "concluído".equals(corpo.estado()),
                corpo.mensagem(), corpo.imagemBase64(), arquivo, corpo.dados());
        if (!dto.erros().isEmpty()) return ResponseEntity.badRequest().build();
        if (arquivo && !operacao.orElseThrow().estado().equals("concluído")
                && (arquivos == null || arquivos.obter(id).isEmpty()))
            return ResponseEntity.status(409).build();
        ResultadoPc resultado = dto.paraDominio();
        return e2.concluir(id, corpo.estado(), resultado, corpo.codigoSaida(), corpo.mensagem())
                ? ResponseEntity.noContent().build() : ResponseEntity.status(409).build();
    }

    @GetMapping("/v2/comandos/{id}")
    public ResponseEntity<?> estadoE2(@PathVariable String id) {
        return e2.estado(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/v2/comandos/{id}/cancelamento")
    public Map<String, Boolean> cancelamentoE2(@PathVariable String id) {
        return Map.of("cancelar", e2.cancelamentoPedido(id));
    }

    /**
     * O próximo comando pendente, ou 204 depois de 25 s sem nenhum.
     *
     * <p>A espera <b>bloqueia a thread da requisição</b>, sem {@code DeferredResult}: existe
     * exatamente um agente, então no pior caso uma das ~200 threads do Tomcat fica parada — custo
     * que o {@code DeferredResult} resolveria mas que aqui não existe, cobrando em troca
     * complexidade de dispatch assíncrono e, pior, um caminho de timeout que o MockMvc não
     * executa (ou seja, o "25 s sem comando → 204" ficaria sem teste). Havendo mais de um agente
     * algum dia, reavaliar.
     *
     * @param apps o {@code X-Pc-Apps} com os nomes que o agente aceita em {@code abrir_app};
     *             ausente mantém a lista anterior, presente e vazio zera
     */
    @GetMapping("/comandos")
    public ResponseEntity<?> comandos(
            @RequestHeader(value = "X-Pc-Apps", required = false) String apps) {
        return pc.proximoComando(espera, apps)
                .<ResponseEntity<?>>map(comando -> ResponseEntity.ok(PcDto.de(comando)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** O resultado de um comando. 404 quando ele já venceu ou já foi respondido. */
    @PostMapping("/resultado")
    public ResponseEntity<?> resultado(@RequestBody PcDto.Resultado corpo) {
        if (corpo == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", "Informe o resultado."));
        }
        List<String> erros = corpo.erros();
        if (!erros.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("erro", String.join("; ", erros)));
        }
        return pc.concluir(corpo.paraDominio())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(404).body(Map.of("erro", "comando desconhecido ou ja expirado"));
    }

    /**
     * Faz a tela aberta dizer uma frase do catálogo.
     *
     * <p>O agente chama isto quando o gatilho disparou mas a HUD já estava na frente do usuário:
     * em vez de abrir outra janela, o Jarvis só responde "Já estou aqui, senhor.".
     *
     * <p>409 quando não há HUD conectada — não é erro do agente, é o estado do mundo, e ele usa
     * isso para registrar no log que a fala não chegou a ninguém.
     */
    @PostMapping("/falar")
    public ResponseEntity<?> falar(@RequestBody PcDto.Fala corpo) {
        if (corpo == null || corpo.frase() == null || corpo.frase().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("erro", "Informe a frase."));
        }
        return switch (fala.pedir(corpo.frase())) {
            case FALADA -> ResponseEntity.noContent().build();
            case SEM_TELA -> ResponseEntity.status(409).body(Map.of("erro", "nenhuma tela conectada"));
            case DESCONHECIDA -> ResponseEntity.badRequest()
                    .body(Map.of("erro", "frase desconhecida; aceito " + FalaDaTela.chaves()));
        };
    }
}
