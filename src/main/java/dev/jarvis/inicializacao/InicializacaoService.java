package dev.jarvis.inicializacao;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import dev.jarvis.briefing.BriefingService;
import dev.jarvis.briefing.fontes.ClimaService;
import dev.jarvis.custo.Origem;
import dev.jarvis.briefing.fontes.ClimaService.ClimaHoje;
import dev.jarvis.briefing.fontes.ServidorService;
import dev.jarvis.briefing.fontes.ServidorService.EstadoServidor;
import dev.jarvis.briefing.fontes.SitesService;
import dev.jarvis.briefing.fontes.SitesService.Checagem;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.Canal;
import dev.jarvis.ia.ClaudeClient;
import dev.jarvis.ia.provedor.ListaDeFerramentas;
import dev.jarvis.ia.provedor.Mensagem;
import dev.jarvis.ia.provedor.OpcoesDoPedido;
import dev.jarvis.ia.provedor.PedidoAoModelo;
import dev.jarvis.ia.provedor.ProvedorAnthropic;
import dev.jarvis.ia.provedor.ProvedorDeApoio;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import org.springframework.beans.factory.annotation.Qualifier;
import dev.jarvis.ia.provedor.RespostaDoModelo;
import dev.jarvis.ia.SistemaDoJarvis;
import dev.jarvis.lembretes.LembreteService;
import dev.jarvis.lembretes.LembreteService.Lembrete;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * A abertura falada da HUD: o que o Jarvis diz no instante em que a tela acende.
 *
 * <p>Espelha o {@code BriefingService} — várias fontes, uma chamada só ao Claude, fonte que falha
 * fica de fora —, com duas diferenças de propósito.
 *
 * <p><b>Fala com o {@link ProvedorDeModelo} direto, não pelo Assistente.</b> Valem os dois motivos do
 * {@code NarradorDeBriefing} (o ciclo de dependência pelas Ferramentas, e o risco de o modelo sair
 * chamando ferramenta no meio de uma apresentação), mais um que é só daqui: o
 * {@code Assistente.responder} toma um lock justo pelo turno inteiro, e o despertar passaria a
 * disputar esse lock com o poller do Telegram. Quem bateu palmas na frente do PC não pode ficar
 * esperando uma conversa do celular terminar.
 *
 * <p><b>Reúne as fontes em paralelo</b>, e não em sequência como o briefing, porque aqui existe
 * prazo: a tela já está acesa, animando, e a fala tem de chegar em segundos. Cada fonte ganha um
 * futuro e um prazo compartilhado de {@link #PRAZO_DAS_FONTES}; a que não responder é abandonada e
 * sai da apresentação. Isso importa porque o {@code ClimaService.hoje()} é {@code synchronized} e o
 * {@code SitesService.checarTodos()} percorre os sites um a um com 10 s cada — confiar no timeout
 * interno delas deixaria o despertar refém do site mais lento.
 */
@Service
public class InicializacaoService {

    private static final Logger log = LoggerFactory.getLogger(InicializacaoService.class);

    /** Prazo somado de todas as fontes: elas partem juntas, então é o teto da coleta inteira. */
    static final Duration PRAZO_DAS_FONTES = Duration.ofSeconds(3);

    /**
     * Quanto a mesma introdução vale.
     *
     * <p>Duas palmas seguidas, ou um F5 na tela, não podem virar duas chamadas ao Claude. Dois
     * minutos é o bastante para cobrir a repetição sem deixar a introdução velha no caso real de
     * alguém acordar a tela de novo mais tarde.
     */
    static final Duration VALIDADE = Duration.ofMinutes(2);

    /** O site é considerado de pé pela mesma marca que a HUD lê no card de sistemas. */
    private static final String MARCA_DE_OK = "(OK)";

    private static final String RESERVA_SEM_DADOS = "Sistemas locais ativos, senhor.";

    private record Guardada(Introducao introducao, Instant quando) {
    }

    private final ClimaService clima;
    private final SitesService sites;
    private final ServidorService servidor;
    private final LembreteService lembretes;
    private final ProvedorDeModelo modelo;
    private final SistemaDoJarvis sistema;
    private final JsonMapper json;
    private final JarvisProperties props;
    private final Clock relogio;
    private final Duration prazoDasFontes;

    /**
     * Executor próprio, no mesmo desenho do {@code ConversaService}: o projeto não tem executor
     * compartilhado, e trazer o {@code @Async} do Spring só para quatro chamadas seria peso novo.
     */
    private final ExecutorService fontes = Executors.newVirtualThreadPerTaskExecutor();

    private Guardada guardada;

    // Com vários construtores e nenhum anotado o Spring procuraria um sem argumentos. O provedor é
    // o de apoio: o modelo do PC primeiro, com um prazo curto, porque há alguém olhando a tela.
    @Autowired
    public InicializacaoService(ClimaService clima, SitesService sites, ServidorService servidor,
            LembreteService lembretes, @Qualifier(ProvedorDeApoio.QUALIFICADOR) ProvedorDeModelo modelo,
            SistemaDoJarvis sistema, JsonMapper json, JarvisProperties props) {
        this(clima, sites, servidor, lembretes, modelo, sistema, json, props, Clock.systemUTC(),
                PRAZO_DAS_FONTES);
    }

    /** Sobre o {@link ClaudeClient}, que é como os testes a montam. */
    public InicializacaoService(ClimaService clima, SitesService sites, ServidorService servidor,
            LembreteService lembretes, ClaudeClient claude, SistemaDoJarvis sistema,
            JsonMapper json, JarvisProperties props) {
        this(clima, sites, servidor, lembretes, claude, sistema, json, props, Clock.systemUTC(),
                PRAZO_DAS_FONTES);
    }

    InicializacaoService(ClimaService clima, SitesService sites, ServidorService servidor,
            LembreteService lembretes, ClaudeClient claude, SistemaDoJarvis sistema,
            JsonMapper json, JarvisProperties props, Clock relogio, Duration prazoDasFontes) {
        this(clima, sites, servidor, lembretes, ProvedorAnthropic.sobre(claude, json), sistema,
                json, props, relogio, prazoDasFontes);
    }

    /**
     * O relógio e o prazo injetáveis são para o teste: um evita esperar dois minutos pelo cache, o
     * outro evita esperar três segundos por uma fonte propositalmente travada.
     */
    InicializacaoService(ClimaService clima, SitesService sites, ServidorService servidor,
            LembreteService lembretes, ProvedorDeModelo modelo, SistemaDoJarvis sistema,
            JsonMapper json, JarvisProperties props, Clock relogio, Duration prazoDasFontes) {
        this.clima = clima;
        this.sites = sites;
        this.servidor = servidor;
        this.lembretes = lembretes;
        this.modelo = modelo;
        this.sistema = sistema;
        this.json = json;
        this.props = props;
        this.relogio = relogio;
        this.prazoDasFontes = prazoDasFontes;
    }

    @PreDestroy
    void fechar() {
        fontes.shutdownNow();
    }

    /**
     * A introdução de agora, reaproveitando a de menos de {@link #VALIDADE}.
     *
     * <p>É {@code synchronized} de propósito: dois pedidos simultâneos — duas palmas, ou a tela
     * recarregando — devem render <b>uma</b> chamada ao Claude, e o segundo pedido sai com a
     * resposta que o primeiro acabou de guardar.
     */
    public synchronized Introducao gerar() {
        Guardada atual = guardada;
        Instant agora = relogio.instant();
        if (atual != null && Duration.between(atual.quando(), agora).compareTo(VALIDADE) < 0) {
            return atual.introducao();
        }
        Introducao introducao = montar();
        guardada = new Guardada(introducao, relogio.instant());
        return introducao;
    }

    private Introducao montar() {
        // Do relógio injetado, e não de ZonedDateTime.now(): assim a hora que entra no prompt e
        // a data que filtra os lembretes são as mesmas que o teste controla.
        ZonedDateTime agora = relogio.instant().atZone(props.zona());

        // Todas partem juntas; o prazo é contado a partir daqui, para a coleta inteira.
        Future<ClimaHoje> futuroClima = fontes.submit(clima::hoje);
        Future<List<Checagem>> futuroSites = fontes.submit(sites::checarTodos);
        Future<EstadoServidor> futuroServidor = fontes.submit(servidor::estado);
        Future<List<Lembrete>> futuroLembretes = fontes.submit(lembretes::listar);
        Instant limite = Instant.now().plus(prazoDasFontes);  // prazo real: as fontes são I/O

        ClimaHoje climaDeAgora = colher("clima", futuroClima, limite, prazoDasFontes);
        List<Checagem> checagens = colher("sites", futuroSites, limite, prazoDasFontes);
        EstadoServidor estadoDoServidor = colher("servidor", futuroServidor, limite, prazoDasFontes);
        List<Lembrete> pendentes = colher("lembretes", futuroLembretes, limite, prazoDasFontes);

        List<Lembrete> deHoje = pendentes == null
                ? List.of()
                : BriefingService.lembretesDeHoje(pendentes, agora);
        int total = checagens == null ? 0 : checagens.size();
        int ok = checagens == null ? 0
                : (int) checagens.stream().filter(c -> c.resultado().contains(MARCA_DE_OK)).count();

        List<String> problemas = problemas(checagens, estadoDoServidor);
        String texto = narrar(agora, climaDeAgora, deHoje, checagens, estadoDoServidor, problemas);

        return new Introducao(texto, ok, total, climaDeAgora != null, deHoje.size(), agora.toString());
    }

    /**
     * Pega o resultado da fonte, ou nulo.
     *
     * <p>O prazo é o que falta para o limite comum, e não três segundos para cada uma — senão
     * quatro fontes lentas somariam doze.
     */
    private static <T> T colher(String nome, Future<T> futuro, Instant limite, Duration prazo) {
        long restanteMs = Math.max(0, Duration.between(Instant.now(), limite).toMillis());
        try {
            return futuro.get(restanteMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            futuro.cancel(true);
            log.warn("Fonte '{}' não respondeu em {} ms; a introdução sai sem ela.",
                    nome, prazo.toMillis());
        } catch (ExecutionException e) {
            Throwable causa = e.getCause() == null ? e : e.getCause();
            log.warn("Fonte '{}' falhou ({}); a introdução sai sem ela.", nome, causa.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futuro.cancel(true);
        }
        return null;
    }

    /** O que está errado agora, em frases curtas. Vazio quer dizer tudo no lugar. */
    private static List<String> problemas(List<Checagem> checagens, EstadoServidor servidor) {
        List<String> achados = new ArrayList<>();
        if (checagens != null) {
            for (Checagem c : checagens) {
                if (!c.resultado().contains(MARCA_DE_OK)) {
                    achados.add(c.nome() + " está fora do ar");
                }
            }
        }
        if (servidor != null && servidor.discoTotal() > 0
                && 100.0 * servidor.discoLivre() / servidor.discoTotal() < 10) {
            achados.add("o disco do servidor está com menos de 10% livre");
        }
        return achados;
    }

    /**
     * A apresentação em si, numa chamada ao Claude e sem ferramenta nenhuma.
     *
     * <p>Falhando — e o provedor já tenta três vezes —, monta a frase em Java com o que
     * chegou. A tela acabou de acender: ficar muda seria pior que uma frase sem charme.
     */
    private String narrar(ZonedDateTime agora, ClimaHoje clima, List<Lembrete> deHoje,
            List<Checagem> checagens, EstadoServidor servidor, List<String> problemas) {
        try {
            RespostaDoModelo resposta = modelo.responder(new PedidoAoModelo(
                    SistemaDoJarvis.instrucoes(sistema.blocos(Canal.HUD)),
                    List.of(Mensagem.texto(Mensagem.Papel.USUARIO,
                            instrucao(agora, clima, deHoje, checagens, servidor, problemas))),
                    ListaDeFerramentas.vazia(), OpcoesDoPedido.de(Origem.INICIALIZACAO)),
                    r -> !extrairTexto(r.textos()).isBlank());
            String texto = extrairTexto(resposta.textos());
            if (!texto.isBlank()) {
                return texto;
            }
            log.warn("A introdução voltou vazia do Claude; fica a frase montada em Java.");
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Não consegui gerar a introdução ({}); fica a frase montada em Java.",
                    e.getMessage());
        }
        return reserva(clima, deHoje, problemas);
    }

    private String instrucao(ZonedDateTime agora, ClimaHoje clima, List<Lembrete> deHoje,
            List<Checagem> checagens, EstadoServidor servidor, List<String> problemas) {
        StringBuilder pedido = new StringBuilder();
        pedido.append("""
                A tela acabou de acender na frente de %s, que chamou você. Apresente-se.

                No máximo três frases, faladas em voz alta, no seu tom. Comece cumprimentando pelo
                horário. Use só os dados abaixo: não invente número, não comente o que faltou e não
                faça pergunta. Termine se colocando à disposição.
                """.formatted(props.nomeUsuario()));
        if (!problemas.isEmpty()) {
            pedido.append("\nATENÇÃO: comece pelos problemas, logo depois do cumprimento:\n");
            for (String problema : problemas) {
                pedido.append("- ").append(problema).append('\n');
            }
        }
        pedido.append("\nDados:\n");
        pedido.append("- hora agora: ").append(agora.toLocalTime().withSecond(0).withNano(0))
                .append('\n');
        if (clima != null) {
            pedido.append("- clima: %.0f graus em %s, %s%n"
                    .formatted(clima.temperaturaAtual(), clima.cidade(), clima.descricao()));
        }
        pedido.append("- lembretes para hoje: ").append(deHoje.size()).append('\n');
        if (checagens != null && !checagens.isEmpty()) {
            pedido.append("- sistemas monitorados: ")
                    .append(checagens.stream().filter(c -> c.resultado().contains(MARCA_DE_OK)).count())
                    .append(" de ").append(checagens.size()).append(" no ar\n");
        }
        if (servidor != null) {
            pedido.append("- ").append(servidor.curto()).append('\n');
        }
        return pedido.toString();
    }

    /** A frase determinística, para quando o Claude não responde. */
    private static String reserva(ClimaHoje clima, List<Lembrete> deHoje, List<String> problemas) {
        StringJoiner frases = new StringJoiner(" ");
        if (problemas.isEmpty()) {
            frases.add(RESERVA_SEM_DADOS);
        } else {
            frases.add("Atenção, senhor: " + String.join("; ", problemas) + ".");
        }
        if (clima != null) {
            frases.add("%.0f graus em %s.".formatted(clima.temperaturaAtual(), clima.cidade()));
        }
        frases.add(switch (deHoje.size()) {
            case 0 -> "Nenhum lembrete para hoje.";
            case 1 -> "Um lembrete para hoje.";
            default -> deHoje.size() + " lembretes para hoje.";
        });
        frases.add("Às suas ordens.");
        return frases.toString();
    }

    private static String extrairTexto(List<String> textos) {
        StringBuilder sb = new StringBuilder();
        for (String texto : textos) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(texto);
        }
        return sb.toString().trim();
    }
}
