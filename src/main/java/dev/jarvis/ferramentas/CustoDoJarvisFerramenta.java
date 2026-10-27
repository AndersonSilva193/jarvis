package dev.jarvis.ferramentas;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import org.springframework.stereotype.Component;

import dev.jarvis.custo.Dinheiro;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.custo.Origem;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Quanto o Jarvis gastou com os modelos — hoje, na semana ou no mês. */
@Component
public class CustoDoJarvisFerramenta implements Ferramenta {

    /** Quantos modelos e origens cabem na resposta antes de ela virar leitura de planilha. */
    private static final int MAX_LINHAS = 4;

    private final MedidorDeCusto medidor;
    private final JsonMapper json;

    public CustoDoJarvisFerramenta(MedidorDeCusto medidor, JsonMapper json) {
        this.medidor = medidor;
        this.json = json;
    }

    @Override
    public String nome() {
        return "custo_do_jarvis";
    }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.CUSTO; }
    @Override public boolean simples() { return true; }

    @Override
    public String descricao() {
        return "Diz quanto o Jarvis gastou em dólares com os modelos de IA — hoje, na semana ou no "
                + "mês —, com a divisão por origem (conversa, briefing, inicialização, anotador, "
                + "classificador, computer use) e por modelo. Mostra as 10 ferramentas mais usadas. "
                + "Use em pedidos como 'quanto você "
                + "gastou hoje', 'e na semana?', 'quanto custou esse mês', 'quanto estou gastando "
                + "com você'. Não serve para a cota de voz da ElevenLabs: essa é a status_voz.";
    }

    /**
     * Um {@code enum} de três períodos, e não um número de dias.
     *
     * É a regra de {@code verificar_site}: o modelo escolhe de uma lista fechada. Um inteiro livre
     * o convidaria a pedir 365 dias, e aí a ferramenta leria 365 arquivos no caminho crítico de um
     * turno — para responder a uma pergunta de três palavras.
     */
    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        ObjectNode periodo = esquema.putObject("properties").putObject("periodo");
        periodo.put("type", "string");
        periodo.put("description",
                "hoje (padrão), semana = últimos 7 dias, mes = últimos 30 dias.");
        periodo.putArray("enum").add("hoje").add("semana").add("mes");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) {
        String periodo = entrada == null ? "" : Json.texto(entrada.path("periodo")).trim();
        int dias = switch (periodo) {
            case "semana" -> 7;
            case "mes" -> 30;
            default -> 1;
        };

        MedidorDeCusto.Relatorio relatorio = medidor.periodo(dias);
        String titulo = switch (dias) {
            case 7 -> "Últimos 7 dias";
            case 30 -> "Últimos 30 dias";
            default -> "Hoje";
        };
        if (relatorio.vazio()) {
            return comFerramentas(dias == 1 ? "Hoje ainda não gastei nada com modelo."
                    : titulo + ": não gastei nada com modelo.", relatorio);
        }

        StringBuilder texto = new StringBuilder(titulo + ": " + Dinheiro.usd(relatorio.totalUsd()));
        if (dias > 1) {
            texto.append(" (média ").append(Dinheiro.usd(relatorio.totalUsd() / dias))
                    .append("/dia)");
        }
        texto.append('.');

        List<String> origens = new ArrayList<>();
        relatorio.porOrigem().forEach((origem, valor) -> origens.add(rotulo(origem, valor)));
        acrescentar(texto, " Por origem: ", origens);

        List<String> modelos = new ArrayList<>();
        relatorio.porModelo().forEach((modelo, valor) -> modelos.add(rotulo(modelo, valor)));
        acrescentar(texto, " Por modelo: ", modelos);

        if (relatorio.buscasWeb() > 0) {
            texto.append(' ').append(relatorio.buscasWeb()).append(" busca")
                    .append(relatorio.buscasWeb() == 1 ? "" : "s").append(" na web.");
        }
        // Quanto da entrada foi lida do cache, a 0,1x do preço, em vez de cobrada cheia. Sem token
        // de entrada no período o método devolve -1 e a frase não sai: "cache: 0% lido" num dia sem
        // chamada seria ruído, e isto é lido em voz alta.
        int cache = relatorio.percentualLidoDoCache();
        if (cache >= 0) {
            texto.append(" Cache: ").append(cache).append("% lido.");
        }
        if (dias == 1 && relatorio.tetoUsd() > 0) {
            texto.append(" Teto diário ").append(Dinheiro.usd(relatorio.tetoUsd())).append(" — ")
                    .append(relatorio.percentualDoTeto()).append("% usado.");
        }
        if (!relatorio.modelosSemPreco().isEmpty()) {
            texto.append(" Sem preço configurado em CUSTO_PRECOS para ")
                    .append(String.join(", ", relatorio.modelosSemPreco()))
                    .append(": os tokens desse(s) modelo(s) ficaram fora da conta.");
        }
        return comFerramentas(texto.toString(), relatorio);
    }

    private static String comFerramentas(String texto, MedidorDeCusto.Relatorio relatorio) {
        if (relatorio.ferramentasMaisUsadas().isEmpty()) return texto;
        StringJoiner usos = new StringJoiner("; ");
        relatorio.ferramentasMaisUsadas().forEach((nome, chamadas) ->
                usos.add(nome + " " + chamadas));
        return texto + " Ferramentas mais usadas (desde a contagem): " + usos + ".";
    }

    /** Balde que não custou nada fica de fora: ele só gastaria token para dizer "zero". */
    private static String rotulo(Origem origem, double valor) {
        return valor <= 0 ? "" : origem.chave() + " " + Dinheiro.numero(valor);
    }

    private static String rotulo(String modelo, double valor) {
        return valor <= 0 ? "" : modelo + " " + Dinheiro.numero(valor);
    }

    /** Até {@link #MAX_LINHAS} itens; o resto vira "e mais N". Tudo isso é lido em voz alta. */
    private static void acrescentar(StringBuilder texto, String prefixo, List<String> itens) {
        List<String> uteis = itens.stream().filter(item -> !item.isEmpty()).toList();
        if (uteis.isEmpty()) {
            return;
        }
        StringJoiner lista = new StringJoiner("; ");
        uteis.stream().limit(MAX_LINHAS).forEach(lista::add);
        texto.append(prefixo).append(lista);
        int sobrando = uteis.size() - MAX_LINHAS;
        if (sobrando > 0) {
            texto.append("; e mais ").append(sobrando);
        }
        texto.append('.');
    }
}
