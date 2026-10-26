package dev.jarvis.proativo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.briefing.fontes.SitesService;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.OperacoesPcService;

/**
 * Acompanha os {@code SITES_MONITORADOS} sozinho e avisa quando um site cai e quando volta.
 *
 * <p>Antes disto o {@code SitesService} só era consultado quando alguém perguntava — no briefing
 * das oito e na ferramenta {@code verificar_site}. Uma API que caísse às duas da tarde de terça só
 * apareceria no briefing de quarta.
 *
 * <p><b>Duas falhas seguidas, não uma.</b> Uma checagem HTTP sozinha erra por motivos que não são o
 * site: um deploy de trinta segundos, um DNS lento, um pico de rede na VPS. Avisar na primeira
 * treinaria o dono a ignorar o aviso, que é o único jeito de um monitor falhar de vez. A volta, ao
 * contrário, é anunciada na <b>primeira</b> checagem boa: a assimetria é de propósito — o custo de
 * demorar a dizer "voltou" é o dono preocupado à toa.
 *
 * <p>O {@code desde} que a frase afirma é o instante da <b>primeira</b> falha da sequência, não o
 * da segunda: é a hora em que o site caiu, e é o que "está fora desde 14h05" quer dizer.
 */
@Component
public class MonitorDeSites {

    private static final Logger log = LoggerFactory.getLogger(MonitorDeSites.class);

    /** Quantas falhas seguidas até o site ser dado como fora. */
    static final int FALHAS_PARA_AVISAR = 2;

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH'h'mm");

    /** O que se sabe de um site entre duas checagens. */
    private record Vigia(boolean fora, int falhasSeguidas, Instant desde, String motivo) {
        static final Vigia NOVO = new Vigia(false, 0, null, "");
    }

    private final SitesService sites;
    private final AvisosProativos avisos;
    private final FalasService falas;
    private final HistoricoDeDisponibilidade historico;
    private final Aprovacoes aprovacoes;
    private final OperacoesPcService e2;
    private final JarvisProperties props;
    private final Clock relogio;

    private final Map<String, Vigia> vigias = new HashMap<>();

    @Autowired
    public MonitorDeSites(SitesService sites, AvisosProativos avisos, FalasService falas,
            HistoricoDeDisponibilidade historico, Aprovacoes aprovacoes, OperacoesPcService e2,
            JarvisProperties props) {
        this(sites, avisos, falas, historico, aprovacoes, e2, props, Clock.systemUTC());
    }

    public MonitorDeSites(SitesService sites, AvisosProativos avisos, FalasService falas,
            HistoricoDeDisponibilidade historico, Aprovacoes aprovacoes, OperacoesPcService e2,
            JarvisProperties props, Clock relogio) {
        this.sites = sites;
        this.avisos = avisos;
        this.falas = falas;
        this.historico = historico;
        this.aprovacoes = aprovacoes;
        this.e2 = e2;
        this.props = props;
        this.relogio = relogio;
    }

    /**
     * {@code fixedDelay}, e não {@code fixedRate}: cada site tem dez segundos de timeout, e uma
     * rodada lenta não pode empilhar a seguinte em cima de si mesma.
     */
    @Scheduled(fixedDelayString = "${jarvis.proativo.sites-intervalo-minutos:5}",
            initialDelayString = "1", timeUnit = TimeUnit.MINUTES)
    public void checar() {
        if (sites.sites().isEmpty()) {
            return;
        }
        try {
            for (SitesService.Leitura leitura : sites.checarTodasAsSituacoes()) {
                avaliar(leitura);
            }
        } catch (RuntimeException e) {
            // Nunca deixa a exceção subir: o agendador do Spring desiste da tarefa que lança.
            log.warn("Falha na rodada do monitor de sites: {}", e.getMessage());
        }
    }

