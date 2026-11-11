package dev.jarvis.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.proativo.AvisosProativos;
import dev.jarvis.proativo.FalasService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code avisar_aprovacoes}: o escritório avisa no Telegram que há itens esperando o dono.
 *
 * <p>O agente escolhe <b>o tipo, de uma lista, e um número</b> — não escreve a mensagem. A frase
 * sai do {@link FalasService}, pela regra de todo aviso que chega sem o dono ter pedido: ele não tem
 * como conferir de onde o texto veio, e um agente que leu uma página maliciosa não pode usar o
 * Telegram do dono como megafone.
 *
 * <p>Passa pelo {@link AvisosProativos}, e herda as três regras de lá: horário de silêncio (acumula
 * para as 7h), sem repetir o mesmo estado (o mesmo número duas vezes não sai de novo) e um destino
 * só. Por cima disso, um aviso por tipo a cada {@link #INTERVALO_MINIMO}: um agente em laço,
 * alternando 3 e 4, não pode fazer o telefone apitar a cada minuto.
 */
@Component
public class AvisarAprovacoesMcp implements FerramentaMcp {

    static final Duration INTERVALO_MINIMO = Duration.ofMinutes(15);
    static final int MAXIMO = 99;

    /** Tipo -> {singular, plural}. A chave é o que o agente manda. */
    static final Map<String, String[]> TIPOS = criarTipos();

    private static Map<String, String[]> criarTipos() {
        Map<String, String[]> tipos = new LinkedHashMap<>();
        tipos.put("abordagens", new String[] {"abordagem", "abordagens"});
        tipos.put("propostas", new String[] {"proposta", "propostas"});
        tipos.put("conteudos", new String[] {"ideia de conteúdo", "ideias de conteúdo"});
        tipos.put("tarefas", new String[] {"tarefa", "tarefas"});
        return tipos;
    }

    private final AvisosProativos avisos;
    private final FalasService falas;
    private final JsonMapper json;
    private final Clock relogio;
    private final Map<String, Instant> ultimoEnvio = new HashMap<>();

    @Autowired
    public AvisarAprovacoesMcp(AvisosProativos avisos, FalasService falas, JsonMapper json) {
        this(avisos, falas, json, Clock.systemUTC());
    }

    public AvisarAprovacoesMcp(AvisosProativos avisos, FalasService falas, JsonMapper json,
            Clock relogio) {
        this.avisos = avisos;
        this.falas = falas;
        this.json = json;
        this.relogio = relogio;
    }

    @Override
    public String nome() {
        return "avisar_aprovacoes";
    }

    @Override
    public String descricao() {
        return "Avisa o dono no Telegram que há itens esperando a aprovação dele no quadro de tarefas "
                + "do escritório. Chame depois de deixar itens em revisão (kanban_request_review). "
                + "Informe o tipo e quantos itens estão esperando agora, no total; a mensagem é "
                + "escrita pelo Jarvis. Respeita o horário de silêncio e não repete o mesmo número.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode esquema = json.createObjectNode().put("type", "object");
        ObjectNode propriedades = esquema.putObject("properties");
        ObjectNode tipo = propriedades.putObject("tipo").put("type", "string")
                .put("description", "O que está esperando aprovação.");
        ArrayNode valores = tipo.putArray("enum");
        for (String chave : TIPOS.keySet()) {
            valores.add(chave);
        }
        propriedades.putObject("quantidade").put("type", "integer").put("minimum", 1)
                .put("maximum", MAXIMO)
                .put("description", "Quantos itens desse tipo estão esperando agora, no total.");
        ArrayNode obrigatorios = esquema.putArray("required");
        obrigatorios.add("tipo");
        obrigatorios.add("quantidade");
        esquema.put("additionalProperties", false);
        return esquema;
    }

    @Override
    public ResultadoMcp executar(JsonNode argumentos) {
        String tipo = Json.texto(argumentos.path("tipo")).trim().toLowerCase(Locale.ROOT);
        String[] rotulos = TIPOS.get(tipo);
        if (rotulos == null) {
            return ResultadoMcp.erro("tipo tem de ser um de " + String.join(", ", TIPOS.keySet()) + ".");
        }
        JsonNode numero = argumentos.path("quantidade");
        if (!numero.isNumber() || numero.doubleValue() != Math.rint(numero.doubleValue())) {
            return ResultadoMcp.erro("quantidade tem de ser um número inteiro.");
        }
        long quantidade = numero.longValue();
        if (quantidade < 1 || quantidade > MAXIMO) {
            return ResultadoMcp.erro("quantidade tem de estar entre 1 e " + MAXIMO + ".");
        }

        Instant agora = relogio.instant();
        synchronized (ultimoEnvio) {
            Instant anterior = ultimoEnvio.get(tipo);
            if (anterior != null && Duration.between(anterior, agora).compareTo(INTERVALO_MINIMO) < 0) {
                long minutos = Math.max(1, INTERVALO_MINIMO.minus(Duration.between(anterior, agora))
                        .toMinutes());
                return ResultadoMcp.ok("Não avisei: já avisei sobre " + tipo + " há pouco. "
                        + "Dá para avisar de novo em " + minutos + " min.");
            }
        }

        String itens = quantidade + " " + (quantidade == 1 ? rotulos[0] : rotulos[1]);
        String texto = falas.frase("escritorio_aprovacoes", Map.of("itens", itens));
        AvisosProativos.Entrega entrega = avisos.avisar("escritorio:aprovacoes:" + tipo,
                String.valueOf(quantidade), texto, null);
        return switch (entrega) {
            case ENVIADO -> {
                marcar(tipo, agora);
                yield ResultadoMcp.ok("Avisei o dono no Telegram: " + texto);
            }
            case ACUMULADO -> {
                marcar(tipo, agora);
                yield ResultadoMcp.ok("É horário de silêncio; o aviso sai quando ele acabar.");
            }
            case REPETIDO -> ResultadoMcp.ok("Não avisei: o dono já sabe desse número.");
            case SEM_DESTINO -> ResultadoMcp.erro("O Jarvis não tem destino no Telegram configurado.");
        };
    }

    private void marcar(String tipo, Instant agora) {
        synchronized (ultimoEnvio) {
            ultimoEnvio.put(tipo, agora);
        }
    }
}
