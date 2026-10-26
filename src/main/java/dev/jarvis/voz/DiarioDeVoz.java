package dev.jarvis.voz;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.ArquivoAtomico;
import dev.jarvis.util.Json;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Quanto de voz cada dia consumiu — e é daqui que sai a projeção do aviso antecipado de cota.
 *
 * <p>Os caracteres sintetizados ficam num <b>registro por dia</b>, num JSON só, com a troca atômica
 * do {@link ArquivoAtomico}. Antes isto era um contador em memória que zerava à meia-noite e virava
 * uma linha de log: bastava para responder "por que a cota sumiu tão rápido?" olhando o log, mas
 * não dá para projetar nada a partir de um número que o restart leva embora.
 *
 * <p>Acertos de cache e trocas de provedor continuam <b>só no log</b>, zerando a cada dia: eles
 * explicam o gasto, não o projetam.
 */
@Component
public class DiarioDeVoz {

    private static final Logger log = LoggerFactory.getLogger(DiarioDeVoz.class);

    /**
     * A pasta do uso diário.
     *
     * <p><b>Subpasta de propósito.</b> O volume é o mesmo {@code /data/voz} do cache de áudio, mas
     * {@code CacheDeVoz.descartarExcedente()} lista todo arquivo <i>regular</i> da pasta e apaga do
     * menos usado para o mais usado até caber no teto — um {@code uso-diario.json} solto ali sairia
     * junto com os MP3 no dia em que o cache estourasse, e o {@code .bak} com ele. {@code
     * Files.list} não desce em subpasta, então {@code uso/} é intocado. Constante na classe, como o
     * {@code CacheDeVoz.PADRAO}: não vale uma configuração nova para uma pasta dentro de um volume
     * que já está montado.
     */
    private static final Path PADRAO = Path.of("/data/voz/uso");

    private static final String NOME_DO_ARQUIVO = "uso-diario.json";

    /** Teto de sanidade: são 35 linhas de "data: número". */
    private static final long MAX_BYTES = 64_000;

    /**
     * Quantos dias ficam no arquivo. Um ciclo de cobrança mais folga — e constante, não
     * configuração, pela regra que o {@code DiarioDeAcoes} já documenta.
     */
    static final int DIAS_GUARDADOS = 35;

    /** Quantos dias para trás a média olha: "no ritmo atual" é o ritmo recente, não o do mês. */
    static final int JANELA_DA_MEDIA = 7;

    /** Mínimo de dias completos com registro para haver média. Com um dia só, não há ritmo. */
    static final int MINIMO_DE_DIAS = 2;

    private final JarvisProperties props;
    private final JsonMapper json;
    private final Clock relogio;
    private final Path arquivo;

    /** Protege o mapa dos dias e o marcador; a gravação em disco roda fora do caminho da fala. */
    private final Object trava = new Object();
    private final Map<LocalDate, Long> dias = new TreeMap<>();
    private String avisoDoPeriodo = "";
    private boolean sujo;

    private final AtomicLong acertosDeCache = new AtomicLong();
    private final AtomicLong trocas = new AtomicLong();

    @Autowired
    public DiarioDeVoz(JarvisProperties props, JsonMapper json) {
        this(props, json, Clock.systemUTC(), PADRAO);
    }

    /** O relógio e a pasta entram por aqui nos testes, como no {@code CacheDeVoz}. */
    public DiarioDeVoz(JarvisProperties props, JsonMapper json, Clock relogio, Path pasta) {
        this.props = props;
        this.json = json;
        this.relogio = relogio;
        this.arquivo = pasta.toAbsolutePath().normalize().resolve(NOME_DO_ARQUIVO);
        carregar();
    }

    // ---------------------------------------------------------------- registro

    /**
     * Soma o que acabou de ser sintetizado no <b>dia de hoje</b>, e volta sem tocar no disco.
     *
     * <p>Somar direto no dia, em vez de num contador que só vira registro à meia-noite, é o que faz
     * um restart às 23h não apagar o dia inteiro — e o dia inteiro é o insumo da projeção.
     */
    public void sintetizou(int caracteresEnviados) {
        if (caracteresEnviados <= 0) {
            return;
        }
        synchronized (trava) {
            dias.merge(hoje(), (long) caracteresEnviados, (antes, agora) -> antes + agora);
            sujo = true;
        }
    }

    public void acertouCache() {
        acertosDeCache.incrementAndGet();
    }

    public void trocouDeProvedor() {
        trocas.incrementAndGet();
    }

    // ---------------------------------------------------------------- leitura

    /**
     * Média de caracteres por dia dos dias completos recentes, ou vazio quando não dá para afirmar.
     *
     * <p>Duas regras, e as duas existem para a projeção não mentir:
     *
     * <ul>
     *   <li><b>Hoje não entra.</b> É um dia pela metade, e meio dia de uso puxaria a projeção para
     *       longe justamente na hora de avisar.</li>
     *   <li><b>Dia sem registro é dia omitido</b>, nunca dia com zero — a regra do {@code
     *       HistoricoDeDisponibilidade} e do {@code MedidorDeCusto.serieDiaria}. Zero ali afirmaria
     *       "não falei nada" onde a verdade pode ser "eu estava desligado". Por isso a soma é
     *       dividida pelos dias <i>com</i> registro, não pelos dias do calendário.</li>
     * </ul>
     *
     * <p>Com menos de {@link #MINIMO_DE_DIAS} dias completos o retorno é vazio, e quem avisa omite a
     * projeção em vez de chutar uma data.
     */
    public OptionalDouble mediaDiaria() {
        LocalDate hoje = hoje();
        LocalDate inicio = hoje.minusDays(JANELA_DA_MEDIA);
        long soma = 0;
        int quantos = 0;
        synchronized (trava) {
            for (Map.Entry<LocalDate, Long> dia : dias.entrySet()) {
                if (!dia.getKey().isBefore(hoje) || dia.getKey().isBefore(inicio)
                        || dia.getValue() == null || dia.getValue() <= 0) {
                    continue;
                }
                soma += dia.getValue();
                quantos++;
            }
        }
        return quantos < MINIMO_DE_DIAS ? OptionalDouble.empty()
                : OptionalDouble.of((double) soma / quantos);
    }

