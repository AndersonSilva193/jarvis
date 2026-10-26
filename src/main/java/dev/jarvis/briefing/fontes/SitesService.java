package dev.jarvis.briefing.fontes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;

/**
 * Checa se os sites e APIs de SITES_MONITORADOS estão respondendo.
 * Só conhece as URLs da lista: nunca acessa uma URL que venha de fora da configuração.
 *
 * <p>Há dois jeitos de ler o resultado, e a diferença importa. A {@link Checagem} traz a frase
 * pronta, que é o que o briefing lê em voz alta; a {@link Situacao} traz o dado antes de virar
 * frase, que é o que o monitor proativo precisa. Quem decide "o site caiu" a partir do texto
 * acerta hoje e erra calado no dia em que a frase mudar — e um aviso proativo é uma afirmação, não
 * um palpite. Mesmo desenho do {@code ConsultaArquivosPc}, onde {@code consultar} devolve o dado e
 * {@code executar} serializa.
 */
@Service
public class SitesService {

    /** Resultado da checagem de um site, já em texto pronto para ler. */
    public record Checagem(String nome, String url, String resultado) {
    }

    /**
     * O que a checagem viu, antes de virar frase.
     *
     * @param ok     se o site respondeu sem erro. O critério é o mesmo do {@code (OK)} da frase:
     *               status abaixo de 400.
     * @param status o código HTTP, ou zero quando não houve resposta nenhuma.
     * @param ms     quanto tempo a resposta levou.
     * @param motivo por que não está no ar, curto o bastante para caber num aviso ("HTTP 502",
     *               "sem resposta (ConnectException)"). Vazio quando {@code ok}.
     */
    public record Situacao(boolean ok, int status, long ms, String motivo) {
    }

    /** Uma {@link Situacao} com o nome e a URL do site a que pertence. */
    public record Leitura(String nome, String url, Situacao situacao) {
    }

    private final HttpClient http;
    private final Map<String, String> sites;

    public SitesService(HttpClient http, JarvisProperties props) {
        this.http = http;
        this.sites = props.sitesMonitorados();
    }

    /** Mapa nome -> URL dos sites configurados, na ordem do .env. */
    public Map<String, String> sites() {
        return sites;
    }

    /** Checa todos os sites configurados, um a um. */
    public List<Checagem> checarTodos() {
        List<Checagem> checagens = new ArrayList<>();
        for (Map.Entry<String, String> site : sites.entrySet()) {
            checagens.add(new Checagem(site.getKey(), site.getValue(), checar(site.getValue())));
        }
        return checagens;
    }

    /** Como o {@link #checarTodos()}, devolvendo o dado em vez da frase. */
    public List<Leitura> checarTodasAsSituacoes() {
        List<Leitura> leituras = new ArrayList<>();
        for (Map.Entry<String, String> site : sites.entrySet()) {
            leituras.add(new Leitura(site.getKey(), site.getValue(), situacaoDe(site.getValue())));
        }
        return leituras;
    }

    /**
     * Faz uma requisição GET e descreve o resultado (status, tempo e situação) em uma linha.
     *
     * <p>É a casca que formata a {@link #situacaoDe}: o texto daqui é lido pelo briefing e
     * procurado pela marca {@code (OK)} do {@code InicializacaoService}, então ele não muda.
     */
    public String checar(String url) {
        Situacao situacao = situacaoDe(url);
        if (situacao.status() == 0) {
            return situacao.motivo().startsWith("checagem interrompida")
                    ? "verificação interrompida"
                    : "FORA DO AR ou inacessível (" + situacao.motivo() + ")";
        }
        String descricao = situacao.ok() ? "OK"
                : situacao.status() < 500 ? "respondeu, mas com erro do cliente" : "ERRO no servidor";
        return "HTTP " + situacao.status() + " em " + situacao.ms() + " ms (" + descricao + ")";
    }

    /** A requisição em si. Nunca lança: falha de rede é um resultado, não um acidente. */
    public Situacao situacaoDe(String url) {
        long inicio = System.nanoTime();
        try {
            HttpRequest requisicao = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Jarvis-Monitor/1.0")
                    .GET()
                    .build();
            int status = http.send(requisicao, HttpResponse.BodyHandlers.discarding()).statusCode();
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            boolean ok = status < 400;
            return new Situacao(ok, status, ms, ok ? "" : "HTTP " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Situacao(false, 0, decorrido(inicio), "checagem interrompida");
        } catch (Exception e) {
            // A mensagem da exceção entra junto porque "ConnectException" sozinho não distingue
            // DNS que não resolve de porta fechada, e é isso que o dono quer saber no aviso.
            return new Situacao(false, 0, decorrido(inicio),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static long decorrido(long inicio) {
        return (System.nanoTime() - inicio) / 1_000_000;
    }
}