    private void avaliar(SitesService.Leitura leitura) {
        String nome = leitura.nome();
        Instant agora = relogio.instant();
        Vigia antes = vigias.getOrDefault(nome, Vigia.NOVO);
        historico.registrarChecagem(nome, leitura.situacao().ok());

        if (!leitura.situacao().ok()) {
            Instant desde = antes.falhasSeguidas() == 0 ? agora : antes.desde();
            int falhas = antes.falhasSeguidas() + 1;
            String motivo = leitura.situacao().motivo();
            vigias.put(nome, new Vigia(antes.fora() || falhas >= FALHAS_PARA_AVISAR, falhas, desde,
                    motivo));
            if (!antes.fora() && falhas == FALHAS_PARA_AVISAR) {
                historico.registrarQueda(nome);
                anunciarQueda(nome, desde, motivo);
            }
            return;
        }

        vigias.put(nome, Vigia.NOVO);
        if (antes.fora()) {
            Duration quanto = Duration.between(antes.desde(), agora);
            avisos.avisar("site:" + nome, "dentro", falas.frase("site_voltou", Map.of(
                    "site", nome,
                    "duracao", FalasService.duracaoFalada(quanto))), null);
        }
    }

    private void anunciarQueda(String nome, Instant desde, String motivo) {
        String texto = falas.frase("site_fora", Map.of(
                "site", nome,
                "desde", HORA.format(desde.atZone(props.zona())),
                "motivo", motivo));
        String rotina = props.acoesDeSites().get(nome);
        // O Supplier é avaliado dentro do AvisosProativos, e só se a mensagem sair agora: durante o
        // silêncio a pendência expiraria horas antes de o dono ler a pergunta.
        avisos.avisar("site:" + nome, "fora", texto,
                rotina == null ? null : () -> pedirParaRodar(nome, rotina));
    }

    /**
     * Registra a aprovação da rotina e devolve a pergunta, ou vazio se ela não pode ser feita.
     *
     * <p>Confere {@code pendenciaDe} <b>antes</b> de pedir porque o {@code Aprovacoes.pedir}
     * sobrescreve calado uma pendência do mesmo usuário com outro {@code pedidoOrigem}: sem esta
     * checagem, um site caindo no instante errado trocaria o "Posso?" que o dono está lendo por
     * outro, e o "pode" dele executaria algo que ele não leu.
     */
    private String pedirParaRodar(String nome, String rotina) {
        long usuario = props.telegram() == null ? 0 : props.telegram().usuarioPermitido();
        if (usuario == 0) {
            return "";
        }
        if (!e2.online() || e2.rotinas().stream().noneMatch(r -> r.nome().equals(rotina))) {
            log.info("Site {} caiu, mas a rotina '{}' não está disponível no PC agora", nome,
                    rotina);
            return "";
        }
        // Id sintético: nunca igual ao de uma mensagem real, então a próxima mensagem do dono
        // satisfaz a regra do "confirme em nova mensagem".
        String pedidoOrigem = "proativo:site_fora:" + nome + ":" + relogio.instant();
        if (aprovacoes.pendenciaDe(usuario, pedidoOrigem).isPresent()) {
            log.info("Site {} caiu, mas já havia uma aprovação em aberto; aviso sem a pergunta",
                    nome);
            return "";
        }
        String pergunta = "Posso rodar a rotina " + rotina + " para tentar resolver?";
        String pedido = "O site " + nome + " está fora do ar; rodar a rotina " + rotina + ".";
        try {
            // Tipo "rotina" reaproveitado de propósito: é literalmente uma rotina, a frase fixa já
            // é "confirmo rotina <código>" e o Aprovacoes.tipoNoDiario já a anota como ROTINA.
            aprovacoes.pedir("rotina", "Executar rotina " + rotina, pergunta,
                    Aprovacoes.Risco.COMUM, usuario, pedidoOrigem,
                    () -> e2.iniciarRotina(rotina, pedido, "telegram", usuario));
            return pergunta;
        } catch (IllegalArgumentException | IllegalStateException recusa) {
            log.warn("Não consegui abrir a aprovação da rotina '{}': {}", rotina,
                    recusa.getMessage());
            return "";
        }
    }
}
