package dev.jarvis.gatilhos;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A fonte {@link FonteDeGatilho#ARQUIVO_NOVO_NA_ENTRADA}: de {@code GATILHO_ENTRADA_MINUTOS} em
 * {@code GATILHO_ENTRADA_MINUTOS}, com o PC online e alguma regra dessa fonte ativa, lista a
 * subpasta {@code entrada} da pasta de arquivos pelo {@code listar_arquivos} do agente e publica um
 * evento por ID que ainda não tinha visto.
 *
 * <p><b>A primeira conferência é a linha de base</b> e não dispara nada: sem ela, tudo o que já
 * estava na pasta seria "novo". Vale também quando as regras dessa fonte somem e voltam durante o
 * processo — o que chegou no intervalo não era esperado por ninguém. Os IDs vistos ficam no
 * {@code gatilhos.json}, então um restart não reanuncia a pasta inteira, e o que chegou com a VPS
 * fora do ar é anunciado na volta.
 *
 * <p>A resposta tem de dizer {@code "pasta": "entrada"}. Agente antigo, que não conhece o campo,
 * lista a raiz: sem essa conferência cada arquivo da pasta principal viraria "arquivo novo na
 * entrada".
 */
@Component
public class VigiaDaEntrada {

    private static final Logger log = LoggerFactory.getLogger(VigiaDaEntrada.class);
    static final String PASTA = "entrada";
    static final int MAX_PAGINAS = 10;
    static final int POR_PAGINA = 50;

    private final PcService pc;
    private final Gatilhos gatilhos;
    private final MotorDeGatilhos motor;
    private final JsonMapper json;
    private final Clock relogio;
    /** Sem regra desde a última conferência: a próxima com regra refaz a linha de base. */
    private boolean rebasear;
    private boolean avisouAgenteAntigo;

    @Autowired
    public VigiaDaEntrada(PcService pc, Gatilhos gatilhos, MotorDeGatilhos motor, JsonMapper json) {
        this(pc, gatilhos, motor, json, Clock.systemUTC());
    }

    public VigiaDaEntrada(PcService pc, Gatilhos gatilhos, MotorDeGatilhos motor, JsonMapper json, Clock relogio) {
        this.pc = pc;
        this.gatilhos = gatilhos;
        this.motor = motor;
        this.json = json;
        this.relogio = relogio;
    }

    @Scheduled(fixedDelayString = "${jarvis.proativo.gatilhos.entrada-minutos:10}",
            initialDelayString = "${jarvis.proativo.gatilhos.entrada-minutos:10}", timeUnit = TimeUnit.MINUTES)
    public void tique() {
        try {
            conferir();
        } catch (RuntimeException e) {
            log.warn("Vigia da pasta entrada: {}", e.getMessage());
        }
    }

    /** Devolve quantos eventos publicou (para os testes). */
    synchronized int conferir() {
        if (gatilhos.ativas(FonteDeGatilho.ARQUIVO_NOVO_NA_ENTRADA).isEmpty()) {
            rebasear = true;
            return 0;
        }
        if (!pc.online()) return 0;
        Optional<Map<String, JsonNode>> lidos = listar();
        if (lidos.isEmpty()) return 0;
        List<String> ids = new ArrayList<>(lidos.get().keySet());
        if (!gatilhos.entradaInicializada() || rebasear) {
            gatilhos.marcarVistos(ids);
            rebasear = false;
            return 0;
        }
        List<String> novos = gatilhos.naoVistos(ids);
        if (novos.isEmpty()) return 0;
        // Marca antes de publicar: um tique que reencontre o mesmo arquivo não dispara de novo.
        gatilhos.marcarVistos(novos);
        Instant agora = relogio.instant();
        for (String id : novos) {
            JsonNode a = lidos.get().get(id);
            motor.aoEvento(EventoDeGatilho.de(FonteDeGatilho.ARQUIVO_NOVO_NA_ENTRADA, agora,
                    "id", id, "nome", Json.texto(a.path("nome")), "formato", Json.texto(a.path("formato")),
                    "tamanho", String.valueOf(Json.numero(a.path("tamanho")))));
        }
        return novos.size();
    }

    /** Os arquivos da pasta entrada por ID, ou vazio se o PC não respondeu direito. */
    private Optional<Map<String, JsonNode>> listar() {
        Map<String, JsonNode> porId = new LinkedHashMap<>();
        for (int pagina = 1; pagina <= MAX_PAGINAS; pagina++) {
            var pedido = json.createObjectNode().put("pasta", PASTA).put("pagina", pagina)
                    .put("tamanhoPagina", POR_PAGINA);
            ComandoPc comando = new ComandoPc(UUID.randomUUID().toString(), "listar_arquivos", "",
                    relogio.instant().toString(), pedido);
            ResultadoPc resultado;
            try {
                resultado = pc.enfileirar(comando).get(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                pc.cancelar(comando.id());
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception e) {
                pc.cancelar(comando.id());
                log.info("Vigia da pasta entrada: o PC não respondeu ({})", e.getClass().getSimpleName());
                return Optional.empty();
            }
            if (!resultado.ok() || resultado.dados() == null) {
                log.info("Vigia da pasta entrada: {}", resultado.mensagem());
                return Optional.empty();
            }
            JsonNode dados = resultado.dados();
            if (!PASTA.equals(Json.texto(dados.path("pasta")))) {
                if (!avisouAgenteAntigo) {
                    avisouAgenteAntigo = true;
                    log.warn("O agente do PC não conhece a pasta entrada; atualize o jarvis-agente-pc");
                }
                return Optional.empty();
            }
            for (JsonNode a : dados.path("arquivos")) {
                String id = Json.texto(a.path("id"));
                if (!id.isBlank()) porId.put(id, a);
            }
            if (!dados.path("temMais").asBoolean(false)) break;
        }
        return Optional.of(porId);
    }
}
