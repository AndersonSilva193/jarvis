package dev.jarvis.ferramentas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import dev.jarvis.tela.CardTela;
import dev.jarvis.tela.ComandoTela;
import dev.jarvis.tela.TelaEvento;
import dev.jarvis.util.Json;
import dev.jarvis.ia.Canal;
import dev.jarvis.ia.provedor.Bloco;
import dev.jarvis.ia.provedor.ConversorAnthropic;
import dev.jarvis.ia.provedor.DefinicaoDeFerramenta;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Reúne todas as ferramentas (o Spring injeta a lista) e faz a ponte com o formato da API. */
@Component
public class Ferramentas {

    private static final Logger log = LoggerFactory.getLogger(Ferramentas.class);
    /**
     * Edição deve estar visível mesmo quando a configuração ainda contém a lista inicial de seis.
     * O {@code ler_procedimento} também: o índice do system manda chamá-lo antes da tarefa, e ter
     * de procurá-lo primeiro custaria uma ida a mais ao modelo justamente no caso que ele acelera.
     */
    private static final Set<String> EXTRAS_SEMPRE_CARREGADAS = Set.of(
            "editar_arquivo", "tarefa_no_pc", "ler_procedimento");

    /**
     * Por nome, em <b>ordem alfabética</b> — e é a ordem que importa, não só a busca.
     *
     * <p>As definições são renderizadas no array {@code tools}, antes do {@code system}, e entram
     * no mesmo prefixo do cache de prompt. Antes isto era um {@code LinkedHashMap} na ordem em que
     * o Spring injetou a lista, que é a da varredura do classpath: determinística para um artefato,
     * mas não contratual, e podendo diferir entre {@code target/classes} e o jar. Uma ordem
     * diferente é um prefixo diferente, ou seja, cache perdido num deploy que não mudou código.
     */
    private final Map<String, Ferramenta> porNome = new TreeMap<>();
    private final JsonMapper json;
    /** A forma da API para quem ainda pede JSON: os testes e a impressão digital das definições. */
    private final ConversorAnthropic conversor;
    private final ApplicationEventPublisher eventos;
    private final MedidorDeCusto medidor;
    private final LeiturasVisiveis leiturasVisiveis;
    private final Set<String> sempreCarregadas;
    private final int resultadoMaxCaracteres;

    /**
     * A última impressão digital das definições, por canal — nome da ferramenta para o JSON dela em
     * forma canônica.
     *
     * <p>Serve para um WARN quando algo mudou, porque mudança aqui é cara e silenciosa: invalida o
     * prefixo de {@code tools}, de {@code system} e de {@code messages} de uma vez.
     *
     * <p>A chave inclui o {@code tarefaPlanilha}: com tarefa de planilha ativa a lista cai para
     * cinco ferramentas, e sem isso cada tarefa gritaria "trinta e sete removidas" e depois
     * "trinta e sete acrescentadas". São dois prefixos legítimos por canal, não uma mudança.
     */
    private final Map<String, Map<String, String>> digitaisPorCanal = new ConcurrentHashMap<>();

    /** Sem barramento de eventos: o que vai para a tela simplesmente não sai daqui. */
    public Ferramentas(List<Ferramenta> ferramentas, JsonMapper json) {
        this(ferramentas, json, evento -> { });
    }

    public Ferramentas(List<Ferramenta> ferramentas, JsonMapper json,
            ApplicationEventPublisher eventos) {
        this(ferramentas, json, eventos, null, null, null);
    }

    public Ferramentas(List<Ferramenta> ferramentas, JsonMapper json,
            ApplicationEventPublisher eventos, JarvisProperties props, MedidorDeCusto medidor) {
        this(ferramentas, json, eventos, props, medidor, null);
    }

