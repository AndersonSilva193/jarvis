package dev.jarvis.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import dev.jarvis.holograma.controle.BilhetesDoCanal;
import dev.jarvis.holograma.controle.LimiteDeTaxa;
import dev.jarvis.holograma.controle.PareamentosDoControle;
import dev.jarvis.holograma.controle.PareamentosDoControle.Papel;
import dev.jarvis.holograma.controle.PareamentosDoControle.Recusa;
import dev.jarvis.holograma.controle.PareamentosDoControle.Resultado;
import dev.jarvis.holograma.controle.ValidadorDeMensagemDoControle;
import dev.jarvis.holograma.controle.ValidadorDeMensagemDoControle.Quadro;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code /ws/holograma}: o celular manda gestos já interpretados e este handler repassa à HUD do PC
 * que gerou o código do QR. Contrato na seção 11 do {@code docs/HOLOGRAMA.md}.
 *
 * <p>Quem é quem sai da query do handshake (o {@link CanalDoHologramaConfig} só copia): {@code
 * bilhete} é a HUD (com {@code retomada}, a HUD que voltou), {@code par} é o celular entrando pelo
 * QR e {@code retomada} sozinha é o celular voltando. A conferência é aqui, depois do handshake,
 * porque só com a conexão aberta dá para fechar com um código que a tela sabe explicar — um 403 no
 * handshake chega ao navegador como erro genérico.
 *
 * <p>Cada sessão vai embrulhada num {@link ConcurrentWebSocketSessionDecorator}: o repasse escreve
 * na sessão do outro lado a partir da thread desta, e uma HUD lenta descarta em vez de segurar o
 * celular.
 */
