package dev.jarvis.gatilhos;

import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * As regras de gatilho do dono: guardar, achar, pausar, apagar e migrar.
 *
 * <p>Quem decide <b>se</b> uma regra é criada é a ferramenta {@code criar_gatilho}, com as travas do
 * {@code conceder_permissao}; daqui para dentro a regra já é do dono. Quem decide <b>quando</b> ela
 * dispara é o {@link MotorDeGatilhos}. Este serviço só guarda, com a troca atômica do
 * {@link GatilhosEmArquivo}, e grava antes de responder: regra que o dono ouviu "Combinado" tem de
 * estar no disco.
 */
@Service
public class Gatilhos {

    private static final Logger log = LoggerFactory.getLogger(Gatilhos.class);

    /** Teto de regras por usuário: dez já é muito para alguém lembrar o que deixou ligado. */
    public static final int MAX_REGRAS = 30;
    /** "Apaga esse gatilho" logo depois de criar, com várias regras, aponta para a recém-criada. */
    static final Duration RECENTE = Duration.ofMinutes(10);

    private static final Set<String> VAZIAS = Set.of("isso", "isto", "essa", "esse", "esta", "este",
            "ela", "ele", "aquela", "aquele", "aquilo", "gatilho", "gatilhos", "regra", "regras", "que",
            "voce", "sua", "seu", "minha", "meu", "agora", "pra", "para", "com", "uma", "dos", "das",
            "apaga", "apagar", "pausa", "pausar", "desliga", "desligar", "liga", "ligar", "reativa",
            "reativar", "volta", "voltar", "ultimo", "ultima", "criei", "criou", "quando");

    /** A escolha de "esse gatilho": a regra, quando única; senão as candidatas (vazia: nenhuma). */
    public record Escolha(RegraDeGatilho regra, List<RegraDeGatilho> candidatas) {
        public boolean unica() { return regra != null; }
    }

    private final GatilhosEmArquivo arquivo;
    private final JarvisProperties props;
    private final Clock relogio;
    private final Object trava = new Object();
    private final Map<String, RegraDeGatilho> regras = new LinkedHashMap<>();
    private final Set<String> migrados = new LinkedHashSet<>();
    private final LinkedHashSet<String> vistos = new LinkedHashSet<>();
    private boolean entradaInicializada;

    @Autowired
    public Gatilhos(JarvisProperties props, JsonMapper json) {
        this(props, json, Clock.systemUTC(), props.pastaDoDiario().resolve("gatilhos.json"));
    }

    public Gatilhos(JarvisProperties props, JsonMapper json, Clock relogio, Path caminho) {
        this.props = props;
        this.relogio = relogio;
        this.arquivo = new GatilhosEmArquivo(caminho, json);
        var estado = arquivo.carregar();
        estado.regras().forEach(r -> regras.put(r.id(), r));
        migrados.addAll(estado.migrados());
        vistos.addAll(estado.vistos());
        entradaInicializada = estado.entradaInicializada();
    }

    public Instant agora() { return relogio.instant(); }
    public ZoneId zona() { return props.zona(); }

    /**
     * Grava uma regra nova, ativa. No horário calcula a primeira ocorrência: um {@code quando} que já
     * passou só vale se a regra repete, e aí ela começa na próxima.
     *
     * @throws IllegalArgumentException horário único no passado ou teto de regras
     * @throws IOException              não foi possível gravar; nada mudou
     */
    public RegraDeGatilho criar(long usuario, FonteDeGatilho fonte, Filtro filtro, String instrucao,
            String evidencia, String origem, String pedido) throws IOException {
        if (usuario <= 0) throw new IllegalArgumentException("usuário autenticado necessário");
        Instant agora = relogio.instant();
        Instant proximo = null;
        if (fonte == FonteDeGatilho.HORARIO) {
            proximo = primeiraOcorrencia(filtro, agora)
                    .orElseThrow(() -> new IllegalArgumentException("esse horário já passou"));
        }
        synchronized (trava) {
            if (regras.values().stream().filter(r -> r.usuario() == usuario).count() >= MAX_REGRAS)
                throw new IllegalArgumentException("já há " + MAX_REGRAS + " gatilhos; apague algum antes");
            String id;
            do { id = UUID.randomUUID().toString().substring(0, 8); } while (regras.containsKey(id));
            RegraDeGatilho nova = new RegraDeGatilho(id, usuario, fonte, filtro, instrucao.strip(),
                    evidencia, true, agora, null, proximo, origem, pedido);
            regras.put(id, nova);
            try {
                salvarTravado();
            } catch (IOException e) {
                regras.remove(id);
                throw e;
            }
            return nova;
        }
    }

