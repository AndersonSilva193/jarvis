package dev.jarvis.busca;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.proativo.AvisosProativos;
import dev.jarvis.proativo.FalasService;

/**
 * A cota mensal do Tavily: conta cada crédito gasto, avisa em 80% e, em 100%, tira o Tavily da vez
 * até a virada do mês.
 *
 * <p><b>O aviso sai ao cruzar o limiar, não ao estar acima dele.</b> A contagem do mês vem dos JSON
 * diários do {@link MedidorDeCusto}, que sobrevivem ao restart; então "antes estava abaixo de 80%,
 * agora está em 80% ou mais" acontece uma vez por mês, com ou sem restart no meio — sem marcador em
 * disco para isso. Um salto que passe dos dois limiares de uma vez manda só o de 100%, como o teto
 * de custo: o de 80% existe para chegar antes.
 *
 * <p>Os dois avisos passam pelo {@link AvisosProativos}, com chave e estado por mês, e herdam dali o
 * silêncio da madrugada e a regra de não repetir.
 */
@Component
public class CotaDeBusca {

    private static final Logger log = LoggerFactory.getLogger(CotaDeBusca.class);

    /** Só o Tavily tem cota: o SearXNG é do dono e a reserva Anthropic é cobrada em dólar. */
    static final String COM_COTA = "tavily";
    private static final int PRIMEIRO_AVISO_PCT = 80;

    private final MedidorDeCusto medidor;
    private final AvisosProativos avisos;
    private final FalasService falas;
    private final JarvisProperties props;
    private final Clock relogio;

    @Autowired
    public CotaDeBusca(MedidorDeCusto medidor, AvisosProativos avisos, FalasService falas,
            JarvisProperties props) {
        this(medidor, avisos, falas, props, Clock.systemUTC());
    }

    CotaDeBusca(MedidorDeCusto medidor, AvisosProativos avisos, FalasService falas,
            JarvisProperties props, Clock relogio) {
        this.medidor = medidor;
        this.avisos = avisos;
        this.falas = falas;
        this.props = props;
        this.relogio = relogio;
    }

    /** Se o provedor já gastou a cota do mês. Sem cota configurada, nunca. */
    public boolean esgotada(String provedor) {
        int cota = props.cotaMensalDoTavily();
        return COM_COTA.equals(provedor) && cota > 0 && medidor.buscasDoMes(provedor) >= cota;
    }

    /** Conta um uso que de fato foi ao provedor e avisa se ele cruzou um limiar. */
    public void registrar(String provedor) {
        int cota = props.cotaMensalDoTavily();
        boolean comCota = COM_COTA.equals(provedor) && cota > 0;
        long antes = comCota ? medidor.buscasDoMes(provedor) : 0;
        medidor.registrarBusca(provedor);
        if (!comCota) {
            return;
        }
        long depois = antes + 1;
        String mes = YearMonth.from(relogio.instant().atZone(props.zona())).toString();
        long oitenta = (long) Math.ceil(cota * PRIMEIRO_AVISO_PCT / 100.0);
        try {
            if (antes < cota && depois >= cota) {
                log.warn("Busca: cota do Tavily esgotada em {} ({} de {}); até a virada vão as reservas",
                        mes, depois, cota);
                avisos.avisar("busca:" + provedor, "esgotada:" + mes,
                        falas.frase("busca_cota_esgotada", Map.of()), null);
            } else if (antes < oitenta && depois >= oitenta) {
                avisos.avisar("busca:" + provedor, "alta:" + mes, falas.frase("busca_cota_alta",
                        Map.of("percentual", String.valueOf(depois * 100 / cota),
                                "usadas", String.valueOf(depois), "cota", String.valueOf(cota))), null);
            }
        } catch (RuntimeException e) {
            // O aviso é cortesia: a busca já aconteceu e a resposta não pode cair por causa dele.
            log.warn("Busca: não consegui avisar a cota do Tavily: {}", e.getMessage());
        }
    }
}
