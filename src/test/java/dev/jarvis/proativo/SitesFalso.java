package dev.jarvis.proativo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import dev.jarvis.briefing.fontes.SitesService;
import dev.jarvis.config.JarvisProperties;

/**
 * Um {@code SitesService} com roteiro: cada chamada consome a próxima rodada combinada.
 *
 * <p>Devolve as <b>mesmas formas</b> do serviço real — {@code Leitura} com {@code Situacao} — e
 * monta o {@code motivo} como ele monta, porque dublê que inventa a própria forma só prova que o
 * código concorda com o próprio engano. O mapa de sites continua vindo das properties, então
 * {@code sites()} é o de verdade.
 */
class SitesFalso extends SitesService {

    private final Map<String, String> urls;
    private final Deque<Boolean> roteiro = new ArrayDeque<>();
    int rodadas;

    SitesFalso(JarvisProperties props) {
        super(null, props);
        this.urls = props.sitesMonitorados();
    }

    /** Acrescenta rodadas ao roteiro: {@code true} é site no ar. */
    SitesFalso roteiro(boolean... rodadasNoAr) {
        for (boolean ok : rodadasNoAr) {
            roteiro.add(ok);
        }
        return this;
    }

    @Override
    public List<Leitura> checarTodasAsSituacoes() {
        rodadas++;
        boolean ok = roteiro.isEmpty() ? true : roteiro.poll();
        List<Leitura> leituras = new ArrayList<>();
        urls.forEach((nome, url) -> leituras.add(new Leitura(nome, url,
                ok ? new Situacao(true, 200, 80, "")
                   : new Situacao(false, 502, 95, "HTTP 502"))));
        return leituras;
    }
}
