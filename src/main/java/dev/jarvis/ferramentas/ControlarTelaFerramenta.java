package dev.jarvis.ferramentas;

import java.util.Optional;

import org.springframework.stereotype.Component;

import dev.jarvis.briefing.BriefingService;
import dev.jarvis.briefing.DadosSecao;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.tela.CardTela;
import dev.jarvis.tela.ComandoTela;
import dev.jarvis.tela.DadosDoCard;
import dev.jarvis.tela.PresencaDaHud;
import dev.jarvis.tela.TelaEstadoService;
import dev.jarvis.util.Json;
import dev.jarvis.util.Texto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Comanda o que já está na tela: fechar, minimizar, fixar, pausar, mudar de volume e por aí.
 *
 * O alvo é conferido contra o último estado que a HUD mandou. Id que não está lá não gera ordem
 * nenhuma: o que volta ao modelo é a lista do que está aberto, para ele acertar na segunda.
 */
@Component
public class ControlarTelaFerramenta extends FerramentaDeTela {

    private final TelaEstadoService tela;
    private final BriefingService briefing;

    public ControlarTelaFerramenta(PresencaDaHud hud, JsonMapper json, TelaEstadoService tela,
            BriefingService briefing) {
        super(hud, json);
        this.tela = tela;
        this.briefing = briefing;
    }

    @Override
    public String nome() {
        return "controlar_tela";
    }

    @Override
    public String descricao() {
        return """
                Comanda a tela do Jarvis e as janelas que já estão abertas nela. Os ids das janelas \
                aparecem no contexto, na linha "Na tela agora"; use só esses ids, nunca um inventado.

                As ações: fechar, minimizar, maximizar, restaurar, focar, fixar, desafixar, pausar, \
                continuar, volume_mais, volume_menos, avancar e voltar precisam de 'alvo' com o id \
                de uma janela. fechar_tudo fecha todos os cards; limpar fecha os cards não fixados e devolve \
                a tela ao repouso (fecha o histórico e encerra o briefing, preservando cards fixados). abrir_historico e \
                fechar_historico mostram e escondem o histórico da conversa. mostrar_briefing e \
                repetir_briefing trabalham com o briefing que já está na HUD — se nenhum foi gerado \
                hoje, chame briefing_do_dia primeiro. abrir_noticia põe na tela uma manchete do \
                último briefing: ponha em 'valor' o título ou o tema procurado.

                Em avancar e voltar, 'valor' são os segundos (ex.: "30").""";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        ObjectNode campos = esquema.putObject("properties");

        ObjectNode acao = campos.putObject("acao");
        acao.put("type", "string");
        acao.put("description", "O que fazer na tela.");
        ComandoTela.ACOES.forEach(acao.putArray("enum")::add);

        campos.putObject("alvo").put("type", "string")
                .put("description", "O id da janela, como aparece entre colchetes na linha "
                        + "\"Na tela agora\". Obrigatório nas ações que mexem em uma janela.");
        campos.putObject("valor").put("type", "string")
                .put("description", "O complemento da ação: os segundos em avancar e voltar, ou o "
                        + "título procurado em abrir_noticia.");

        esquema.putArray("required").add("acao");
        return esquema;
    }