    /** Quanto o dia consumiu, em caracteres. Zero quando não há registro dele. */
    public long usoDoDia(LocalDate dia) {
        synchronized (trava) {
            return dias.getOrDefault(dia, 0L);
        }
    }

    /**
     * O período de cobrança cujo aviso antecipado já saiu, ou vazio.
     *
     * <p>Mora aqui, no mesmo arquivo da série, porque é o par dela: guardar a série sem o marcador
     * deixaria o aviso sair de novo a cada restart, e guardar o marcador sem a série deixaria a
     * projeção discordar do aviso que ela mesma gerou. Um arquivo, um dono.
     */
    public String avisoDoPeriodo() {
        synchronized (trava) {
            return avisoDoPeriodo;
        }
    }

    /** Anota que o aviso deste período já saiu, e grava na hora: perder isto custa uma repetição. */
    public void marcarAvisoDoPeriodo(String periodo) {
        synchronized (trava) {
            avisoDoPeriodo = periodo == null ? "" : periodo;
            sujo = true;
        }
        gravarPendente();
    }

    // ---------------------------------------------------------------- disco e dia

    /**
     * Fecha o dia que acabou: a linha de log de sempre, mais o expurgo e a gravação.
     *
     * <p>O log continua existindo porque é o que responde "por que a cota sumiu tão rápido?" sem
     * abrir o painel da ElevenLabs — e agora o número dele vem do registro do dia, não de um
     * contador que zera.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "${jarvis.fuso-horario}")
    public void fecharODia() {
        LocalDate ontem = hoje().minusDays(1);
        long enviados = usoDoDia(ontem);
        long acertos = acertosDeCache.getAndSet(0);
        long trocou = trocas.getAndSet(0);
        if (enviados > 0 || acertos > 0 || trocou > 0) {
            log.info("Voz em {}: {} caracteres sintetizados, {} acertos de cache, {} trocas de "
                    + "provedor", ontem, enviados, acertos, trocou);
        }
        expurgar();
        gravarPendente();
    }

    /** Grava o que mudou desde a última vez. A fala nunca espera o disco. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void gravarPendente() {
        String conteudo;
        synchronized (trava) {
            if (!sujo) {
                return;
            }
            conteudo = json.writeValueAsString(retrato());
            sujo = false;
        }
        try {
            ArquivoAtomico.gravar(arquivo, conteudo);
        } catch (IOException | RuntimeException e) {
            synchronized (trava) {
                sujo = true;
            }
            log.warn("Não consegui gravar o uso de voz: {}", e.getMessage());
        }
    }

    private ObjectNode retrato() {
        ObjectNode raiz = json.createObjectNode();
        raiz.put("fuso", props.zona().getId());
        raiz.put("gravadoEm", Instant.now(relogio).toString());
        raiz.put("avisoDoPeriodo", avisoDoPeriodo);
        ObjectNode porDia = raiz.putObject("dias");
        dias.forEach((dia, caracteres) -> porDia.put(dia.toString(), caracteres));
        return raiz;
    }

    /** O arquivo, ou o {@code .bak}, ou nada: um uso ilegível não impede o Jarvis de subir. */
    private void carregar() {
        if (ler(arquivo)) {
            return;
        }
        Path backup = ArquivoAtomico.backupDe(arquivo);
        if (Files.exists(backup) && ler(backup)) {
            log.warn("Uso de voz recuperado do backup .bak");
        }
    }

    private boolean ler(Path deOnde) {
        if (!Files.exists(deOnde)) {
            return false;
        }
        try {
            JsonNode raiz = json.readTree(ArquivoAtomico.ler(deOnde, MAX_BYTES));
            if (!raiz.isObject()) {
                throw new IOException("raiz do uso de voz não é um objeto JSON");
            }
            synchronized (trava) {
                avisoDoPeriodo = Json.texto(raiz.path("avisoDoPeriodo"));
                for (Map.Entry<String, JsonNode> entrada : raiz.path("dias").properties()) {
                    try {
                        dias.put(LocalDate.parse(entrada.getKey()),
                                (long) Json.numero(entrada.getValue()));
                    } catch (RuntimeException naoEhDia) {
                        // Chave que não é data: fica de fora, sem derrubar o resto do arquivo.
                        log.warn("Uso de voz com dia inválido '{}'; ignorado", entrada.getKey());
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Uso de voz em {} ilegível: {}", deOnde.getFileName(), e.getMessage());
            return false;
        }
    }

    private void expurgar() {
        LocalDate maisAntigo = hoje().minusDays(DIAS_GUARDADOS - 1L);
        synchronized (trava) {
            List<LocalDate> velhos = new ArrayList<>();
            dias.keySet().forEach(dia -> {
                if (dia.isBefore(maisAntigo)) {
                    velhos.add(dia);
                }
            });
            if (velhos.isEmpty()) {
                return;
            }
            velhos.forEach(dias::remove);
            sujo = true;
        }
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(relogio.instant(), props.zona());
    }

    @PreDestroy
    public void fechar() {
        gravarPendente();
    }
}