@Component
public class CanalDoHologramaHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CanalDoHologramaHandler.class);

    static final CloseStatus CREDENCIAL_INVALIDA = new CloseStatus(4401, "credencial invalida ou expirada");
    static final CloseStatus SEM_HUD = new CloseStatus(4404, "a HUD nao esta conectada");
    static final CloseStatus OCUPADO = new CloseStatus(4409, "outro celular ja esta conectado");
    static final CloseStatus DESCONECTADO = new CloseStatus(4000, "desconectado");
    private static final int LIMITE_DE_ENVIO_MS = 1_000;
    private static final int BUFFER_DE_ENVIO = 16 * 1024;

    private record Sessao(WebSocketSession ws, Papel papel, LimiteDeTaxa limite) { }

    private final BilhetesDoCanal bilhetes;
    private final PareamentosDoControle pareamentos;
    private final ValidadorDeMensagemDoControle validador;
    private final JsonMapper json;
    private final Map<String, Sessao> sessoes = new ConcurrentHashMap<>();

    public CanalDoHologramaHandler(BilhetesDoCanal bilhetes, PareamentosDoControle pareamentos,
            ValidadorDeMensagemDoControle validador, JsonMapper json) {
        this.bilhetes = bilhetes;
        this.pareamentos = pareamentos;
        this.validador = validador;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession bruta) throws IOException {
        // Mensagem do canal é curta: o contêiner recusa (1009) o que passar disso antes de montar a
        // string. Por sessão, e não no ServletServerContainerFactoryBean, que exige o contêiner
        // de verdade e derrubaria o contexto dos testes sem servidor.
        bruta.setTextMessageSizeLimit(ValidadorDeMensagemDoControle.MAX_CARACTERES * 4);
        bruta.setBinaryMessageSizeLimit(1024);
        WebSocketSession ws = new ConcurrentWebSocketSessionDecorator(bruta, LIMITE_DE_ENVIO_MS, BUFFER_DE_ENVIO,
                ConcurrentWebSocketSessionDecorator.OverflowStrategy.DROP);
        String bilhete = atributo(bruta, "bilhete");
        String par = atributo(bruta, "par");
        String retomada = atributo(bruta, "retomada");
        if (bilhete != null) {
            entrarComoHud(ws, bilhete, retomada);
        } else if (par != null) {
            entrarComoControle(ws, pareamentos.entrarComCodigo(par, ws.getId()));
        } else if (retomada != null) {
            entrarComoControle(ws, pareamentos.retomar(Papel.CONTROLE, retomada, ws.getId()));
        } else {
            ws.close(CREDENCIAL_INVALIDA);
        }
    }

    private void entrarComoHud(WebSocketSession ws, String bilhete, String retomada) throws IOException {
        if (!bilhetes.consumir(bilhete)) {
            ws.close(CREDENCIAL_INVALIDA);
            return;
        }
        sessoes.put(ws.getId(), new Sessao(ws, Papel.HUD, new LimiteDeTaxa()));
        if (retomada == null) {
            log.info("Canal do holograma: HUD conectada");
            return;
        }
        Resultado resultado = pareamentos.retomar(Papel.HUD, retomada, ws.getId());
        if (resultado.entrada().isEmpty()) {
            // O par acabou enquanto a HUD estava fora: ela abre como HUD nova e fica sabendo.
            enviar(ws.getId(), mensagem("controle", "encerrado"));
            return;
        }
        Optional<String> celular = resultado.entrada().get().outroLado();
        log.info("Canal do holograma: HUD voltou ({})", celular.isPresent() ? "celular presente" : "celular fora");
        enviar(ws.getId(), mensagem("controle", celular.isPresent() ? "voltou" : "caiu"));
        celular.ifPresent(id -> enviar(id, mensagem("hud", "voltou")));
    }

    private void entrarComoControle(WebSocketSession ws, Resultado resultado) throws IOException {
        if (resultado.recusa().isPresent()) {
            Recusa recusa = resultado.recusa().get();
            log.info("Canal do holograma: celular recusado ({})", recusa);
            ws.close(switch (recusa) {
                case INVALIDO -> CREDENCIAL_INVALIDA;
                case OCUPADO -> OCUPADO;
            });
            return;
        }
        var entrada = resultado.entrada().orElseThrow();
        sessoes.put(ws.getId(), new Sessao(ws, Papel.CONTROLE, new LimiteDeTaxa()));
        enviar(ws.getId(), json.createObjectNode().put("t", "pareado").put("retomada", entrada.retomada()));
        Optional<String> hud = entrada.outroLado();
        if (hud.isEmpty()) {
            enviar(ws.getId(), mensagem("hud", "caiu"));
            return;
        }
        log.info("Canal do holograma: celular {}", entrada.retomou() ? "voltou" : "conectado");
        ObjectNode aviso = mensagem("controle", entrada.retomou() ? "voltou" : "conectado");
        pareamentos.retomadaDaHud(hud.get()).ifPresent(chave -> aviso.put("retomada", chave));
        enviar(hud.get(), aviso);
    }

    @Override
    protected void handleTextMessage(WebSocketSession bruta, TextMessage mensagem) throws IOException {
        Sessao sessao = sessoes.get(bruta.getId());
        if (sessao == null) {
            return;
        }
        if (!sessao.limite().permitir()) {
            fecharSeEstourou(sessao);
            return;
        }
        if (sessao.papel() == Papel.CONTROLE) {
            doControle(sessao, mensagem.getPayload());
        } else {
            daHud(sessao, mensagem.getPayload());
        }
    }

    private void doControle(Sessao sessao, String texto) throws IOException {
        Optional<Quadro> quadro = validador.doControle(texto);
        if (quadro.isEmpty()) {
            sessao.limite().descartar();
            fecharSeEstourou(sessao);
            return;
        }
        quadro.get().latencia().ifPresent(l -> log.info(
                "Controle do holograma: ida e volta celular → VPS → HUD → VPS → celular p50={} ms p95={} ms ({} amostras)",
                Math.round(l.p50()), Math.round(l.p95()), l.amostras()));
        List<ObjectNode> eventos = quadro.get().eventos();
        if (eventos.isEmpty()) {
            return;
        }
        Optional<String> hud = pareamentos.outroLado(sessao.ws().getId());
        if (hud.isEmpty()) {
            return;
        }
        if (eventos.size() == 1) {
            enviar(hud.get(), eventos.getFirst());
        } else {
            ArrayNode lista = json.createArrayNode();
            eventos.forEach(lista::add);
            enviar(hud.get(), lista);
        }
    }

    private void daHud(Sessao sessao, String texto) throws IOException {
        Optional<ObjectNode> lida = validador.daHud(texto);
        if (lida.isEmpty()) {
            sessao.limite().descartar();
            fecharSeEstourou(sessao);
            return;
        }
        String id = sessao.ws().getId();
        ObjectNode m = lida.get();
        switch (m.path("t").stringValue()) {
            case "parear" -> enviar(id, pareamentos.gerarCodigo(id)
                    .map(codigo -> json.createObjectNode().put("t", "par").put("codigo", codigo)
                            .put("expira", PareamentosDoControle.VALIDADE_DO_CODIGO.toSeconds()))
                    .orElseGet(() -> json.createObjectNode().put("t", "erro").put("e", "ocupado")));
            case "desconectar" -> pareamentos.encerrar(id).ifPresent(fim -> {
                log.info("Canal do holograma: a HUD desconectou o celular");
                enviar(id, mensagem("controle", "encerrado"));
                fim.controle().ifPresent(celular -> fechar(celular, DESCONECTADO));
            });
            case "eco" -> pareamentos.outroLado(id).ifPresent(celular -> enviar(celular, m));
            default -> { }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession bruta, CloseStatus status) {
        Sessao sessao = sessoes.remove(bruta.getId());
        if (sessao == null) {
            return;
        }
        if (sessao.limite().descartes() > 0) {
            log.info("Canal do holograma: {} saiu com {} mensagem(ns) descartada(s)",
                    sessao.papel() == Papel.HUD ? "HUD" : "celular", sessao.limite().descartes());
        }
        // 1000 do celular é o "Sair": o par acaba já. Qualquer outro fechamento é queda, e o par
        // espera a volta pela tolerância.
        if (sessao.papel() == Papel.CONTROLE && status.getCode() == CloseStatus.NORMAL.getCode()) {
            pareamentos.encerrar(bruta.getId()).ifPresent(fim -> {
                log.info("Canal do holograma: o celular saiu");
                fim.hud().ifPresent(hud -> enviar(hud, mensagem("controle", "encerrado")));
            });
            return;
        }
        pareamentos.caiu(bruta.getId()).ifPresent(aviso -> {
            log.info("Canal do holograma: {} caiu ({})", aviso.quemCaiu() == Papel.HUD ? "HUD" : "celular",
                    status.getCode());
            aviso.outroLado().ifPresent(outro ->
                    enviar(outro, mensagem(aviso.quemCaiu() == Papel.HUD ? "hud" : "controle", "caiu")));
        });
    }

    /** Pares que esperaram a volta além da tolerância acabam; o celular sem HUD é fechado. */
    @Scheduled(fixedRate = 5_000)
    public void encerrarVencidos() {
        for (var fim : pareamentos.vencidos()) {
            log.info("Canal do holograma: o par acabou depois de {} s sem um dos lados",
                    PareamentosDoControle.TOLERANCIA_DE_QUEDA.toSeconds());
            fim.hud().ifPresent(hud -> enviar(hud, mensagem("controle", "encerrado")));
            fim.controle().ifPresent(celular -> {
                enviar(celular, mensagem("hud", "encerrado"));
                fechar(celular, SEM_HUD);
            });
        }
    }

    /** Ping do protocolo: atravessa o nginx e revela a conexão morta sem virar mensagem na tela. */
    @Scheduled(fixedRate = 25_000)
    public void manterVivo() {
        for (Sessao sessao : sessoes.values()) {
            try {
                sessao.ws().sendMessage(new PingMessage());
            } catch (IOException | IllegalStateException e) {
                log.debug("Ping do canal do holograma falhou: {}", e.getMessage());
            }
        }
    }

    /** Quantas sessões estão abertas (para o teste). */
    int sessoesAbertas() {
        return sessoes.size();
    }

    private void fecharSeEstourou(Sessao sessao) {
        if (sessao.limite().estourou()) {
            log.warn("Canal do holograma: {} fechado por mensagens fora do formato ou da taxa",
                    sessao.papel() == Papel.HUD ? "HUD" : "celular");
            fechar(sessao.ws().getId(), CloseStatus.POLICY_VIOLATION);
        }
    }

    private ObjectNode mensagem(String tipo, String estado) {
        return json.createObjectNode().put("t", tipo).put("e", estado);
    }

    private void enviar(String id, tools.jackson.databind.JsonNode mensagem) {
        Sessao sessao = sessoes.get(id);
        if (sessao == null) {
            return;
        }
        try {
            sessao.ws().sendMessage(new TextMessage(json.writeValueAsString(mensagem)));
        } catch (IOException | IllegalStateException e) {
            // A sessão fechou no meio: o afterConnectionClosed dela cuida do resto.
            log.debug("Canal do holograma: envio falhou: {}", e.getMessage());
        }
    }

    private void fechar(String id, CloseStatus status) {
        Sessao sessao = sessoes.get(id);
        if (sessao == null) {
            return;
        }
        try {
            sessao.ws().close(status);
        } catch (IOException e) {
            log.debug("Canal do holograma: fechamento falhou: {}", e.getMessage());
        }
    }

    private static String atributo(WebSocketSession sessao, String nome) {
        return sessao.getAttributes().get(nome) instanceof String valor && !valor.isEmpty() ? valor : null;
    }
}
