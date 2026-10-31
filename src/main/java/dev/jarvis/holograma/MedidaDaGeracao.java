package dev.jarvis.holograma;

import java.util.List;

/**
 * Os números de uma geração, os mesmos da linha de log que o {@link HologramaService} escreve no
 * fim de toda geração. É o que o {@link BenchmarkDeHolograma} junta no relatório.
 *
 * @param provedorPedido   {@code local} ou {@code anthropic}: o que o benchmark mandou medir
 * @param provedor         o que de fato respondeu; vazio quando nenhum chegou a ser chamado
 * @param primeiraPecaMs   do pedido até a primeira peça sair; {@code null} quando nenhuma saiu
 * @param linhas           linhas de peça que o modelo escreveu e o servidor aceitou (sem a meta);
 *                         {@code pecas} conta as cópias de {@code rep}/{@code rad} já expandidas
 * @param descartadas      linhas que não viraram nada: texto solto, JSON quebrado, linha cortada
 * @param status           {@code completo} ou {@code incompleto}, como no {@code fim}
 * @param motivo           por que ficou incompleto (a frase do {@code fim}); vazio quando completo
 * @param indisponivel     o motivo da {@code DisponibilidadeLocal} quando o local nem foi tentado
 * @param fim              o fim interno da geração ({@code modelo}, {@code incompleto}, {@code erro},
 *                         {@code limite}, {@code tempo}...), o mesmo {@code fim=} do log
 * @param exemplosDescartados até cinco linhas descartadas, cortadas, para o relatório mostrar o erro
 * @param ndjson           as linhas que o servidor aceitou, como o modelo as escreveu (antes de
 *                         expandir {@code rep}/{@code rad}): é o que se lê para ajustar o prompt
 */
public record MedidaDaGeracao(String objeto, String detalhe, String provedorPedido, String provedor,
        String modelo, Long primeiraPecaMs, long totalMs, int pecas, int linhas, int chamadas,
        int descartadas, int repetidas, int avisos, Long tokensEntrada, Long tokensSaida, Double custoUsd,
        String status, String motivo, String indisponivel, String fim, List<String> exemplosDescartados,
        String ndjson) {

    public boolean completo() {
        return SaidaHolograma.COMPLETO.equals(status);
    }
}
