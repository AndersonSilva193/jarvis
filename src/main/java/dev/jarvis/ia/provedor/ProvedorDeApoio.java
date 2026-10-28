package dev.jarvis.ia.provedor;

import java.io.IOException;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;

/**
 * O provedor das tarefas de apoio: o modelo do PC primeiro, o Claude de reserva.
 *
 * <p>Atende classificador, anotador, narrador do briefing e introdução — quem o recebe pelo
 * {@link #QUALIFICADOR}. A conversa e o laço de ferramentas continuam no {@code ProvedorAnthropic},
 * que é o {@code @Primary}.
 *
 * <p>Três caminhos levam ao Claude, e cada um conta uma reserva no {@link MedidorDeCusto} com o
 * motivo: o local <b>indisponível</b> antes de tentar ({@link DisponibilidadeLocal}), o local que
 * <b>falhou</b> ({@link FalhaLocal#motivo()}: timeout, sem memória, erro de formato...) e o local
 * que respondeu mas foi <b>reprovado</b> pelo validador de quem chamou. O validador é o mesmo que já
 * valia para o Claude — o JSON do classificador, a evidência literal do anotador, as marcas do
 * briefing —, e quem chama continua conferindo o que volta do Claude como sempre conferiu.
 *
 * <p>Com {@code IA_LOCAL_APOIO} desligado, isto é só o Claude: nenhuma reserva é contada, porque
 * nada foi tentado.
 */
@Component
@Qualifier(ProvedorDeApoio.QUALIFICADOR)
public class ProvedorDeApoio implements ProvedorDeModelo {

    public static final String QUALIFICADOR = "apoio";
    /** O local respondeu e o validador de quem chamou recusou. */
    public static final String REPROVADO = "reprovado";

    private static final Logger log = LoggerFactory.getLogger(ProvedorDeApoio.class);

    private final ProvedorDeModelo claude;
    private final ProvedorDeModelo local;
    private final DisponibilidadeLocal disponibilidade;
    /** Pode ser nulo nos testes que não conferem a contagem. */
    private final MedidorDeCusto medidor;
    private final JarvisProperties props;

    @Autowired
    public ProvedorDeApoio(ProvedorAnthropic claude, ProvedorLocal local,
            DisponibilidadeLocal disponibilidade, MedidorDeCusto medidor, JarvisProperties props) {
        this((ProvedorDeModelo) claude, local, disponibilidade, medidor, props);
    }

    public ProvedorDeApoio(ProvedorDeModelo claude, ProvedorDeModelo local,
            DisponibilidadeLocal disponibilidade, MedidorDeCusto medidor, JarvisProperties props) {
        this.claude = claude;
        this.local = local;
        this.disponibilidade = disponibilidade;
        this.medidor = medidor;
        this.props = props;
    }

    /** Sem validador próprio: serve resposta com texto ou com chamada. */
    @Override
    public RespostaDoModelo responder(PedidoAoModelo pedido) throws IOException, InterruptedException {
        return responder(pedido, ProvedorDeApoio::temConteudo);
    }

    @Override
    public RespostaDoModelo responder(PedidoAoModelo pedido, Predicate<RespostaDoModelo> aceita)
            throws IOException, InterruptedException {
        if (!props.apoioLocal()) {
            return claude.responder(pedido);
        }
        DisponibilidadeLocal.Situacao situacao = disponibilidade.agora();
        if (!situacao.pode()) {
            return reserva(situacao.motivo(), pedido);
        }
        try {
            RespostaDoModelo resposta = local.responder(pedido);
            if (aceita.test(resposta)) {
                return resposta;
            }
            return reserva(REPROVADO, pedido);
        } catch (FalhaLocal falha) {
            return reserva(falha.motivo(), pedido);
        } catch (IOException | RuntimeException falha) {
            log.warn("Modelo local falhou ({}); o Claude assume.", falha.getClass().getSimpleName());
            return reserva(ProvedorLocal.FALHA_LOCAL, pedido);
        }
    }

    private RespostaDoModelo reserva(String motivo, PedidoAoModelo pedido)
            throws IOException, InterruptedException {
        log.info("Reserva no Claude para {}: {}", pedido.opcoes().origem().chave(), motivo);
        if (medidor != null) {
            medidor.registrarReserva(motivo);
        }
        return claude.responder(pedido);
    }

    private static boolean temConteudo(RespostaDoModelo resposta) {
        return resposta.blocos().stream().anyMatch(bloco -> bloco instanceof Bloco.Chamada
                || bloco instanceof Bloco.Texto t && !t.texto().isBlank());
    }
}