    @Autowired
    public Ferramentas(List<Ferramenta> ferramentas, JsonMapper json,
            ApplicationEventPublisher eventos, JarvisProperties props, MedidorDeCusto medidor,
            LeiturasVisiveis leiturasVisiveis) {
        this.json = json;
        this.conversor = new ConversorAnthropic(json);
        this.eventos = eventos;
        this.medidor = medidor;
        this.leiturasVisiveis = leiturasVisiveis;
        this.sempreCarregadas = Set.copyOf(props == null
                ? List.of("clima", "criar_lembrete", "listar_lembretes", "controlar_pc",
                        "controlar_tela", "tocar_youtube") : props.ferramentasSempreCarregadas());
        this.resultadoMaxCaracteres = props == null ? 6_000 : props.resultadoMaxCaracteres();
        for (Ferramenta f : ferramentas) {
            porNome.put(f.nome(), f);
        }
        if (props != null) {
            sempreCarregadas.stream().filter(nome -> !porNome.containsKey(nome)).sorted()
                    .forEach(nome -> log.warn("Ferramenta sempre carregada desconhecida: {}", nome));
        }
        log.info("Ferramentas disponíveis: {}", porNome.keySet());
    }

    /** Lista no formato "tools" da Messages API. */
    public ArrayNode definicoes() {
        return definicoes(null);
    }

    /** Ferramentas oferecidas para o canal, mantendo a lista estável dentro do turno. */
    public ArrayNode definicoes(Canal canal) {
        return definicoes(canal, false);
    }

    public ArrayNode definicoes(Canal canal, boolean tarefaPlanilha) {
        ArrayNode lista = json.createArrayNode();
        for (DefinicaoDeFerramenta definicao : definicoesNeutras(canal, tarefaPlanilha)) {
            lista.add(conversor.definicao(definicao));
        }
        return lista;
    }

    /**
     * As mesmas definições no formato neutro, que é o que o laço de ferramentas entrega ao
     * {@code ProvedorDeModelo}. É aqui que o aviso de estabilidade roda, então ele vale para os dois
     * caminhos — e a impressão digital continua sendo a da forma que a API recebe.
     */
    public List<DefinicaoDeFerramenta> definicoesNeutras(Canal canal, boolean tarefaPlanilha) {
        List<DefinicaoDeFerramenta> lista = new ArrayList<>();
        Map<String, String> digitais = new LinkedHashMap<>();
        for (Ferramenta f : porNome.values()) {
            if (!f.oferecida()) continue;
            if (canal == Canal.TELEGRAM && "ouvir_resposta".equals(f.nome())) continue;
            if (tarefaPlanilha && !List.of("listar_arquivos", "ler_arquivo", "editar_arquivo",
                    "verificar_site", "clima").contains(f.nome())) continue;
            boolean sobDemanda = !sempreCarregadas.contains(f.nome())
                    && !EXTRAS_SEMPRE_CARREGADAS.contains(f.nome())
                    && !((canal == null || canal == Canal.HUD) && "ouvir_resposta".equals(f.nome()));
            DefinicaoDeFerramenta definicao = new DefinicaoDeFerramenta(f.nome(), f.descricao(),
                    f.esquemaEntrada(), sobDemanda);
            lista.add(definicao);
            digitais.put(f.nome(), Json.canonico(conversor.definicao(definicao)));
        }
        conferirEstabilidade(canal, tarefaPlanilha, digitais);
        return lista;
    }

