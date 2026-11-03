package dev.jarvis.autonomia;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.Origem;
import dev.jarvis.ia.provedor.BlocoDeSistema;
import dev.jarvis.ia.provedor.InstrucoesDeSistema;
import dev.jarvis.ia.provedor.ListaDeFerramentas;
import dev.jarvis.ia.provedor.Mensagem;
import dev.jarvis.ia.provedor.OpcoesDoPedido;
import dev.jarvis.ia.provedor.PedidoAoModelo;
import dev.jarvis.ia.provedor.ProvedorDeApoio;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.ia.provedor.RespostaDoModelo;

/**
 * A segunda leitura de um "pode fazer X sem me perguntar": confirma, fora do turno, que a mensagem
 * do dono concede <b>aquela</b> permissão e até quando.
 *
 * <p>É o desenho do {@code ClassificadorDeResposta}: uma chamada curta ao modelo de apoio, sem
 * ferramentas, com um system mínimo montado aqui e resposta em JSON. Ela existe porque a evidência
 * literal sozinha não basta — "não pode rodar rotina hoje" contém "pode rodar rotina" —, e porque
 * quem chamou a {@code conceder_permissao} foi o modelo principal, no meio de um turno que pode ter
 * lido página ou arquivo. Aqui o modelo vê só a mensagem do dono, a ação e o relógio.
 *
 * <p><b>Tudo que não é um "sim" com prazo legível é "não grava"</b> — rede, prazo, JSON torto,
 * {@code duvida}. Perder uma permissão custa ao dono repetir a frase; ganhar uma por engano custa
 * uma semana de ações sem pergunta.
 */
@Component
public class ClassificadorDeConcessao {

    private static final Logger log = LoggerFactory.getLogger(ClassificadorDeConcessao.class);

    private static final int MAX_TOKENS = 60;
    private static final Duration PRAZO = Duration.ofSeconds(5);

    private static final Pattern CONCEDE = Pattern.compile("\"concede\"\\s*:\\s*\"([a-z_]+)\"");
    private static final Pattern ATE = Pattern.compile(
            "\"ate\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2})?)\"");

    private static final String SISTEMA = """
            Você confere se a mensagem de uma pessoa dá a um assistente permissão para fazer uma \
            ação sem perguntar antes, e até quando. Responda apenas com um JSON \
            {"concede":"sim|nao|duvida","ate":"AAAA-MM-DDTHH:MM"}, sem mais nada.

            sim = a mensagem libera, com clareza, essa ação sem pergunta.
            nao = não libera: nega, proíbe, pergunta, fala de outra ação ou de outra coisa.
            duvida = não dá para ter certeza.

            "ate" é o fim da permissão, no horário local da pessoa: "hoje à noite" é hoje às 23:59, \
            "até sexta" é a próxima sexta às 23:59, "até amanhã" é amanhã às 23:59. Sem prazo dito, \
            use hoje às 23:59.

            Na dúvida, responda duvida.""";

    private final ProvedorDeModelo modelo;
    private final ZoneId zona;
    private final Clock relogio;

    @Autowired
    public ClassificadorDeConcessao(@Qualifier(ProvedorDeApoio.QUALIFICADOR) ProvedorDeModelo modelo,
            JarvisProperties props) {
        this(modelo, props.zona(), Clock.systemUTC());
    }

    public ClassificadorDeConcessao(ProvedorDeModelo modelo, ZoneId zona, Clock relogio) {
        this.modelo = modelo;
        this.zona = zona;
        this.relogio = relogio;
    }

    /**
     * O prazo que a mensagem concede para aquela ação, ou vazio quando ela não concede — ou quando
     * não deu para saber.
     *
     * @param mensagem o texto que o dono mandou, e só ele
     */
    public Optional<Instant> confirmar(String mensagem, AcaoAutonoma acao, String alvo) {
        if (modelo == null || mensagem == null || mensagem.isBlank()) return Optional.empty();
        try {
            ZonedDateTime agora = relogio.instant().atZone(zona);
            String pedido = "Agora: "
                    + agora.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.of("pt", "BR"))
                    + ", " + agora.toLocalDateTime().withSecond(0).withNano(0)
                    + "\nAção: " + acao.oQue(alvo)
                    + "\nMensagem da pessoa: " + mensagem;
            RespostaDoModelo resposta = modelo.responder(new PedidoAoModelo(
                    InstrucoesDeSistema.de(new BlocoDeSistema(SISTEMA, null)),
                    List.of(Mensagem.texto(Mensagem.Papel.USUARIO, pedido)),
                    ListaDeFerramentas.vazia(),
                    OpcoesDoPedido.curta(Origem.CLASSIFICADOR, MAX_TOKENS, PRAZO)),
                    r -> legivel(String.join("", r.textos())));
            return ler(String.join("", resposta.textos()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Não consegui confirmar a permissão ({}); não gravo.", e.toString());
            return Optional.empty();
        }
    }

    /** Há uma decisão escrita. "duvida" é decisão e vale; texto sem decisão manda o local para a reserva. */
    private static boolean legivel(String texto) {
        return CONCEDE.matcher(texto.toLowerCase(Locale.ROOT)).find();
    }

    Optional<Instant> ler(String texto) {
        String minusculo = texto.toLowerCase(Locale.ROOT);
        Matcher concede = CONCEDE.matcher(minusculo);
        if (!concede.find() || !"sim".equals(concede.group(1))) return Optional.empty();
        Matcher ate = ATE.matcher(texto);
        if (!ate.find()) return Optional.empty();
        try {
            return Optional.of(LocalDateTime.parse(ate.group(1)).atZone(zona).toInstant());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
