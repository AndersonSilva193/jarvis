package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.ia.Canal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A estabilidade do array {@code tools}, que é a parte mais cara do cache de prompt.
 *
 * <p>As definições são renderizadas <b>antes</b> do {@code system}, então um byte diferente aqui
 * invalida o prefixo de {@code tools}, o da personalidade e o das {@code messages} de uma vez — e
 * essa é a única invalidação, junto com a troca de modelo, que não tem escapatória. Ordem de
 * injeção do Spring e descrição montada de estado volátil são as duas formas de isso acontecer sem
 * ninguém pedir.
 */
class FerramentasEstabilidadeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> avisos;

    /** Uma ferramenta cuja descrição dá para mexer, que é o que simula estado volátil. */
    private static final class Fake implements Ferramenta {
        private final String nome;
        private String descricao;

        Fake(String nome, String descricao) {
            this.nome = nome;
            this.descricao = descricao;
        }

        @Override public String nome() { return nome; }
        @Override public String descricao() { return descricao; }
        @Override public ObjectNode esquemaEntrada() {
            return JSON.createObjectNode().put("type", "object");
        }
        @Override public String executar(JsonNode entrada) { return "ok"; }
        @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada,
                OuvinteProgresso progresso) {
            return ResultadoFerramenta.texto("ok");
        }
    }

    private List<String> capturarAvisos() {
        logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(Ferramentas.class);
        avisos = new ListAppender<>();
        avisos.start();
        logger.addAppender(avisos);
        return List.of();
    }

    @AfterEach
    void soltarOApendice() {
        if (logger != null && avisos != null) {
            logger.detachAppender(avisos);
        }
    }

    private List<String> warns() {
        return avisos.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static List<String> nomes(ArrayNode definicoes) {
        List<String> nomes = new java.util.ArrayList<>();
        for (JsonNode no : definicoes.values()) {
            nomes.add(no.path("name").stringValue());
        }
        return List.copyOf(nomes);
    }

    @Test
    @DisplayName("as definições saem em ordem alfabética, qualquer que seja a ordem da injeção")
    void ordemAlfabetica() {
        var umaOrdem = new Ferramentas(List.of(new Fake("zelar", "z"), new Fake("abrir", "a"),
                new Fake("medir", "m")), JSON);
        var outraOrdem = new Ferramentas(List.of(new Fake("medir", "m"), new Fake("zelar", "z"),
                new Fake("abrir", "a")), JSON);

        assertEquals(List.of("abrir", "medir", "zelar"), nomes(umaOrdem.definicoes(Canal.HUD)));
        assertEquals(nomes(umaOrdem.definicoes(Canal.HUD)), nomes(outraOrdem.definicoes(Canal.HUD)),
                "a ordem da varredura do classpath não é contratual: pode diferir entre "
                        + "target/classes e o jar, e aí um deploy sem mudança de código perde o cache");
    }

    @Test
    @DisplayName("a primeira montagem não avisa nada: não há com o que comparar")
    void primeiraMontagemNaoAvisa() {
        capturarAvisos();
        var registro = new Ferramentas(List.of(new Fake("abrir", "a")), JSON);

        registro.definicoes(Canal.TELEGRAM);

        assertTrue(warns().isEmpty(), warns().toString());
    }

    @Test
    @DisplayName("descrição que muda entre dois pedidos vira WARN nomeando a ferramenta")
    void descricaoQueMudaViraAviso() {
        capturarAvisos();
        Fake instavel = new Fake("controlar_pc", "apps liberados: chrome");
        var registro = new Ferramentas(List.of(instavel, new Fake("abrir", "a")), JSON);

        registro.definicoes(Canal.TELEGRAM);
        instavel.descricao = "apps liberados: nenhum";
        registro.definicoes(Canal.TELEGRAM);

        assertEquals(1, warns().size(), warns().toString());
        String aviso = warns().get(0);
        assertTrue(aviso.contains("controlar_pc"), aviso);
        assertTrue(aviso.contains("alterada(s)"), aviso);
        assertFalse(aviso.contains("abrir"), "a que não mudou não devia ser nomeada: " + aviso);
    }

    /**
     * O conjunto de nomes de um canal é fixo de propósito, e é isto que faz o aviso ser legível:
     * dentro de uma chave só a <b>descrição</b> ou o <b>schema</b> podem mudar, nunca quem está na
     * lista. Os dois filtros do {@code definicoes} dependem só do canal e do {@code tarefaPlanilha},
     * e os dois entram na chave do retrato.
     *
     * <p>Se algum dia um filtro passar a depender de estado — uma ferramenta que entra e sai
     * conforme o PC está no ar, por exemplo —, este teste é o que cai primeiro, e o ramo de
     * {@code +nome}/{@code -nome} do aviso é o que vai nomeá-la.
     */
    @Test
    @DisplayName("o conjunto de nomes de um canal não muda entre pedidos")
    void conjuntoDeNomesDoCanalEFixo() {
        Fake instavel = new Fake("tarefa_no_pc", "disponível");
        var registro = new Ferramentas(List.of(instavel, new Fake("abrir", "a")), JSON);

        List<String> antes = nomes(registro.definicoes(Canal.TELEGRAM));
        instavel.descricao = "indisponível";
        List<String> depois = nomes(registro.definicoes(Canal.TELEGRAM));

        assertEquals(antes, depois);
    }

    @Test
    @DisplayName("a tarefa de planilha é outro prefixo, não uma mudança: não avisa")
    void tarefaDePlanilhaNaoViraAviso() {
        capturarAvisos();
        var registro = new Ferramentas(List.of(new Fake("ler_arquivo", "l"),
                new Fake("tocar_youtube", "t")), JSON);

        registro.definicoes(Canal.TELEGRAM, false);
        registro.definicoes(Canal.TELEGRAM, true);
        registro.definicoes(Canal.TELEGRAM, false);

        assertEquals(List.of("ler_arquivo"), nomes(registro.definicoes(Canal.TELEGRAM, true)),
                "com tarefa de planilha vale a lista branca de cinco ferramentas");
        assertTrue(warns().isEmpty(),
                "sem o tarefaPlanilha na chave, cada tarefa gritaria 'removidas' e 'acrescentadas': "
                        + warns());
    }
}
