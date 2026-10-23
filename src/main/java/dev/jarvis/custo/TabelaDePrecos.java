package dev.jarvis.custo;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quanto cada modelo cobra, lido de {@code CUSTO_PRECOS}.
 *
 * <b>Preço não mora no código.</b> Ele muda sem aviso, e um número desatualizado compilado junto é
 * pior que número nenhum: a conta sai errada com cara de certa. O formato é
 * {@code modelo=entrada/saida/cache_escrita/cache_leitura}, em dólares por <b>milhão</b> de tokens,
 * separado por ponto e vírgula. {@code CUSTO_PRECOS_CONTEXTO_LONGO} aceita
 * {@code modelo@limiar=entrada/saida/cache_escrita/cache_leitura}; o limiar conta toda a entrada,
 * inclusive cache, e acima dele a tarifa maior vale para a chamada inteira. A busca na web vem
 * à parte, em dólares por <b>mil</b> buscas, porque é assim que ela é publicada.
 *
 * <p>Entrada torta é descartada com WARN, nunca lançada — a disciplina de
 * {@code JarvisProperties.sitesMonitorados()}. Um typo no {@code .env} não pode impedir o Jarvis de
 * subir, e o modelo sem preço já cai num estado tratado: conta tokens, não conta dólar.
 *
 * <p>O casamento do nome do modelo é <b>exato</b>. Casar por prefixo faria {@code claude-haiku-5-5}
 * cobrir calado um modelo de nome parecido e preço diferente; é melhor cair em "sem preço" e levar
 * o aviso do dia.
 */
