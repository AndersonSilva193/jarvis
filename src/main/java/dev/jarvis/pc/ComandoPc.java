package dev.jarvis.pc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Uma ordem para o agente que roda no PC Windows.
 *
 * O agente nunca recebe caminho de executável nem linha de comando: recebe o <b>nome</b> de uma
 * ação desta lista e, no máximo, um argumento curto. Quem traduz nome em programa é o
 * {@code config.properties} do próprio PC — é isso que impede o modelo de escolher o que executar.
 *
 * @param argumento o complemento da ação, sempre em texto: o nome do app em {@code abrir_app}, a
 *                  URL https em {@code abrir_url}, ou subir/baixar/mudo em {@code volume}. Vazio
 *                  nas outras.
 * @param criadoEm  ISO-8601 em texto, e não um {@code Instant}: o {@code JsonMapper} do
 *                  {@code BeansConfig} é cru, então não dependemos de como ele serializaria data.
 */
public record ComandoPc(String id, String acao, String argumento, String criadoEm, JsonNode dados) {
    public ComandoPc(String id, String acao, String argumento, String criadoEm) {
        this(id, acao, argumento, criadoEm, null);
    }

    /** As ações que o agente entende. O schema da ferramenta expõe exatamente esta lista. */
    public static final List<String> ACOES = List.of(
            "abrir_app", "abrir_url", "abrir_hud", "bloquear_tela", "volume", "captura_tela",
            "status_pc", "desligar", "reiniciar", "cancelar_desligamento",
            "modo_repouso", "falso_positivo", "criar_arquivo", "listar_arquivos", "ler_arquivo",
            "rodar_rotina");

    /** As irreversíveis, que exigem o código de confirmação do {@link GuardaDeConfirmacao}. */
    public static final Set<String> PERIGOSAS = Set.of("desligar", "reiniciar");

    /** As que não fazem nada sem um argumento; nas outras ele é ignorado. */
    public static final Set<String> COM_ARGUMENTO = Set.of("abrir_app", "abrir_url", "volume");

    /** Os valores aceitos em {@code volume}. */
    public static final Set<String> VOLUMES = Set.of("subir", "baixar", "mudo");

    public ComandoPc {
        if (!ACOES.contains(acao)) {
            throw new IllegalArgumentException("ação de PC desconhecida: " + acao);
        }
        argumento = argumento == null ? "" : argumento.trim();
    }

    public static ComandoPc nova(String acao, String argumento, Instant agora) {
        return new ComandoPc(UUID.randomUUID().toString(), acao, argumento, agora.toString());
    }

    public static boolean acaoValida(String acao) {
        return ACOES.contains(acao);
    }

    public static boolean exigeArgumento(String acao) {
        return COM_ARGUMENTO.contains(acao);
    }

    public static boolean perigosa(String acao) {
        return PERIGOSAS.contains(acao);
    }
}
