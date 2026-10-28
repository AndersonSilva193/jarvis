package dev.jarvis.busca;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.busca.ProvedorDeBusca.Consulta;
import dev.jarvis.busca.ProvedorDeBusca.FalhaDeBusca;
import dev.jarvis.busca.ProvedorDeBusca.RespostaDaBusca;
import dev.jarvis.busca.ProvedorDeBusca.ResultadoDaBusca;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.tela.MontadorDeCardDePesquisa;
import dev.jarvis.util.Texto;

/**
 * Percorre os provedores na ordem do {@code BUSCA_PROVEDORES} até um responder.
 *
 * <p>Três regras moram aqui, e não em cada provedor, para valerem igual nos três:
 * <ul>
 *   <li><b>Falhou, vai o próximo.</b> Falha é erro de rede, HTTP diferente de 200, resposta torta
 *       ou nenhum resultado aproveitável. O Tavily com a cota do mês esgotada é pulado sem nem ser
 *       tentado.</li>
 *   <li><b>Só https.</b> A URL passa pela mesma validação das fontes do card
 *       ({@link MontadorDeCardDePesquisa#urlSegura}: esquema, host, sem usuário e senha) e, além
 *       dela, tem de ser https. O que não passa é descartado calado: é resultado a menos, não erro.</li>
 *   <li><b>Cache de 10 minutos</b> por consulta normalizada — sem acento, minúsculas, espaços
 *       únicos —, mais a quantidade e a recência. O modelo repete a mesma busca de um passo para o
 *       outro, e cada repetição no Tavily é um crédito.</li>
 * </ul>
 */
@Component
public class BuscaNaWeb {

    private static final Logger log = LoggerFactory.getLogger(BuscaNaWeb.class);

    static final Duration VALIDADE = Duration.ofMinutes(10);
    /** Teto de consultas guardadas: dez minutos de conversa não chegam perto, um laço chegaria. */
    static final int MAX_GUARDADAS = 200;

    private final Map<String, ProvedorDeBusca> porNome = new HashMap<>();
    private final CotaDeBusca cota;
    /** A ordem dos configurados, lida uma vez: é de partida, e o WARN de nome torto também. */
    private final List<String> ordem;
    private final Clock relogio;

    /** Em ordem de inserção, para a mais velha sair primeiro quando o teto estoura. */
    private final Map<String, Guardada> cache = new LinkedHashMap<>();

    private record Guardada(RespostaDaBusca resposta, Instant quando) { }

    @Autowired
    public BuscaNaWeb(List<ProvedorDeBusca> provedores, CotaDeBusca cota, JarvisProperties props) {
        this(provedores, cota, props, Clock.systemUTC());
    }

    BuscaNaWeb(List<ProvedorDeBusca> provedores, CotaDeBusca cota, JarvisProperties props,
            Clock relogio) {
        for (ProvedorDeBusca provedor : provedores) {
            porNome.put(provedor.nome(), provedor);
        }
        this.cota = cota;
        this.ordem = props.provedoresDeBusca();
        this.relogio = relogio;
    }

    /**
     * A resposta do primeiro provedor que der certo, do cache quando ele está fresco.
     *
     * @throws FalhaDeBusca com os motivos de cada um ({@code tavily: http_500; searxng: rede}), ou
     *                      {@code sem_provedor} quando nenhum está configurado
     */
    public RespostaDaBusca buscar(Consulta consulta) throws FalhaDeBusca, InterruptedException {
        String chave = chave(consulta);
        synchronized (cache) {
            Guardada guardada = cache.get(chave);
            if (guardada != null && Duration.between(guardada.quando(), relogio.instant())
                    .compareTo(VALIDADE) < 0) {
                log.info("Busca na web: do cache ({})", guardada.resposta().provedor());
                return guardada.resposta();
            }
        }

        StringJoiner motivos = new StringJoiner("; ");
        for (String nome : ordem) {
            ProvedorDeBusca provedor = porNome.get(nome);
            if (provedor == null || !provedor.configurado()) {
                continue;
            }
            if (cota.esgotada(nome)) {
                motivos.add(nome + ": cota_esgotada");
                continue;
            }
            RespostaDaBusca bruta;
            try {
                bruta = provedor.buscar(consulta);
            } catch (FalhaDeBusca falha) {
                log.warn("Busca na web: {} falhou ({}); tentando o próximo", nome, falha.motivo());
                motivos.add(nome + ": " + falha.motivo());
                continue;
            }
            // Contado aqui, depois do 200: é quando o crédito foi gasto, com ou sem resultado bom.
            cota.registrar(nome);
            RespostaDaBusca filtrada = filtrar(bruta, consulta.quantidade());
            if (filtrada.resultados().isEmpty() && filtrada.resumo().isEmpty()) {
                log.warn("Busca na web: {} não trouxe resultado https; tentando o próximo", nome);
                motivos.add(nome + ": sem_resultado");
                continue;
            }
            log.info("Busca na web: {} com {} resultado(s)", nome, filtrada.resultados().size());
            guardar(chave, filtrada);
            return filtrada;
        }
        throw new FalhaDeBusca(motivos.length() == 0 ? "sem_provedor" : motivos.toString());
    }

    /** O que vale guardar como a mesma consulta. */
    static String chave(Consulta consulta) {
        return Texto.normalizar(consulta.texto()) + '\u0000' + consulta.quantidade()
                + '\u0000' + consulta.recenciaDias();
    }

    private void guardar(String chave, RespostaDaBusca resposta) {
        synchronized (cache) {
            Instant agora = relogio.instant();
            cache.values().removeIf(g -> Duration.between(g.quando(), agora).compareTo(VALIDADE) >= 0);
            cache.remove(chave);
            cache.put(chave, new Guardada(resposta, agora));
            Iterator<String> maisVelhas = cache.keySet().iterator();
            while (cache.size() > MAX_GUARDADAS && maisVelhas.hasNext()) {
                maisVelhas.next();
                maisVelhas.remove();
            }
        }
    }

    /** Descarta o que não é https seguro e as URLs repetidas, e corta na quantidade pedida. */
    private static RespostaDaBusca filtrar(RespostaDaBusca bruta, int quantidade) {
        List<ResultadoDaBusca> aceitos = new ArrayList<>();
        Set<String> vistas = new LinkedHashSet<>();
        for (ResultadoDaBusca resultado : bruta.resultados()) {
            if (aceitos.size() >= quantidade) {
                break;
            }
            String url = urlAceita(resultado.url());
            if (url == null || !vistas.add(url)) {
                continue;
            }
            aceitos.add(new ResultadoDaBusca(resultado.titulo(), url, resultado.data(), resultado.trecho()));
        }
        return new RespostaDaBusca(bruta.provedor(), aceitos, bruta.resumo());
    }

    /**
     * A URL na forma que vai ao modelo, ou {@code null}. A forma importa: é ela que a
     * {@code ler_pagina_web} vai comparar depois, então quem decide a grafia é este método só.
     */
    public static String urlAceita(String url) {
        try {
            String segura = MontadorDeCardDePesquisa.urlSegura(url);
            return "https".equalsIgnoreCase(URI.create(segura).getScheme()) ? segura : null;
        } catch (IllegalArgumentException recusada) {
            return null;
        }
    }
}
