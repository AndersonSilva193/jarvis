package dev.jarvis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import dev.jarvis.agenda.ResumoDiario;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ferramentas.Ferramentas;
import dev.jarvis.ferramentas.Ferramenta;
import dev.jarvis.ia.Assistente;
import dev.jarvis.ia.BuscaWebServidor;
import dev.jarvis.ia.PersonalidadeService;
import dev.jarvis.telegram.TelegramPoller;

/**
 * O único teste que levanta o contexto do Spring. Ele existe porque erro de fiação de bean
 * (construtor ambíguo, dependência faltando, ciclo) não aparece em teste de função pura: o
 * `mvn package` passa e o container só quebra na partida, já em produção.
 *
 * Nada de rede aqui: as credenciais são de mentira, o cron do resumo vem desligado ("-") e o
 * poller do Telegram é trocado por um dublê.
 */
@SpringBootTest(properties = {
        // As duas únicas configurações sem default no application.yml.
        "jarvis.telegram.token=teste",
        "jarvis.anthropic.api-key=teste",
        "jarvis.custo.precos=modelo-a=0.10/0.50/0.125/0.01",
        "jarvis.custo.precos-contexto-longo=modelo-a@100000=0.50/2.50/0.625/0.05",
        // "-" é como o Spring desliga um @Scheduled por configuração.
        "jarvis.resumo-diario.cron=-",
})
class ContextoDaAplicacaoTest {

    /**
     * O poller de verdade abre uma thread não-daemon que fica chamando a API do Telegram. O dublê
     * não sobe nada: o contexto fecha limpo e a suíte não depende da rede.
     */
    @MockitoBean
    TelegramPoller telegramPoller;

    @Autowired
    PersonalidadeService personalidade;
    @Autowired
    Assistente assistente;
    @Autowired
    ResumoDiario resumoDiario;
    @Autowired
    Ferramentas ferramentas;
    @Autowired
    java.util.List<Ferramenta> ferramentasRegistradas;
    @Autowired
    JarvisProperties props;
    @Autowired
    BuscaWebServidor buscaWeb;

    @Test
    void metadadosEDescricoesDasFerramentas() throws Exception {
        assertEquals(47, ferramentasRegistradas.size());
        java.util.List<String> longas = new java.util.ArrayList<>();
        for (Ferramenta ferramenta : ferramentasRegistradas) {
            assertNotNull(ferramenta.grupo(), ferramenta.nome());
            assertFalse(ferramenta.getClass().getMethod("grupo").getDeclaringClass()
                    .equals(Ferramenta.class), ferramenta.nome());
            assertFalse(ferramenta.getClass().getMethod("simples").getDeclaringClass()
                    .equals(Ferramenta.class), ferramenta.nome());
            if (ferramenta.descricao().length() > 600)
                longas.add(ferramenta.nome() + "=" + ferramenta.descricao().length());
        }
        assertTrue(longas.isEmpty(), "Descrições acima de 600: " + longas);
    }

    @Test
    @DisplayName("o contexto sobe com todos os beans ligados")
    void contextoSobe() {
        assertNotNull(personalidade);
        assertNotNull(assistente);
        assertNotNull(resumoDiario);
    }

    @Test
    @DisplayName("a busca na web casa com o application.yml e chega ligada ao Assistente")
    void buscaNaWebLigada() {
        // O bloco jarvis.busca-web do application.yml tem que casar com o record: se não casar,
        // props.buscaWeb() vem null, a busca fica desligada em silêncio e o modelo responde que
        // não tem pesquisa na web — sem nenhum erro no caminho.
        assertNotNull(props.buscaWeb(), "jarvis.busca-web não casou com JarvisProperties");
        assertTrue(props.buscaWebAtiva(), "a busca vem ligada por padrão");
        assertTrue(buscaWeb.ativa(), "o bean subiu desligado");
        assertEquals("web_search", buscaWeb.definicao().path("name").stringValue());
        assertFalse(buscaWeb.definicao().path("type").stringValue().isBlank());
    }

    @Test
    @DisplayName("as ferramentas são descobertas sozinhas pelo Spring")
    void ferramentasRegistradas() {
        // Ferramenta nova é só uma classe @Component: registro vazio significa injeção quebrada.
        assertFalse(ferramentas.definicoes().isEmpty(), "o registro de ferramentas subiu vazio");
        for (dev.jarvis.ia.Canal canal : new dev.jarvis.ia.Canal[] {
                dev.jarvis.ia.Canal.HUD, dev.jarvis.ia.Canal.TELEGRAM }) {
            var editar = java.util.stream.StreamSupport
                    .stream(ferramentas.definicoes(canal).spliterator(), false)
                    .filter(def -> "editar_arquivo".equals(def.path("name").asText()))
                    .findFirst().orElseThrow();
            assertTrue(editar.path("defer_loading").isMissingNode(), canal.nome());
        }
    }

