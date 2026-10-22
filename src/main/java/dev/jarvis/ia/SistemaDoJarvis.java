package dev.jarvis.ia;

import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.memoria.MemoriaService;
import dev.jarvis.tela.PresencaDaHud;
import dev.jarvis.tela.TelaEstadoService;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Monta o campo {@code system} de qualquer chamada ao Claude, a partir da personalidade e do canal.
 *
 * É o ponto único: quem for falar com o Claude pede os blocos aqui e não monta prompt próprio, para
 * a personalidade valer em todo lugar e a decisão de cachear ser a mesma em todo lugar.
 */
@Service
public class SistemaDoJarvis {

    private final PersonalidadeService personalidade;
    private final JsonMapper json;
    private final JarvisProperties props;
    private final TelaEstadoService tela;
    private final PresencaDaHud hud;
    private final MemoriaService memoria;

    /** Sem HUD e sem memória: o que vale para teste e para quem chama de dentro do processo. */
    public SistemaDoJarvis(PersonalidadeService personalidade, JsonMapper json, JarvisProperties props) {
        this(personalidade, json, props, new TelaEstadoService(), PresencaDaHud.semTela(), null);
    }

    /** Sem HUD, com memória: para testar o resumo sem subir o retrato da tela. */
    public SistemaDoJarvis(PersonalidadeService personalidade, JsonMapper json, JarvisProperties props,
            MemoriaService memoria) {
        this(personalidade, json, props, new TelaEstadoService(), PresencaDaHud.semTela(), memoria);
    }

    @Autowired
    public SistemaDoJarvis(PersonalidadeService personalidade, JsonMapper json, JarvisProperties props,
            TelaEstadoService tela, PresencaDaHud hud, MemoriaService memoria) {
        this.personalidade = personalidade;
        this.json = json;
        this.props = props;
        this.tela = tela;
        this.hud = hud;
        this.memoria = memoria;
    }

    /**
     * Os dois blocos de system: a personalidade (fixa, cacheada) e o contexto do pedido (dinâmico).
     *
     * <p>O {@code cache_control} só vai quando o bloco fixo passa do mínimo do modelo — abaixo disso
     * a API o ignoraria em silêncio. A conta olha só a personalidade; as ferramentas são renderizadas
     * antes do system e só somam ao prefixo, então a estimativa erra para o lado seguro.
     *
     * <p>O que está aberto na tela entra no bloco <b>dinâmico</b>, e só quando há HUD ouvindo: ele
     * muda a cada pedido e no bloco fixo arruinaria o cache de prompt. O mesmo vale para o resumo
     * da memória.
     *
     * <p>Monte uma vez por pedido e repita o mesmo objeto em todas as idas e vindas de ferramenta:
     * o bloco dinâmico tem a hora, e remontá-lo no meio do turno invalida os blocos de pensamento
     * que o modelo já devolveu (HTTP 400).
     */
    public ArrayNode blocos(Canal canal) {
        return blocos(canal, 0, "");
    }

    /**
     * Os mesmos dois blocos, com o resumo do que a memória sabe sobre quem está pedindo.
     *
     * <p>O resumo só entra em <b>pedido autenticado em conversa privada</b>: é a mesma exigência de
     * {@code FerramentaMemoria} — usuário maior que zero e canal com alguém na frente. Em grupo o
     * {@code Assistente} já devolve usuário zero, porque a resposta seria pública; em
     * {@code briefing} e {@code tarefa} não há usuário autenticado nenhum.
     *
     * @param usuario  o usuário autenticado do pedido, ou 0 quando não há
     * @param mensagem a mensagem do pedido, que decide quais projetos entram no resumo
     */
    public ArrayNode blocos(Canal canal, long usuario, String mensagem) {
        String texto = personalidade.texto();
        boolean cachear = PersonalidadeService.estimarTokens(texto) >= props.anthropic().cacheMinimoTokens();
        List<String> naTela = hud.conectada() ? tela.linhas() : List.of();
        boolean podeLembrar = memoria != null && usuario > 0
                && (canal == Canal.TELEGRAM || canal == Canal.HUD);
        String sobreUsuario = podeLembrar ? memoria.resumoParaContexto(usuario, mensagem) : "";
        return PromptDeSistema.montar(json, texto, cachear, canal, props.nomeUsuario(),
                ZonedDateTime.now(props.zona()), props.buscaWebAtiva(), naTela, sobreUsuario);
    }
}