    /**
     * Avisa quando as definições mudaram de um pedido para o outro, nomeando a ferramenta.
     *
     * <p>Mudança no array {@code tools} é a invalidação mais cara que existe no cache de prompt: as
     * ferramentas são renderizadas antes do {@code system}, então um byte diferente aqui derruba o
     * prefixo de {@code tools}, o do {@code system} (a personalidade) e o das {@code messages} de
     * uma vez — e não há nada a fazer a respeito, diferente da troca de system, que tem mensagem de
     * sistema no meio da conversa como saída. Sem este aviso, o dono descobriria pelo valor da
     * fatura.
     *
     * <p>Usa o {@link Json#canonico} e não o {@code toString()}: o {@code JsonMapper} do projeto é
     * cru, e dois schemas iguais escritos em ordem diferente de campo sairiam como textos
     * diferentes. A primeira chamada de cada chave só registra — não há com o que comparar.
     */
    private void conferirEstabilidade(Canal canal, boolean tarefaPlanilha,
            Map<String, String> digitais) {
        String chave = (canal == null ? "qualquer" : canal.nome()) + "|" + tarefaPlanilha;
        Map<String, String> antes = digitaisPorCanal.put(chave, Map.copyOf(digitais));
        if (antes == null || antes.equals(digitais)) {
            return;
        }
        List<String> alteradas = new ArrayList<>();
        List<String> novas = new ArrayList<>();
        List<String> removidas = new ArrayList<>();
        for (Map.Entry<String, String> agora : digitais.entrySet()) {
            String anterior = antes.get(agora.getKey());
            if (anterior == null) {
                novas.add(agora.getKey());
            } else if (!anterior.equals(agora.getValue())) {
                alteradas.add(agora.getKey());
            }
        }
        for (String nome : antes.keySet()) {
            if (!digitais.containsKey(nome)) {
                removidas.add(nome);
            }
        }
        StringBuilder texto = new StringBuilder();
        if (!alteradas.isEmpty()) texto.append(" alterada(s) ").append(String.join(", ", alteradas));
        if (!novas.isEmpty()) texto.append(" +").append(String.join(", +", novas));
        if (!removidas.isEmpty()) texto.append(" -").append(String.join(", -", removidas));
        log.warn("Definições de ferramenta mudaram em {}:{} — o cache de prompt de tools, system e "
                + "messages foi invalidado neste pedido", chave, texto);
    }

    /** Recebe um bloco "tool_use" do modelo e devolve o bloco "tool_result" correspondente. */
    public ObjectNode executar(JsonNode blocoToolUse) {
        return executar(blocoToolUse, new dev.jarvis.conversa.OuvinteProgresso() { }, saida -> { });
    }

    public ObjectNode executar(JsonNode blocoToolUse, dev.jarvis.conversa.OuvinteProgresso progresso,
            java.util.function.Consumer<ResultadoFerramenta> receber) {
        Bloco.Chamada chamada = new Bloco.Chamada(Json.texto(blocoToolUse.path("id")),
                Json.texto(blocoToolUse.path("name")), blocoToolUse.path("input"));
        return (ObjectNode) conversor.bloco(executar(chamada, progresso, receber));
    }

