package dev.jarvis.autonomia;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.OrigemDaAcao;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.util.ArquivoAtomico;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * As permissões que o dono deu, e o que o servidor precisa lembrar para oferecer uma nova.
 *
 * <p>Mora em {@code DIARIO_PASTA/concessoes.json}, ao lado do {@code freio.json}, com a troca
 * atômica e o {@code .bak} do {@link ArquivoAtomico}. O {@code config/autonomia.md} continua só
 * leitura: isto aqui é o que o <b>Jarvis</b> grava, e por isso tem as próprias travas — quem cria
 * uma concessão é a {@code conceder_permissao} (depois de conferir a mensagem do dono) ou o "sim" a
 * uma pendência {@code conceder_permanente} do {@code Aprovacoes}. Quem consulta é a
 * {@link PoliticaDeAutonomia}, depois do arquivo e antes do padrão, e sempre respeitando o piso.
 *
 * <p>Três coisas no mesmo JSON:
 * <ul>
 *   <li><b>concessoes</b> — as permissões em vigor. A que venceu sai na próxima consulta e entra no
 *       diário como expirada: não há tarefa que precise correr na hora certa, porque uma concessão
 *       vencida nunca é usada (o {@link #vigente} confere o relógio de novo);</li>
 *   <li><b>aprovacoes</b> — os "sim" e "não" a pendências do catálogo, por (chave, alvo), dos
 *       últimos {@link #JANELA_DAS_APROVACOES}. É daí que sai "é a terceira vez que você aprova";</li>
 *   <li><b>ofertasRecusadas</b> — o "não" à oferta, que a cala por {@link #BLOQUEIO_DA_OFERTA}.</li>
 * </ul>
 *
 * <p><b>Arquivo ilegível vale "sem concessões"</b>: o Jarvis volta a perguntar, que é o lado
 * seguro. Primeiro tenta o {@code .bak}.
 */
@Component
public class Concessoes {

    private static final Logger log = LoggerFactory.getLogger(Concessoes.class);

    public static final String NOME_DO_ARQUIVO = "concessoes.json";

    /** Quantos "sim" seguidos, sem nenhum "não" no meio, fazem o Jarvis oferecer parar de perguntar. */
    public static final int APROVACOES_PARA_OFERECER = 3;
    /** Até onde a contagem olha para trás. */
    public static final Duration JANELA_DAS_APROVACOES = Duration.ofDays(30);
    /** Quanto tempo um "não" à oferta a cala. */
    public static final Duration BLOQUEIO_DA_OFERTA = Duration.ofDays(30);

    /** Teto de sanidade do arquivo; um dono tem poucas concessões e algumas dezenas de aprovações. */
    private static final long MAX_BYTES = 256 * 1024;
    /** Teto da lista de aprovações, além da janela: um laço não pode crescer o arquivo sem fim. */
    private static final int MAX_APROVACOES = 500;
    private static final int MAX_EVIDENCIA = 300;

    /** Um "sim", um "não" ou o instante em que a oferta foi feita (que zera a sequência). */
    private record Aprovacao(String chave, String alvo, Instant instante, String tipo) {
        static final String SIM = "sim", NAO = "nao", OFERTA = "oferta";

        boolean de(AcaoAutonoma acao, String alvoPedido) {
            return acao.chave().equals(chave) && java.util.Objects.equals(alvo, alvoPedido);
        }
    }

    private record Bloqueio(String chave, String alvo, Instant ate) {
        boolean de(AcaoAutonoma acao, String alvoPedido) {
            return acao.chave().equals(chave) && java.util.Objects.equals(alvo, alvoPedido);
        }
    }

    private final JsonMapper json;
    private final Clock relogio;
    private final ZoneId zona;
    private final Path arquivo;
    /** Pode ser nulo: quem monta à mão sem diário (testes) só não ganha as linhas. */
    private final DiarioDeAcoes diario;

    private final List<Concessao> concessoes = new ArrayList<>();
    private final List<Aprovacao> aprovacoes = new ArrayList<>();
    private final List<Bloqueio> bloqueios = new ArrayList<>();

    @Autowired
    public Concessoes(JarvisProperties props, JsonMapper json, DiarioDeAcoes diario) {
        this(json, Clock.systemUTC(), props.zona(),
                props.pastaDoDiario().resolve(NOME_DO_ARQUIVO), diario);
    }

    public Concessoes(JsonMapper json, Clock relogio, ZoneId zona, Path arquivo, DiarioDeAcoes diario) {
        this.json = json;
        this.relogio = relogio;
        this.zona = zona;
        this.arquivo = arquivo;
        this.diario = diario;
        carregar();
    }

    // ---------------------------------------------------------------- consulta

    /** A concessão que libera esta ação agora numa conversa, preferindo a que nomeia o alvo. */
    public synchronized Optional<Concessao> vigente(AcaoAutonoma acao, String alvo) {
        expurgar();
        Instant agora = relogio.instant();
        String alvoPedido = alvo == null || alvo.isEmpty() ? null : alvo;
        return concessoes.stream()
                .filter(c -> c.valeNaConversa(agora) && c.cobre(acao, alvoPedido))
                .min(Comparator.comparing((Concessao c) -> c.alvo() == null));
    }

    /** As que estão de pé (inclusive as de tarefa), da que vence primeiro para a permanente. */
    public synchronized List<Concessao> ativas() {
        expurgar();
        return concessoes.stream()
                .sorted(Comparator.comparing((Concessao c) -> c.ate() == null ? Instant.MAX : c.ate()))
                .toList();
    }

    /** "até hoje às 23:59", "até sexta, 07/11, às 23:59" ou "sem prazo" — vai para voz. */
    public String prazo(Concessao c) {
        return switch (c.escopo()) {
            case PERMANENTE -> "sem prazo";
            case TAREFA -> "enquanto durar a tarefa " + c.idTarefa();
            case ATE -> "até " + quando(c.ate());
        };
    }

    /** O instante em português, relativo a hoje no fuso do Jarvis. */
    public String quando(Instant instante) {
        ZonedDateTime alvo = instante.atZone(zona);
        LocalDate hoje = LocalDate.ofInstant(relogio.instant(), zona);
        String hora = String.format("%02d:%02d", alvo.getHour(), alvo.getMinute());
        LocalDate dia = alvo.toLocalDate();
        if (dia.equals(hoje)) return "hoje às " + hora;
        if (dia.equals(hoje.plusDays(1))) return "amanhã às " + hora;
        String semana = alvo.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.of("pt", "BR"))
                .replace("-feira", "");
        return semana + ", " + String.format("%02d/%02d", dia.getDayOfMonth(), dia.getMonthValue())
                + ", às " + hora;
    }

    public Instant agora() {
        return relogio.instant();
    }

    // ---------------------------------------------------------------- escrita

    /**
     * Grava uma concessão nova e anota no diário. As travas de <b>quem</b> pode pedir ficam com quem
     * chama; aqui ficam as que não dependem disso: chave do catálogo, alvo aceito, piso que permite
     * fazer sem perguntar e prazo no futuro.
     *
     * @throws IllegalArgumentException concessão que nunca poderia valer
     * @throws IOException              não gravou; nada mudou em memória
     */
    public synchronized Concessao conceder(AcaoAutonoma acao, String alvo, Concessao.Escopo escopo,
            Instant ate, String idTarefa, Concessao.Origem origem, String evidencia, long usuario,
            String canal) throws IOException {
        String alvoValido = alvo == null || alvo.isBlank() ? null : alvo.strip();
        if (alvoValido != null && (!acao.aceitaAlvo() || !AcaoAutonoma.alvoValido(alvoValido)))
            throw new IllegalArgumentException("alvo inválido para " + acao.chave());
        if (acao.piso() != Nivel.FAZ_E_AVISA)
            throw new IllegalArgumentException(acao.chave() + " sempre pergunta");
        Instant agora = relogio.instant();
        if (escopo == Concessao.Escopo.ATE && (ate == null || !ate.isAfter(agora)))
            throw new IllegalArgumentException("prazo no passado");
        if (escopo == Concessao.Escopo.TAREFA && (idTarefa == null || idTarefa.isBlank()))
            throw new IllegalArgumentException("concessão de tarefa sem tarefa");
        Concessao nova = new Concessao(UUID.randomUUID().toString().substring(0, 8), acao.chave(),
                alvoValido, escopo, escopo == Concessao.Escopo.ATE ? ate : null,
                escopo == Concessao.Escopo.TAREFA ? idTarefa : null, origem,
                cortar(evidencia), agora, null);
        concessoes.add(nova);
        try {
            salvar();
        } catch (IOException e) {
            concessoes.remove(nova);
            throw e;
        }
        anotar(TipoDeAcao.CONCESSAO_CRIADA, "Permissão para " + nova.oQue() + " sem perguntar, "
                + prazo(nova) + " (" + origem.chave() + ")", canal, usuario);
        return nova;
    }

    /** Marca o primeiro uso e anota no diário; dos usos seguintes ninguém precisa saber. */
    public synchronized void marcarUso(String id, long usuario, String canal) {
        for (int i = 0; i < concessoes.size(); i++) {
            Concessao c = concessoes.get(i);
            if (!c.id().equals(id) || c.usadaEm() != null) continue;
            concessoes.set(i, c.usadaAgora(relogio.instant()));
            salvarSemLancar();
            anotar(TipoDeAcao.CONCESSAO_USADA, "Usei pela primeira vez a permissão para "
                    + c.oQue() + " (" + prazo(c) + ")", canal, usuario);
            return;
        }
    }

    /**
     * Tira as concessões que casam. Sem ação, todas; com ação e sem alvo, todas daquela ação; com
     * alvo, só as daquele alvo e as da ação inteira — "volta a perguntar antes da rotina X" não pode
     * deixar de pé uma permissão para todas as rotinas, senão a X continuaria sem pergunta.
     *
     * <p>Revogar também cala a oferta aprendida por {@link #BLOQUEIO_DA_OFERTA}: o dono acabou de
     * dizer que quer ser perguntado.
     */
    public synchronized List<Concessao> revogar(AcaoAutonoma acao, String alvo, long usuario,
            String canal) {
        expurgar();
        String alvoPedido = alvo == null || alvo.isBlank() ? null : alvo.strip();
        List<Concessao> saem = concessoes.stream().filter(c -> acao == null
                || (alvoPedido == null ? acao.chave().equals(c.chave()) : c.cobre(acao, alvoPedido)))
                .toList();
        if (saem.isEmpty()) return saem;
        concessoes.removeAll(saem);
        Instant ate = relogio.instant().plus(BLOQUEIO_DA_OFERTA);
        for (Concessao c : saem) {
            bloqueios.removeIf(b -> b.chave().equals(c.chave()) && java.util.Objects.equals(b.alvo(), c.alvo()));
            bloqueios.add(new Bloqueio(c.chave(), c.alvo(), ate));
        }
        salvarSemLancar();
        for (Concessao c : saem) {
            anotar(TipoDeAcao.CONCESSAO_REVOGADA, "Revoguei a permissão para " + c.oQue()
                    + " (" + prazo(c) + ")", canal, usuario);
        }
        return saem;
    }

    // ---------------------------------------------------------------- aprendida

    /**
     * Conta um "sim" a uma pendência desta ação e diz se é hora de oferecer parar de perguntar:
     * {@link #APROVACOES_PARA_OFERECER} "sim" seguidos dentro da janela, nenhum "não" e nenhuma
     * oferta depois do mais antigo deles, piso que permite, sem bloqueio e sem concessão em vigor.
     * Não marca a oferta — quem a faz chama {@link #registrarOferta} se conseguiu abrir a pendência.
     */
    public synchronized boolean registrarAprovacao(AcaoAutonoma acao, String alvo) {
        String alvoPedido = alvo == null || alvo.isEmpty() ? null : alvo;
        acrescentar(new Aprovacao(acao.chave(), alvoPedido, relogio.instant(), Aprovacao.SIM));
        salvarSemLancar();
        if (acao.piso() != Nivel.FAZ_E_AVISA || bloqueada(acao, alvoPedido)) return false;
        if (vigente(acao, alvoPedido).isPresent()) return false;
        return sequencia(acao, alvoPedido) >= APROVACOES_PARA_OFERECER;
    }

    /** Um "não" a uma pendência desta ação: a sequência volta a zero. */
    public synchronized void registrarRecusa(AcaoAutonoma acao, String alvo) {
        acrescentar(new Aprovacao(acao.chave(), alvo == null || alvo.isEmpty() ? null : alvo,
                relogio.instant(), Aprovacao.NAO));
        salvarSemLancar();
    }

    /** A oferta foi feita: a sequência recomeça, e uma oferta ignorada só volta após mais três "sim". */
    public synchronized void registrarOferta(AcaoAutonoma acao, String alvo) {
        acrescentar(new Aprovacao(acao.chave(), alvo == null || alvo.isEmpty() ? null : alvo,
                relogio.instant(), Aprovacao.OFERTA));
        salvarSemLancar();
    }

    /** "Não" à oferta: não oferece de novo por {@link #BLOQUEIO_DA_OFERTA}. */
    public synchronized void recusarOferta(AcaoAutonoma acao, String alvo) {
        String alvoPedido = alvo == null || alvo.isEmpty() ? null : alvo;
        bloqueios.removeIf(b -> b.de(acao, alvoPedido));
        bloqueios.add(new Bloqueio(acao.chave(), alvoPedido, relogio.instant().plus(BLOQUEIO_DA_OFERTA)));
        salvarSemLancar();
    }

    private boolean bloqueada(AcaoAutonoma acao, String alvo) {
        Instant agora = relogio.instant();
        return bloqueios.stream().anyMatch(b -> b.de(acao, alvo) && agora.isBefore(b.ate()));
    }

    /** Os "sim" desde o último "não" ou oferta, dentro da janela. */
    private int sequencia(AcaoAutonoma acao, String alvo) {
        Instant desde = relogio.instant().minus(JANELA_DAS_APROVACOES);
        int sim = 0;
        for (int i = aprovacoes.size() - 1; i >= 0; i--) {
            Aprovacao a = aprovacoes.get(i);
            if (!a.de(acao, alvo)) continue;
            if (a.instante().isBefore(desde) || !Aprovacao.SIM.equals(a.tipo())) break;
            sim++;
        }
        return sim;
    }

    private void acrescentar(Aprovacao aprovacao) {
        aprovacoes.add(aprovacao);
        Instant desde = relogio.instant().minus(JANELA_DAS_APROVACOES);
        aprovacoes.removeIf(a -> a.instante().isBefore(desde));
        while (aprovacoes.size() > MAX_APROVACOES) aprovacoes.removeFirst();
    }

    // ---------------------------------------------------------------- expiração

    /**
     * De tempos em tempos, para a linha "permissão expirada" sair perto da hora mesmo sem ninguém
     * conversar. Não é ela que impede o uso: o {@link #vigente} confere o relógio sozinho.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public synchronized void expurgar() {
        Instant agora = relogio.instant();
        List<Concessao> vencidas = concessoes.stream().filter(c -> c.vencida(agora)).toList();
        boolean mudou = !vencidas.isEmpty();
        concessoes.removeAll(vencidas);
        mudou |= bloqueios.removeIf(b -> !agora.isBefore(b.ate()));
        if (!mudou) return;
        salvarSemLancar();
        for (Concessao c : vencidas) {
            anotar(TipoDeAcao.CONCESSAO_EXPIRADA, "Expirou a permissão para " + c.oQue()
                    + " (valia até " + quando(c.ate()) + ")", "", 0);
        }
    }

    // ---------------------------------------------------------------- disco

    private void anotar(TipoDeAcao tipo, String descricao, String canal, long usuario) {
        if (diario == null) return;
        try {
            diario.registrar(tipo, descricao, OrigemDaAcao.porCanal(canal), usuario, null);
        } catch (RuntimeException e) {
            log.warn("Concessão: não entrou no diário ({})", e.getMessage());
        }
    }

    private void salvarSemLancar() {
        try {
            salvar();
        } catch (IOException e) {
            log.warn("Concessões: não consegui gravar {} ({})", arquivo, e.getMessage());
        }
    }

    private void salvar() throws IOException {
        ObjectNode raiz = json.createObjectNode().put("versao", 1);
        ArrayNode lista = raiz.putArray("concessoes");
        for (Concessao c : concessoes) {
            ObjectNode no = lista.addObject().put("id", c.id()).put("chave", c.chave());
            if (c.alvo() != null) no.put("alvo", c.alvo());
            no.put("escopo", c.escopo().chave());
            if (c.ate() != null) no.put("ate", c.ate().toString());
            if (c.idTarefa() != null) no.put("idTarefa", c.idTarefa());
            no.put("origem", c.origem().chave()).put("evidencia", c.evidencia())
                    .put("criadaEm", c.criadaEm().toString());
            if (c.usadaEm() != null) no.put("usadaEm", c.usadaEm().toString());
        }
        ArrayNode feitas = raiz.putArray("aprovacoes");
        for (Aprovacao a : aprovacoes) {
            ObjectNode no = feitas.addObject().put("chave", a.chave());
            if (a.alvo() != null) no.put("alvo", a.alvo());
            no.put("instante", a.instante().toString()).put("tipo", a.tipo());
        }
        ArrayNode recusadas = raiz.putArray("ofertasRecusadas");
        for (Bloqueio b : bloqueios) {
            ObjectNode no = recusadas.addObject().put("chave", b.chave());
            if (b.alvo() != null) no.put("alvo", b.alvo());
            no.put("ate", b.ate().toString());
        }
        ArquivoAtomico.gravar(arquivo, json.writeValueAsString(raiz));
    }

    private void carregar() {
        if (arquivo == null) return;
        for (Path candidato : List.of(arquivo, ArquivoAtomico.backupDe(arquivo))) {
            if (!Files.exists(candidato)) continue;
            try {
                ler(json.readTree(ArquivoAtomico.ler(candidato, MAX_BYTES)));
                return;
            } catch (IOException | RuntimeException e) {
                concessoes.clear(); aprovacoes.clear(); bloqueios.clear();
                log.warn("Concessões ilegíveis em {}; tentando a cópia ({})", candidato, e.getMessage());
            }
        }
    }

    /** Linha que não serve (chave fora do catálogo, piso que não deixa, prazo torto) é descartada. */
    private void ler(JsonNode raiz) {
        int descartadas = 0;
        for (JsonNode no : raiz.path("concessoes")) {
            try {
                AcaoAutonoma acao = AcaoAutonoma.porChave(Json.texto(no.path("chave")));
                String alvo = opcional(no, "alvo");
                Concessao.Escopo escopo = Concessao.Escopo.porChave(Json.texto(no.path("escopo")));
                Concessao.Origem origem = Concessao.Origem.porChave(Json.texto(no.path("origem")));
                if (acao == null || escopo == null || origem == null || acao.piso() != Nivel.FAZ_E_AVISA
                        || (alvo != null && (!acao.aceitaAlvo() || !AcaoAutonoma.alvoValido(alvo)))) {
                    descartadas++;
                    continue;
                }
                Instant ate = instante(opcional(no, "ate"));
                String idTarefa = opcional(no, "idTarefa");
                if ((escopo == Concessao.Escopo.ATE && ate == null)
                        || (escopo == Concessao.Escopo.TAREFA && idTarefa == null)) {
                    descartadas++;
                    continue;
                }
                Instant criadaEm = instante(Json.texto(no.path("criadaEm")));
                String id = Json.texto(no.path("id"));
                concessoes.add(new Concessao(id.isBlank() ? UUID.randomUUID().toString().substring(0, 8) : id,
                        acao.chave(), alvo, escopo, ate, idTarefa, origem,
                        Json.texto(no.path("evidencia")),
                        criadaEm == null ? relogio.instant() : criadaEm,
                        instante(opcional(no, "usadaEm"))));
            } catch (RuntimeException e) {
                descartadas++;
            }
        }
        for (JsonNode no : raiz.path("aprovacoes")) {
            Instant quando = instante(Json.texto(no.path("instante")));
            String tipo = Json.texto(no.path("tipo"));
            if (quando != null && List.of(Aprovacao.SIM, Aprovacao.NAO, Aprovacao.OFERTA).contains(tipo))
                aprovacoes.add(new Aprovacao(Json.texto(no.path("chave")), opcional(no, "alvo"), quando, tipo));
        }
        for (JsonNode no : raiz.path("ofertasRecusadas")) {
            Instant ate = instante(Json.texto(no.path("ate")));
            if (ate != null) bloqueios.add(new Bloqueio(Json.texto(no.path("chave")), opcional(no, "alvo"), ate));
        }
        if (descartadas > 0)
            log.warn("Concessões: {} linha(s) de {} descartada(s)", descartadas, arquivo);
    }

    private static String opcional(JsonNode no, String campo) {
        String valor = Json.texto(no.path(campo));
        return valor == null || valor.isBlank() ? null : valor;
    }

    private static Instant instante(String texto) {
        if (texto == null || texto.isBlank()) return null;
        try {
            return Instant.parse(texto);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String cortar(String evidencia) {
        String texto = evidencia == null ? "" : evidencia.strip();
        return texto.length() <= MAX_EVIDENCIA ? texto : texto.substring(0, MAX_EVIDENCIA);
    }
}
