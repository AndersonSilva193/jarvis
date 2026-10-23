package dev.jarvis.ferramentas;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.tela.CardTela;
import dev.jarvis.tela.MontadorDeCardDePesquisa;
import dev.jarvis.tela.PresencaDaHud;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Abre na HUD um card com o resultado de uma pesquisa, em formato estruturado.
 *
 * O servidor valida tudo o que o modelo manda e preenche o "consultado em" com o próprio relógio.
 */
@Component
public class MostrarNaTelaFerramenta extends FerramentaDeTela {

    private static final DateTimeFormatter QUANDO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final JarvisProperties props;

    public MostrarNaTelaFerramenta(PresencaDaHud hud, JsonMapper json, JarvisProperties props) {
        super(hud, json);
        this.props = props;
    }

    @Override
    public String nome() {
        return "mostrar_na_tela";
    }

    @Override
    public String descricao() {
        return """
                Abre na tela do Jarvis um card visual com o resultado de uma pesquisa.

                Use SÓ quando o resultado tem estrutura que vale olhar: comparação, ranking, \
                números, ficha de dados ou sequência de datas. E use sempre DEPOIS de pesquisar \
                na web, com os dados que a busca trouxe — nunca de memória. Resposta simples, de \
                uma ou duas frases, é só falada: não abra card para ela.

                O 'titulo' é a CONCLUSÃO, não o assunto: "Flamengo lidera com 12 pontos de \
                vantagem", não "Tabela do Brasileirão". O 'resumo' é exatamente o texto que você \
                vai falar em voz alta, para a tela e a voz não divergirem.

                O layout 'grafico' é para números que mudam ao longo de um eixo — dias, meses, \
                rodadas. Use SÓ com números que vieram de uma pesquisa feita NESTE pedido, e aí \
                o titulo também é a conclusão: "A temperatura cai 8 °C até quinta", não \
                "Previsão da semana". Para dados do próprio Jarvis ou de planilhas, use \
                grafico_de_dados, não esta ferramenta.

                Se o contexto não traz a linha "Na tela agora", nenhuma HUD está aberta e esta \
                ferramenta não vai abrir nada — responda apenas falando.""";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        ObjectNode campos = esquema.putObject("properties");

        campos.putObject("categoria").put("type", "string")
                .put("description", "Que tipo de informação é: esporte, economia, tecnologia, política...");
        campos.putObject("tema").put("type", "string")
                .put("description", "O assunto da pesquisa, curto. Vira o subtítulo do card.");
        campos.putObject("titulo").put("type", "string")
                .put("description", "A CONCLUSÃO em uma frase, não o assunto. "
                        + "Ex.: 'Dólar fecha a R$ 5,12, menor valor do ano'.");
        campos.putObject("referencia").put("type", "string")
                .put("description", "De quando é a informação, como a fonte diz: "
                        + "'13/10/2026', 'outubro de 2026', 'última rodada'.");

        ObjectNode layout = campos.putObject("layout");
        layout.put("type", "string");
        layout.put("description", "O desenho do card. ranking para comparar itens em ordem; "
                + "ficha para pares rótulo/valor; lista para pontos soltos; "
                + "linha_do_tempo para datas em sequência; destaque para um número só; "
                + "grafico para números ao longo de um eixo (manda 'grafico' em vez de 'itens').");
        MontadorDeCardDePesquisa.LAYOUTS.forEach(layout.putArray("enum")::add);

        ObjectNode itens = campos.putObject("itens");
        itens.put("type", "array");
        itens.put("maxItems", MontadorDeCardDePesquisa.MAX_ITENS);
        itens.put("description", """
                Os itens, no máximo 8, com os campos do layout escolhido:
                ranking -> posicao (número), nome, detalhe, valor (número), unidade ("%", "R$", "pts"), etiqueta;
                ficha -> rotulo, valor;
                lista -> texto;
                linha_do_tempo -> data, texto;
                destaque -> valor, legenda.
                Com unidade "%", o valor tem de ficar entre 0 e 100.
                No layout grafico não mande itens: mande o objeto 'grafico'.""");
        itens.putObject("items").put("type", "object");

        campos.set("grafico", esquemaDoGrafico());

        campos.putObject("resumo").put("type", "string")
                .put("description", "O mesmo texto que você vai falar em voz alta sobre isto.");

        ObjectNode fontes = campos.putObject("fontes");
        fontes.put("type", "array");
        fontes.put("maxItems", MontadorDeCardDePesquisa.MAX_FONTES);
        fontes.put("description", "De onde veio a informação: nome e url (http ou https).");
        ObjectNode fonte = fontes.putObject("items");
        fonte.put("type", "object");
        ObjectNode camposDaFonte = fonte.putObject("properties");
        camposDaFonte.putObject("nome").put("type", "string");
        camposDaFonte.putObject("url").put("type", "string");

        // 'itens' não entra aqui porque o layout grafico não tem itens; quem cobra um ou outro,
        // conforme o layout, é o MontadorDeCardDePesquisa.
        esquema.putArray("required")
                .add("categoria").add("tema").add("titulo").add("layout").add("resumo");
        return esquema;
    }