    /**
     * Executa a chamada que o modelo fez e devolve o resultado para ele, no formato neutro.
     *
     * <p>O resultado integral vai ao {@code receber}; o que volta daqui é só o texto limitado por
     * {@code RESULTADO_MAX_CARACTERES}. Falha vira resultado com {@code erro}, nunca exceção: um
     * pedido não cai porque uma ferramenta caiu.
     */
    public Bloco.Resultado executar(Bloco.Chamada chamada, dev.jarvis.conversa.OuvinteProgresso progresso,
            java.util.function.Consumer<ResultadoFerramenta> receber) {
        String nome = chamada.nome();
        JsonNode entrada = chamada.entrada();

        Ferramenta ferramenta = porNome.get(nome);
        if (ferramenta == null) {
            return new Bloco.Resultado(chamada.id(), LimiteDeResultado.paraModelo(
                    "Ferramenta desconhecida: " + nome, resultadoMaxCaracteres, json), true);
        }

        log.info("Executando ferramenta {}", nome);
        if (medidor != null) medidor.registrarFerramenta(nome);
        String descricao = ferramenta.progresso();
        if (descricao != null && !descricao.isBlank()) {
            dev.jarvis.conversa.OuvinteProgresso.avisar(() -> progresso.ferramenta(nome, descricao, null, null));
        }
        try {
            dev.jarvis.conversa.OuvinteProgresso contexto = new dev.jarvis.conversa.OuvinteProgresso() {
                @Override public String idPedido() { return progresso.idPedido(); }
                @Override public long usuarioAutenticado() { return progresso.usuarioAutenticado(); }
                @Override public String pedidoOriginal() { return progresso.pedidoOriginal(); }
                @Override public String idTarefaPlanilha() { return progresso.idTarefaPlanilha(); }
                @Override public String ultimoAnexoDeArquivo() { return progresso.ultimoAnexoDeArquivo(); }
                @Override public boolean memoriaEsquecida() { return progresso.memoriaEsquecida(); }
                @Override public void marcarMemoriaEsquecida() { progresso.marcarMemoriaEsquecida(); }
                @Override public void marcarMemoriaEsquecida(String id, long revisao) {
                    progresso.marcarMemoriaEsquecida(id, revisao);
                }
                @Override public String idMemoriaEsquecida() { return progresso.idMemoriaEsquecida(); }
                @Override public long revisaoMemoriaEsquecida() { return progresso.revisaoMemoriaEsquecida(); }
                @Override public boolean consumirContexto(int caracteres) { return progresso.consumirContexto(caracteres); }
                @Override public boolean evidenciaVerificada(String conteudo) { return progresso.evidenciaVerificada(conteudo); }
                @Override public String origemDaEvidencia(String conteudo) {
                    return progresso.origemDaEvidencia(conteudo);
                }
                @Override public String canal() { return progresso.canal(); }
                @Override public boolean pesquisaFeita() { return progresso.pesquisaFeita(); }
                @Override public String idFerramenta() { return chamada.id(); }
                @Override public void ferramenta(String nome, String descricao, Integer passo, Integer total) {
                    progresso.ferramenta(nome, descricao, passo, total);
                }
            };
            ResultadoFerramenta detalhado = ferramenta.executarDetalhado(entrada, contexto);
            // Ponto único: nenhuma ferramenta precisa lembrar de publicar, e o card chega à tela
            // já agora, enquanto o modelo ainda está escrevendo a resposta deste turno.
            publicarNaTela(detalhado);
            receber.accept(detalhado);
            String saida = detalhado.texto();
            String textoParaModelo = LimiteDeResultado.paraModelo(
                    saida == null || saida.isBlank() ? "(sem resultado)" : saida,
                    resultadoMaxCaracteres, json);
            if (leiturasVisiveis != null && "ler_arquivo".equals(nome))
                leiturasVisiveis.registrar(progresso.usuarioAutenticado(), textoParaModelo, json);
            return new Bloco.Resultado(chamada.id(), textoParaModelo, false);
        } catch (Exception e) {
            log.warn("Ferramenta {} falhou: {}", nome, e.getMessage());
            return new Bloco.Resultado(chamada.id(), LimiteDeResultado.paraModelo(
                    "Erro ao executar " + nome + ": " + e.getMessage(), resultadoMaxCaracteres, json),
                    true);
        }
    }

    /** Uma HUD que caiu não pode derrubar o pedido, por isso cada aviso vai dentro do avisar(). */
    private void publicarNaTela(ResultadoFerramenta resultado) {
        for (CardTela card : resultado.cards()) {
            dev.jarvis.conversa.OuvinteProgresso.avisar(
                    () -> eventos.publishEvent(TelaEvento.deCard(card)));
        }
        for (ComandoTela comando : resultado.comandos()) {
            dev.jarvis.conversa.OuvinteProgresso.avisar(
                    () -> eventos.publishEvent(TelaEvento.deComando(comando)));
        }
        for (var comando : resultado.holograma()) {
            dev.jarvis.conversa.OuvinteProgresso.avisar(
                    () -> eventos.publishEvent(TelaEvento.deHolograma(comando)));
        }
    }
}