    @Override
    public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso) {
        if (telaFechada()) {
            return ResultadoFerramenta.texto(SEM_TELA);
        }
        String acao = Json.texto(entrada.path("acao")).trim();
        if (!ComandoTela.acaoValida(acao)) {
            return ResultadoFerramenta.texto(
                    "Não conheço a ação '" + acao + "'. As válidas são: " + ComandoTela.ACOES + ".");
        }
        String alvo = Json.texto(entrada.path("alvo")).trim();
        String valor = Json.texto(entrada.path("valor")).trim();

        if (ComandoTela.exigeAlvo(acao)) {
            if (alvo.isEmpty()) {
                return ResultadoFerramenta.texto("A ação '" + acao + "' precisa do id de uma janela. "
                        + tela.listaParaOModelo());
            }
            if (tela.janela(alvo).isEmpty()) {
                return ResultadoFerramenta.texto(tela.listaParaOModelo());
            }
        }

        if ("abrir_noticia".equals(acao)) {
            return abrirNoticia(valor, progresso);
        }
        return ResultadoFerramenta.comComando(fala(acao, alvo), new ComandoTela(acao, alvo, valor));
    }

    /**
     * Põe na tela uma manchete do último briefing.
     *
     * A procura é sem acento e sem caixa porque o modelo repete o título como o ouviu — "eleicao
     * 2026" tem de achar "Eleição 2026". Não achando, dizemos isso: inventar notícia é pior do que
     * oferecer uma busca na web.
     */
    private ResultadoFerramenta abrirNoticia(String procurado, OuvinteProgresso progresso) {
        if (procurado.isEmpty()) {
            return ResultadoFerramenta.texto(
                    "Preciso do título ou do tema da notícia em 'valor'.");
        }
        Optional<DadosSecao.Noticias.Item> achada = procurar(procurado);
        if (achada.isEmpty()) {
            return ResultadoFerramenta.texto("Não achei essa notícia no briefing de hoje. "
                    + "Ofereça procurar na web.");
        }
        DadosSecao.Noticias.Item item = achada.get();
        CardTela card = CardTela.agora(CardTela.NOTICIA, item.titulo(),
                new DadosDoCard.Noticia(item.titulo(), item.tema(), item.fonte(), item.link(),
                        item.imagemUrl()),
                origem(progresso));
        return ResultadoFerramenta.comCard(
                "Notícia na tela (id " + card.id() + "). Diga só uma frase curta.", card);
    }

    private Optional<DadosSecao.Noticias.Item> procurar(String procurado) {
        var ultimo = briefing.ultimo();
        if (ultimo.isEmpty()) {
            return Optional.empty();
        }
        // Título primeiro: o tema ("tecnologia") casaria com várias manchetes de uma vez.
        for (boolean porTitulo : new boolean[] { true, false }) {
            for (var secao : ultimo.get().secoes()) {
                if (!(secao.dados() instanceof DadosSecao.Noticias noticias)) {
                    continue;
                }
                for (DadosSecao.Noticias.Item item : noticias.itens()) {
                    String onde = porTitulo ? item.titulo() : item.tema();
                    if (Texto.casa(procurado, onde)) {
                        return Optional.of(item);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Uma frase curta por ação: a HUD é falada, e o resto já está à vista na tela. */
    private static String fala(String acao, String alvo) {
        String curta = switch (acao) {
            case "limpar" -> "Tela limpa.";
            case "fechar_tudo" -> "Todos os cards fechados.";
            case "fechar" -> "Card fechado.";
            case "minimizar" -> "Card minimizado.";
            case "maximizar" -> "Card maximizado.";
            case "restaurar" -> "Card restaurado.";
            case "focar" -> "Card em foco.";
            case "fixar" -> "Card fixado.";
            case "desafixar" -> "Card desafixado.";
            case "abrir_historico" -> "Histórico aberto.";
            case "fechar_historico" -> "Histórico fechado.";
            case "mostrar_briefing" -> "Briefing na tela. Se nenhum foi gerado hoje, chame briefing_do_dia.";
            case "repetir_briefing" -> "Repetindo o briefing.";
            case "pausar" -> "Pausado.";
            case "continuar" -> "Continuando.";
            case "volume_mais" -> "Volume aumentado.";
            case "volume_menos" -> "Volume reduzido.";
            case "avancar" -> "Avancei.";
            case "voltar" -> "Voltei.";
            default -> "Feito.";
        };
        return curta + " Confirme em uma frase curta, sem descrever o que já está na tela."
                + (alvo.isEmpty() ? "" : " (janela " + alvo + ")");
    }
}