    /**
     * O objeto do layout 'grafico'.
     *
     * A 'origem' de propósito não aparece: o modelo só pode mandar gráfico de pesquisa, e marcar
     * um número como vindo do próprio Jarvis é trabalho do Java.
     */
    private ObjectNode esquemaDoGrafico() {
        ObjectNode grafico = json.createObjectNode();
        grafico.put("type", "object");
        grafico.put("description", "Só no layout grafico, no lugar de 'itens'. Exige 'referencia' "
                + "e ao menos uma fonte: são números pesquisados.");
        ObjectNode campos = grafico.putObject("properties");

        ObjectNode tipo = campos.putObject("tipo");
        tipo.put("type", "string");
        tipo.put("description", "linha para o que anda no tempo (2 a "
                + MontadorDeCardDePesquisa.MAX_PONTOS_LINHA + " pontos); "
                + "barras para comparar categorias (até " + MontadorDeCardDePesquisa.MAX_BARRAS + ").");
        MontadorDeCardDePesquisa.TIPOS_GRAFICO.forEach(tipo.putArray("enum")::add);

        ObjectNode eixoX = campos.putObject("eixoX");
        eixoX.put("type", "array");
        eixoX.put("description", "Os rótulos do eixo, na ordem: 'seg', 'ter'... ou 'jan', 'fev'...");
        eixoX.putObject("items").put("type", "string");

        ObjectNode series = campos.putObject("series");
        series.put("type", "array");
        series.put("maxItems", MontadorDeCardDePesquisa.MAX_SERIES);
        series.put("description", "Até " + MontadorDeCardDePesquisa.MAX_SERIES
                + " séries. Cada uma com 'nome' e 'valores' — um número por rótulo do eixoX, "
                + "na mesma ordem e na mesma quantidade.");
        ObjectNode serie = series.putObject("items");
        serie.put("type", "object");
        ObjectNode camposDaSerie = serie.putObject("properties");
        camposDaSerie.putObject("nome").put("type", "string");
        ObjectNode valores = camposDaSerie.putObject("valores");
        valores.put("type", "array");
        valores.putObject("items").put("type", "number");

        campos.putObject("unidade").put("type", "string")
                .put("description", "O que os valores significam: '°C', 'R$', '%'. Até "
                        + "10 caracteres.");

        ObjectNode destaque = campos.putObject("destaque");
        destaque.put("type", "object");
        destaque.put("description", "Opcional: o ponto a realçar. 'serie' e 'indice' contam do "
                + "zero.");
        ObjectNode camposDoDestaque = destaque.putObject("properties");
        camposDoDestaque.putObject("serie").put("type", "integer");
        camposDoDestaque.putObject("indice").put("type", "integer");

        grafico.putArray("required").add("tipo").add("eixoX").add("series");
        return grafico;
    }

    @Override
    public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso) {
        if (telaFechada()) {
            return ResultadoFerramenta.texto(SEM_TELA);
        }
        // A hora é a do servidor: "quando" é o dado que o modelo mais erra, e é o que a tela
        // mostra em letras pequenas embaixo do card.
        String consultadoEm = ZonedDateTime.now(props.zona()).format(QUANDO);
        CardTela card = MontadorDeCardDePesquisa.montar(entrada, origem(progresso), consultadoEm);
        return ResultadoFerramenta.comCard(
                "Card na tela (id " + card.id() + "). Diga só uma frase curta; o resto já está à vista.",
                card);
    }
}