    private Optional<Instant> primeiraOcorrencia(Filtro filtro, Instant agora) {
        ZonedDateTime vez = filtro.quando().atZone(props.zona());
        while (vez.toInstant().isBefore(agora)) {
            Optional<ZonedDateTime> seguinte = filtro.repeticao().proxima(vez);
            if (seguinte.isEmpty()) return Optional.empty();
            vez = seguinte.get();
        }
        return Optional.of(vez.toInstant());
    }

    public List<RegraDeGatilho> listar(long usuario) {
        synchronized (trava) {
            return regras.values().stream().filter(r -> r.usuario() == usuario).toList();
        }
    }

    /** As regras ativas de uma fonte, de todos os usuários (há um só, mas o filtro é de quem dispara). */
    public List<RegraDeGatilho> ativas(FonteDeGatilho fonte) {
        synchronized (trava) {
            return regras.values().stream().filter(r -> r.ativa() && r.fonte() == fonte).toList();
        }
    }

    public Optional<RegraDeGatilho> porId(String id) {
        synchronized (trava) { return Optional.ofNullable(regras.get(id)); }
    }

    /** Liga ou desliga a regra; devolve a regra como ficou. */
    public Optional<RegraDeGatilho> mudarAtiva(String id, long usuario, boolean ativa) throws IOException {
        return trocar(id, usuario, r -> r.comAtiva(ativa));
    }

    public Optional<RegraDeGatilho> apagar(String id, long usuario) throws IOException {
        synchronized (trava) {
            RegraDeGatilho r = regras.get(id);
            if (r == null || r.usuario() != usuario) return Optional.empty();
            regras.remove(id);
            try {
                salvarTravado();
            } catch (IOException e) {
                regras.put(id, r);
                throw e;
            }
            return Optional.of(r);
        }
    }

    /** O disparo grava o instante; falha de gravação não desfaz o disparo, só fica no WARN. */
    void registrarDisparo(String id, Instant quando) {
        try {
            trocar(id, 0, r -> r.comDisparo(quando));
        } catch (IOException e) {
            log.warn("Não consegui gravar o disparo do gatilho {}: {}", id, e.getMessage());
        }
    }

    /** Horário: a próxima ocorrência, ou a regra desligada quando não há próxima. */
    void avancarHorario(String id, Instant proximo) {
        try {
            trocar(id, 0, r -> proximo == null ? r.comProximo(null).comAtiva(false) : r.comProximo(proximo));
        } catch (IOException e) {
            log.warn("Não consegui gravar o próximo horário do gatilho {}: {}", id, e.getMessage());
        }
    }

    /** @param usuario zero quando quem troca é o próprio Jarvis (disparo, horário) */
    private Optional<RegraDeGatilho> trocar(String id, long usuario,
            java.util.function.UnaryOperator<RegraDeGatilho> mudanca) throws IOException {
        synchronized (trava) {
            RegraDeGatilho antes = regras.get(id);
            if (antes == null || (usuario != 0 && antes.usuario() != usuario)) return Optional.empty();
            RegraDeGatilho depois = mudanca.apply(antes);
            regras.put(id, depois);
            try {
                salvarTravado();
            } catch (IOException e) {
                regras.put(id, antes);
                throw e;
            }
            return Optional.of(depois);
        }
    }

