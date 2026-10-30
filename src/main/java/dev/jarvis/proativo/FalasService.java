package dev.jarvis.proativo;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * As frases dos avisos que o Jarvis manda sozinho — <b>só estas</b>.
 *
 * <p>Catálogo fechado pelo mesmo motivo do {@code FalaDaTela}: o aviso proativo chega sem ninguém
 * ter pedido, e o dono não tem como conferir na hora de onde o texto veio. Aqui o conjunto de
 * frases possíveis é um {@code Map} no servidor, e o que varia são os <b>marcadores</b>, cujos
 * valores saem de dado real — hora do relógio, nome do site, motivo da checagem —, nunca de texto
 * escrito pelo modelo. É a regra da {@code pergunta} do {@code Aprovacoes} e da {@code descricao}
 * do {@code DiarioDeAcoes}.
 *
 * <p>Os marcadores são a parte nova: o {@code FalaDaTela} tem frases fixas, e um aviso de site sem
 * o nome do site não diz nada. Frase nova é uma linha em {@link #FALAS}.
 */
@Service
public class FalasService {

    private static final Pattern MARCADOR = Pattern.compile("\\{([a-z_]+)\\}");

    private static final Map<String, String> FALAS = criar();

    private static Map<String, String> criar() {
        Map<String, String> falas = new LinkedHashMap<>();
        falas.put("site_fora", "O site da {site} está fora do ar desde {desde} ({motivo}).");
        falas.put("site_voltou", "O site da {site} voltou. Ficou fora por {duracao}.");
        // A cota de voz. As três primeiras são a mesma notícia com projeções diferentes, e são
        // três frases — e não uma com marcador opcional — porque marcador sem valor estoura: a
        // forma de dizer menos é ter uma frase que pede menos.
        falas.put("voz_cota_alta",
                "A cota de voz está em {percentual}%. No ritmo atual, dura até {data}.");
        falas.put("voz_cota_alta_ate_renovacao", "A cota de voz está em {percentual}%. No ritmo "
                + "atual, chega à renovação de {data} sem acabar.");
        falas.put("voz_cota_alta_sem_projecao", "A cota de voz está em {percentual}%.");
        falas.put("voz_na_reserva",
                "A cota de voz chegou à reserva. Volto à voz do navegador até {renovacao}.");
        falas.put("voz_indisponivel",
                "A voz da ElevenLabs saiu do ar: {motivo}. Volto à voz do navegador.");
        falas.put("voz_renovada", "A cota de voz foi renovada. Volto à minha voz.");
        // O modo LOCAL forçado caindo no Claude: uma vez por período de indisponibilidade.
        falas.put("ia_local_indisponivel",
                "O PC não está disponível; estou usando o Claude por enquanto.");
        // O holograma pedido pelo Telegram com a HUD fechada: gerado em segundo plano, para a galeria.
        falas.put("holograma_na_galeria", "{objeto} está na galeria do holograma: {pecas} peças.");
        falas.put("holograma_na_galeria_falhou",
                "Não consegui montar {objeto} para a galeria. Tente de novo com a HUD aberta.");
        // A cota mensal do Tavily na buscar_na_web. Os números saem do MedidorDeCusto.
        falas.put("busca_cota_alta",
                "A cota de buscas do Tavily está em {percentual}% este mês ({usadas} de {cota}).");
        falas.put("busca_cota_esgotada", "A cota de buscas do Tavily acabou este mês. Até a "
                + "virada, pesquiso pelas reservas.");
        return Map.copyOf(falas);
    }

    /** As situações conhecidas, para a mensagem de erro dizer quais são. */
    public Set<String> situacoes() {
        return FALAS.keySet();
    }

    /**
     * A frase da situação, com os marcadores trocados pelos valores.
     *
     * <p>Situação desconhecida e marcador sem valor <b>estouram</b>, em vez de sair pela metade:
     * isto é erro de programação, não entrada de usuário, e uma mensagem chegando ao Telegram com
     * um {@code {motivo}} literal é pior do que um teste vermelho. Valor sobrando é ignorado — o
     * mesmo mapa serve a duas frases que não usam os mesmos marcadores.
     */
    public String frase(String situacao, Map<String, String> marcadores) {
        String modelo = FALAS.get(situacao);
        if (modelo == null) {
            throw new IllegalArgumentException("situação desconhecida '" + situacao
                    + "'; conheço " + situacoes());
        }
        Map<String, String> valores = marcadores == null ? Map.of() : marcadores;
        Matcher busca = MARCADOR.matcher(modelo);
        StringBuilder frase = new StringBuilder();
        while (busca.find()) {
            String nome = busca.group(1);
            String valor = valores.get(nome);
            if (valor == null || valor.isBlank()) {
                throw new IllegalArgumentException("a fala '" + situacao + "' precisa do marcador {"
                        + nome + "}");
            }
            busca.appendReplacement(frase, Matcher.quoteReplacement(valor));
        }
        busca.appendTail(frase);
        return frase.toString();
    }

    /**
     * Quanto tempo, em português falado: "25 minutos", "2 horas e 5 minutos", "menos de um minuto".
     *
     * <p>Singular e plural de verdade, como o {@code TipoDeAcao.rotulo(int)}: isto pode ser lido em
     * voz alta, e "1 minutos" entrega que ninguém revisou. Abaixo de um minuto não arredonda para
     * zero — "ficou fora por 0 minutos" afirmaria que não houve queda.
     */
    public static String duracaoFalada(Duration quanto) {
        long minutos = quanto == null ? 0 : Math.max(0, quanto.toMinutes());
        if (minutos == 0) {
            return "menos de um minuto";
        }
        long horas = minutos / 60;
        long resto = minutos % 60;
        if (horas == 0) {
            return plural(minutos, "minuto");
        }
        return resto == 0 ? plural(horas, "hora")
                : plural(horas, "hora") + " e " + plural(resto, "minuto");
    }

    private static String plural(long quantidade, String unidade) {
        return quantidade + " " + (quantidade == 1 ? unidade : unidade + "s");
    }
}