public record TabelaDePrecos(Map<String, Preco> porModelo,
        Map<String, FaixaLonga> contextoLongo, double buscaPorMil) {

    private static final Logger log = LoggerFactory.getLogger(TabelaDePrecos.class);

    public TabelaDePrecos {
        porModelo = porModelo == null ? Map.of() : Map.copyOf(porModelo);
        contextoLongo = contextoLongo == null ? Map.of() : Map.copyOf(contextoLongo);
        buscaPorMil = Math.max(0, buscaPorMil);
    }

    /** A tarifa alternativa começa somente quando o prompt ultrapassa este limiar. */
    public record FaixaLonga(long limiar, Preco preco) { }

    public static TabelaDePrecos vazia() {
        return new TabelaDePrecos(Map.of(), Map.of(), 0);
    }

    /** Lê a configuração uma vez. Quem chama guarda o resultado: isto roda em toda chamada ao modelo. */
    public static TabelaDePrecos analisar(String precos, double buscaPorMil) {
        return analisar(precos, "", buscaPorMil);
    }

    public static TabelaDePrecos analisar(String precos, String precosContextoLongo,
            double buscaPorMil) {
        Map<String, Preco> mapa = lerTabelaBasica(precos);
        Map<String, FaixaLonga> longas = lerFaixasLongas(precosContextoLongo, mapa);
        return new TabelaDePrecos(mapa, longas, buscaPorMil);
    }

    private static Map<String, Preco> lerTabelaBasica(String precos) {
        Map<String, Preco> mapa = new LinkedHashMap<>();
        if (precos == null || precos.isBlank()) {
            return mapa;
        }
        for (String par : precos.split(";")) {
            String entrada = par.trim();
            if (entrada.isEmpty()) {
                continue;
            }
            String[] partes = entrada.split("=", 2);
            if (partes.length < 2) {
                log.warn("CUSTO_PRECOS: entrada sem '=' ignorada: {}", entrada);
                continue;
            }
            String modelo = partes[0].trim();
            if (modelo.isBlank()) {
                log.warn("CUSTO_PRECOS: entrada sem nome de modelo ignorada: {}", entrada);
                continue;
            }
            Preco preco = lerPreco("CUSTO_PRECOS", modelo, partes[1].trim());
            if (preco == null) {
                continue;
            }
            if (mapa.containsKey(modelo)) {
                log.warn("CUSTO_PRECOS: modelo '{}' repetido; mantido o primeiro preço e ignorado "
                        + "o seguinte", modelo);
                continue;
            }
            mapa.put(modelo, preco);
        }
        return mapa;
    }

    private static Map<String, FaixaLonga> lerFaixasLongas(String bruto,
            Map<String, Preco> basicos) {
        Map<String, FaixaLonga> longas = new LinkedHashMap<>();
        if (bruto == null || bruto.isBlank()) {
            return longas;
        }
        for (String par : bruto.split(";")) {
            String entrada = par.trim();
            if (entrada.isEmpty()) {
                continue;
            }
            String[] partes = entrada.split("=", 2);
            int arroba = partes[0].lastIndexOf('@');
            if (partes.length != 2 || arroba <= 0 || arroba == partes[0].length() - 1) {
                log.warn("CUSTO_PRECOS_CONTEXTO_LONGO: entrada inválida '{}' ignorada", entrada);
                continue;
            }
            String modelo = partes[0].substring(0, arroba).trim();
            if (!basicos.containsKey(modelo) || longas.containsKey(modelo)) {
                log.warn("CUSTO_PRECOS_CONTEXTO_LONGO: modelo '{}' sem preço básico ou repetido; "
                        + "entrada ignorada", modelo);
                continue;
            }
            long limiar;
            try {
                limiar = Long.parseLong(partes[0].substring(arroba + 1).trim());
            } catch (NumberFormatException e) {
                log.warn("CUSTO_PRECOS_CONTEXTO_LONGO: limiar inválido para '{}'; entrada ignorada",
                        modelo);
                continue;
            }
            if (limiar <= 0) {
                log.warn("CUSTO_PRECOS_CONTEXTO_LONGO: limiar de '{}' deve ser positivo", modelo);
                continue;
            }
            Preco preco = lerPreco("CUSTO_PRECOS_CONTEXTO_LONGO", modelo, partes[1].trim());
            if (preco != null) {
                longas.put(modelo, new FaixaLonga(limiar, preco));
            }
        }
        return longas;
    }

    /** Os quatro valores, ou null com WARN. Meia tabela de preço é pior que nenhuma. */
    private static Preco lerPreco(String variavel, String modelo, String valores) {
        String[] campos = valores.split("/", -1);
        if (campos.length != 4) {
            log.warn("{}: o modelo '{}' precisa de quatro preços "
                    + "(entrada/saida/cache_escrita/cache_leitura); entrada ignorada", variavel, modelo);
            return null;
        }
        double[] numeros = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                numeros[i] = Double.parseDouble(campos[i].trim());
            } catch (NumberFormatException e) {
                log.warn("{}: preço '{}' do modelo '{}' não é número; entrada ignorada",
                        variavel, campos[i].trim(), modelo);
                return null;
            }
            if (numeros[i] < 0 || !Double.isFinite(numeros[i])) {
                log.warn("{}: preço negativo ou inválido no modelo '{}'; entrada ignorada",
                        variavel, modelo);
                return null;
            }
        }
        return new Preco(numeros[0], numeros[1], numeros[2], numeros[3]);
    }

    /** O preço daquele modelo, ou null quando ele não está configurado. */
    public Preco precoDe(String modelo) {
        return modelo == null ? null : porModelo.get(modelo);
    }

    /** A faixa é determinada por toda a entrada da chamada, cache incluído. */
    public Preco precoDe(String modelo, long entrada, long cacheEscrito, long cacheLido) {
        if (modelo == null) {
            return null;
        }
        FaixaLonga longa = contextoLongo.get(modelo);
        if (longa != null && (double) entrada + cacheEscrito + cacheLido > longa.limiar()) {
            return longa.preco();
        }
        return precoDe(modelo);
    }

    /**
     * O custo em dólares de um consumo. Modelo sem preço devolve zero — quem chama é que sabe
     * distinguir "não custou nada" de "não sei quanto custou", e faz isso pelo {@link #precoDe}.
     */
    public double custo(String modelo, long entrada, long saida, long cacheEscrito, long cacheLido,
            long buscas) {
        Preco preco = precoDe(modelo, entrada, cacheEscrito, cacheLido);
        double porTokens = preco == null ? 0
                : (entrada * preco.entradaPorMilhao()
                        + saida * preco.saidaPorMilhao()
                        + cacheEscrito * preco.cacheEscritaPorMilhao()
                        + cacheLido * preco.cacheLeituraPorMilhao()) / 1_000_000d;
        return porTokens + buscas * buscaPorMil / 1_000d;
    }
}
