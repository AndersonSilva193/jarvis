package dev.jarvis.proativo;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.telegram.TelegramClient;

/**
 * O <b>único</b> caminho das mensagens que o Jarvis manda sem ter sido chamado.
 *
 * <p>Até aqui tudo que o Jarvis dizia era resposta a um pedido — menos o resumo das 8h, que o dono
 * agendou, e o aviso de teto de custo. Um monitor que fala sozinho muda isso, e a diferença é
 * grande: quem recebe não estava esperando. Então as três regras de boa educação moram num
 * componente só, e não em cada aviso à sua maneira:
 *
 * <ol>
 *   <li><b>Horário de silêncio</b> ({@code PROATIVO_SILENCIO}): dentro dele nada sai. O aviso não é
 *       descartado — entra numa fila e é entregue <b>junto</b>, numa mensagem, no fim da janela. Um
 *       site que caiu às 2h ainda importa às 7h; o que não importa é o telefone apitando às 2h.</li>
 *   <li><b>Sem repetir</b>: a dupla chave do evento + estado é lembrada, e o mesmo estado não é
 *       anunciado duas vezes. É a diferença entre "o site caiu" e "o site continua caído", que o
 *       dono já sabe.</li>
 *   <li><b>Um destino só</b>: o {@code TELEGRAM_USUARIO_PERMITIDO}. Zero, ninguém é avisado.</li>
 * </ol>
 *
 * <p>A pergunta de aprovação entra como {@link Supplier}, não como texto, e isso é o desenho e não
 * um detalhe: ela só é <b>avaliada</b> quando a mensagem realmente sai. Uma pendência do
 * {@code Aprovacoes} criada durante o silêncio expiraria em dez minutos, horas antes de o dono ler
 * a pergunta, e ele responderia "pode" a um "Posso?" que já não existe. Mesmo idioma do
 * {@code Supplier} do {@code Aprovacoes}, pelo mesmo motivo: a ação nasce no instante em que faz
 * sentido, não antes.
 *
 * <p>Fila e memória de estados são <b>em memória</b>, como as confirmações pendentes: um restart no
 * meio da madrugada perde o acúmulo.
 */
@Component
public class AvisosProativos {

    private static final Logger log = LoggerFactory.getLogger(AvisosProativos.class);

    /**
     * Teto da fila do silêncio. Oito horas de janela com um monitor de cinco minutos não chegam
     * perto disso; o teto existe para um laço de aviso não virar uma mensagem de 400 linhas.
     */
    static final int MAX_ACUMULADOS = 50;

    static final String CABECALHO_DO_ACUMULO = "Enquanto o senhor dormia:";

    /** O que aconteceu com o aviso. Quem chama precisa saber: só o enviado virou pergunta. */
    public enum Entrega { ENVIADO, ACUMULADO, REPETIDO, SEM_DESTINO }

    private final TelegramClient telegram;
    private final JarvisProperties props;
    private final Clock relogio;

    /** Protege a memória de estados e a fila; o envio ao Telegram roda fora dele. */
    private final Object trava = new Object();
    /** Chave do evento -> último estado anunciado. */
    private final Map<String, String> ultimoEstado = new HashMap<>();
    private final List<String> acumulados = new ArrayList<>();
    private int descartados;

    @Autowired
    public AvisosProativos(TelegramClient telegram, JarvisProperties props) {
        this(telegram, props, Clock.systemUTC());
    }

    /** O relógio entra por aqui nos testes, para a janela de silêncio não depender da hora real. */
    public AvisosProativos(TelegramClient telegram, JarvisProperties props, Clock relogio) {
        this.telegram = telegram;
        this.props = props;
        this.relogio = relogio;
    }

    /** Se agora é hora de silêncio. Público porque é a pergunta que o monitor também faz. */
    public boolean emSilencio() {
        return janela().contem(relogio.instant(), props.zona());
    }

    private JanelaDeSilencio janela() {
        return JanelaDeSilencio.de(props.silencioProativo());
    }

    /**
     * Manda o aviso, ou o guarda para o fim do silêncio.
     *
     * @param chave            identifica o evento ao longo do tempo ({@code "site:loja"}), não a
     *                         ocorrência: é por ela que a repetição é reconhecida.
     * @param estado           o estado que está sendo anunciado ({@code "fora"}/{@code "dentro"}).
     * @param texto            a frase, montada em Java a partir do {@code FalasService}.
     * @param perguntaSeAgora  a pergunta a acrescentar <b>só</b> se a mensagem sair neste momento,
     *                         ou nulo. É aqui que o {@code Aprovacoes.pedir} do chamador roda.
     */
    public Entrega avisar(String chave, String estado, String texto,
            Supplier<String> perguntaSeAgora) {
        long chat = props.telegram() == null ? 0 : props.telegram().usuarioPermitido();
        if (chat == 0) {
            return Entrega.SEM_DESTINO;
        }
        if (texto == null || texto.isBlank()) {
            return Entrega.REPETIDO;
        }

        String aEnviar;
        synchronized (trava) {
            if (estado != null && estado.equals(ultimoEstado.get(chave))) {
                return Entrega.REPETIDO;
            }
            ultimoEstado.put(chave, estado);
            if (emSilencio()) {
                if (acumulados.size() >= MAX_ACUMULADOS) {
                    descartados++;
                } else {
                    acumulados.add(texto);
                }
                return Entrega.ACUMULADO;
            }
            // A pergunta nasce aqui dentro, com a decisão de enviar já tomada: assim não há como
            // uma pendência sobrar de um aviso que acabou acumulado.
            String pergunta = perguntaSeAgora == null ? null : perguntaSeAgora.get();
            aEnviar = pergunta == null || pergunta.isBlank() ? texto : texto + " " + pergunta;
        }

        enviar(chat, aEnviar);
        return Entrega.ENVIADO;
    }

    /**
     * Entrega o que o silêncio acumulou, numa mensagem só.
     *
     * <p>Um tique de minuto, e não um cron derivado da janela: a janela é configurável e recarrega
     * com o {@code .env}, e dois lugares calculando o mesmo horário divergem no dia em que um deles
     * for esquecido. O custo de conferir uma lista vazia a cada minuto é nenhum.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void entregarAcumulados() {
        long chat = props.telegram() == null ? 0 : props.telegram().usuarioPermitido();
        List<String> saindo;
        int perdidos;
        synchronized (trava) {
            if (acumulados.isEmpty() || emSilencio()) {
                return;
            }
            saindo = new ArrayList<>(acumulados);
            perdidos = descartados;
            acumulados.clear();
            descartados = 0;
        }
        if (chat == 0) {
            return;
        }
        StringBuilder texto = new StringBuilder(CABECALHO_DO_ACUMULO);
        for (String linha : saindo) {
            texto.append("\n- ").append(linha);
        }
        if (perdidos > 0) {
            texto.append("\n- e mais ").append(perdidos).append(" aviso(s) que não couberam.");
        }
        enviar(chat, texto.toString());
    }

    /** Falha de envio é WARN e nada mais: quem chama é uma tarefa agendada, não uma resposta. */
    private void enviar(long chat, String texto) {
        try {
            telegram.enviarMensagem(chat, texto);
        } catch (RuntimeException e) {
            log.warn("Não consegui mandar o aviso proativo: {}", e.getMessage());
        }
    }

    /** Só para os testes: quantos avisos estão esperando o fim do silêncio. */
    int acumulados() {
        synchronized (trava) {
            return acumulados.size();
        }
    }
}