    /**
     * "Apaga esse gatilho", "pausa o do PC": acha a regra sem o modelo inventar id. Vazio ou dêitico
     * com uma regra só é ela; com várias, a criada há menos de dez minutos (o "esse" de quem acabou
     * de ouvir o "Combinado"); senão, palavras da instrução e do evento, e empate volta ao modelo.
     */
    public Escolha resolver(long usuario, String referencia) {
        List<RegraDeGatilho> pool = listar(usuario);
        String ref = referencia == null ? "" : referencia.strip();
        for (RegraDeGatilho r : pool) if (r.id().equals(ref)) return new Escolha(r, List.of(r));
        List<String> palavras = palavras(ref).stream().filter(p -> !VAZIAS.contains(p)).toList();
        if (palavras.isEmpty()) {
            if (pool.size() == 1) return new Escolha(pool.getFirst(), pool);
            Instant limite = relogio.instant().minus(RECENTE);
            var recente = pool.stream().filter(r -> r.criadaEm().isAfter(limite))
                    .max(Comparator.comparing(RegraDeGatilho::criadaEm));
            return recente.map(r -> new Escolha(r, List.of(r))).orElse(new Escolha(null, pool));
        }
        int melhor = 0;
        List<RegraDeGatilho> empatadas = new ArrayList<>();
        for (RegraDeGatilho r : pool) {
            List<String> daRegra = palavras(r.instrucao() + " " + r.evento() + " " + r.fonte().chave());
            int pontos = (int) palavras.stream().filter(p -> daRegra.stream().anyMatch(o -> casa(p, o))).count();
            if (pontos == 0 || pontos < melhor) continue;
            if (pontos > melhor) { melhor = pontos; empatadas.clear(); }
            empatadas.add(r);
        }
        return empatadas.size() == 1 ? new Escolha(empatadas.getFirst(), empatadas) : new Escolha(null, empatadas);
    }

    private static boolean casa(String a, String b) {
        return a.equals(b) || (a.length() >= 5 && b.length() >= 5 && a.regionMatches(0, b, 0, 5));
    }

    static List<String> palavras(String texto) {
        String semAcento = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        List<String> lista = new ArrayList<>();
        for (String p : semAcento.split("[^a-z0-9]+")) if (p.length() >= 2) lista.add(p);
        return lista;
    }

    // ---------------------------------------------------------------- pasta entrada

    public boolean entradaInicializada() {
        synchronized (trava) { return entradaInicializada; }
    }

    /** Os IDs ainda não vistos, em ordem; não marca nada. */
    public List<String> naoVistos(List<String> ids) {
        synchronized (trava) { return ids.stream().filter(id -> !vistos.contains(id)).toList(); }
    }

    /** Lembra os IDs e marca a linha de base como feita. */
    public void marcarVistos(List<String> ids) {
        synchronized (trava) {
            boolean mudou = !entradaInicializada;
            entradaInicializada = true;
            for (String id : ids) mudou |= vistos.add(id);
            while (vistos.size() > GatilhosEmArquivo.MAX_VISTOS) vistos.remove(vistos.iterator().next());
            if (!mudou) return;
            try {
                salvarTravado();
            } catch (IOException e) {
                log.warn("Não consegui gravar os arquivos vistos na pasta entrada: {}", e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- migração do SITES_ACOES

    /**
     * Cada par {@code site=rotina} do {@code SITES_ACOES} vira uma regra equivalente, uma vez só: o
     * par vai para {@code migrados} junto, e a regra que o dono apagar não volta na partida
     * seguinte. Sem usuário configurado não há dono para a regra, e nada é migrado.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrarSitesAcoes() {
        long usuario = props.telegram() == null ? 0 : props.telegram().usuarioPermitido();
        Map<String, String> pares = props.acoesDeSites();
        if (usuario <= 0 || pares.isEmpty()) return;
        synchronized (trava) {
            boolean mudou = false;
            for (var par : pares.entrySet()) {
                String chave = par.getKey() + "=" + par.getValue();
                if (migrados.contains(chave)) continue;
                String id;
                do { id = UUID.randomUUID().toString().substring(0, 8); } while (regras.containsKey(id));
                regras.put(id, new RegraDeGatilho(id, usuario, FonteDeGatilho.SITE_CAIU,
                        new Filtro(par.getKey(), null, null, null, null, null),
                        "Quando " + par.getKey() + " cair, pergunte se pode rodar a rotina " + par.getValue() + ".",
                        "SITES_ACOES: " + chave, true, relogio.instant(), null, null,
                        RegraDeGatilho.ORIGEM_SITES_ACOES, ""));
                migrados.add(chave);
                mudou = true;
                log.info("SITES_ACOES: {} virou o gatilho {}", chave, id);
            }
            if (!mudou) return;
            try {
                salvarTravado();
            } catch (IOException e) {
                log.warn("Não consegui gravar a migração do SITES_ACOES; tento na próxima partida: {}", e.getMessage());
            }
        }
    }

    private void salvarTravado() throws IOException {
        arquivo.salvar(new GatilhosEmArquivo.Estado(List.copyOf(regras.values()), Set.copyOf(migrados),
                entradaInicializada, List.copyOf(vistos)));
    }
}