    @Test
    @DisplayName("o bloco do YouTube casa com o application.yml")
    void youtubeCasaComOYml() {
        // Mesmo motivo do teste da busca na web: se o bloco jarvis.youtube não casar com o record,
        // props.youtube() vem null, o tocar_youtube diz "não está configurada" para sempre e
        // nenhum erro aparece no caminho.
        assertNotNull(props.youtube(), "jarvis.youtube não casou com JarvisProperties");
        assertFalse(props.youtubeConfigurado(), "sem YOUTUBE_API_KEY no ambiente, fica desligado");
    }

    @Test
    @DisplayName("o bloco da memória casa com o application.yml, anotador incluído")
    void memoriaCasaComOYml() {
        // Mesmo motivo dos outros: o anotador entrou esticando o record Memoria, e se os dois
        // campos novos não casarem com o yml o recurso fica desligado sem nenhum erro aparecer.
        assertNotNull(props.memoria(), "jarvis.memoria não casou com JarvisProperties");
        assertTrue(props.anotadorDeMemoriaAtivo(), "MEMORIA_ANOTADOR_ATIVO tem true de padrão");
        assertEquals(java.time.Duration.ofMinutes(5), props.ociosidadeDoAnotador());
    }

    @Test
    @DisplayName("o bloco do PC casa com o application.yml e o controlar_pc sobe fiado")
    void pcCasaComOYml() {
        // Mesmo motivo dos dois testes acima: se jarvis.pc não casar com o record, props.pc() vem
        // null, /api/pc recusa tudo com 401 e o controle do PC fica desligado em silêncio.
        assertNotNull(props.pc(), "jarvis.pc não casou com JarvisProperties");
        assertFalse(props.pcConfigurado(), "sem PC_TOKEN no ambiente, fica desligado");
        assertTrue(ferramentas.definicoes().toString().contains("controlar_pc"),
                "ferramenta 'controlar_pc' não foi registrada");
    }

    @Test
    @DisplayName("a configuração de custo e a ferramenta de consulta sobem no contexto")
    void custoCasaComOYml() {
        assertNotNull(props.custo(), "jarvis.custo não casou com JarvisProperties");
        assertEquals("modelo-a=0.10/0.50/0.125/0.01", props.precosDeCusto());
        assertEquals("modelo-a@100000=0.50/2.50/0.625/0.05",
                props.precosDeContextoLongo());
        assertTrue(ferramentas.definicoes().toString().contains("custo_do_jarvis"));
    }

    @Test
    @DisplayName("o bloco de esforços casa com o application.yml, nos cinco perfis")
    void esforcosCasamComOYml() {
        // Mesmo motivo dos blocos acima, e aqui o silêncio custa dinheiro: se jarvis.anthropic
        // .esforcos não casar com o record, props.anthropic().esforcos() vem null, nenhum
        // output_config é emitido e todas as chamadas voltam a pensar no padrão do modelo — sem
        // erro nenhum no caminho, e com a conta de saída de volta ao patamar de antes.
        var esforcos = props.anthropic().esforcos();
        assertNotNull(esforcos, "jarvis.anthropic.esforcos não casou com JarvisProperties");
        assertEquals("medium", esforcos.conversa());
        assertEquals("low", esforcos.briefing());
        assertEquals("low", esforcos.inicializacao());
        assertEquals("low", esforcos.apoio());
        assertEquals("medium", esforcos.tarefa());
    }

    @Test
    @DisplayName("as ferramentas de tela sobem fiadas, com os dois HttpClient resolvidos")
    void ferramentasDeTelaRegistradas() {
        // São as que dependem da porta PresencaDaHud (implementada pelo EventosHud) e, no caso do
        // abrir_pagina, do segundo HttpClient. Fiação errada aí só apareceria na partida.
        String nomes = ferramentas.definicoes().toString();

        for (String nome : new String[] {
                "mostrar_na_tela", "tocar_youtube", "abrir_pagina", "controlar_tela",
                "grafico_de_dados" }) {
            assertTrue(nomes.contains(nome), "ferramenta '" + nome + "' não foi registrada");
        }
    }
}
