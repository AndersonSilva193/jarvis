package dev.jarvis.ia;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A definição da busca na web, que entra no array de ferramentas da Messages API.
 *
 * <p>Não é uma {@link dev.jarvis.ferramentas.Ferramenta}: não há nada para executar aqui. Quem
 * procura é o servidor da Anthropic, e os resultados voltam dentro da mesma resposta, em blocos
 * {@code server_tool_use} e {@code web_search_tool_result}. O Jarvis só declara e repassa.
 *
 * <p>A definição é montada <b>uma vez</b>, no construtor: as ferramentas são renderizadas antes do
 * system e fazem parte do prefixo do cache de prompt, então qualquer variação por pedido jogaria o
 * cache no lixo.
 */
@Component
public class BuscaWebServidor {

    private static final Logger log = LoggerFactory.getLogger(BuscaWebServidor.class);

    /** Nome fixo da ferramenta na API — não é escolha nossa. */
    private static final String NOME = "web_search";

    private final ObjectNode definicao;
    private final int maxUsos;

    /**
     * Anotado de propósito: a classe tem dois construtores, e entre vários o Spring escolhe o sem
     * argumentos — o da fábrica {@link #desligada()}, mesmo sendo privado. Sem esta anotação o bean
     * sobe desligado em silêncio e o modelo responde que não tem busca na web.
     */
    @Autowired
    public BuscaWebServidor(JarvisProperties props, JsonMapper json) {
        if (!props.buscaWebAtiva()) {
            // Dizer no log por que ela está desligada: sem isso, config que não casou e desligar de
            // propósito ficam idênticos de fora, e o modelo responde "não tenho busca" nos dois casos.
            log.info("Busca na web: desligada ({})",
                    props.buscaWeb() == null ? "bloco jarvis.busca-web ausente na configuração"
                            : "BUSCA_WEB_ATIVA=false");
            this.definicao = null;
            this.maxUsos = 0;
            return;
        }
        JarvisProperties.BuscaWeb config = props.buscaWeb();
        this.maxUsos = config.maxUsos();

        ObjectNode def = json.createObjectNode();
        def.put("type", config.tipo());
        def.put("name", NOME);
        def.put("max_uses", config.maxUsos());
        // Localiza o resultado: "farmácia aberta agora" sem isso devolve farmácia em outro país.
        ObjectNode onde = def.putObject("user_location");
        onde.put("type", "approximate");
        if (props.clima() != null && props.clima().cidade() != null && !props.clima().cidade().isBlank()) {
            onde.put("city", props.clima().cidade());
        }
        onde.put("country", "BR");
        onde.put("timezone", props.zona().getId());
        this.definicao = def;
        log.info("Busca na web: ligada (tipo={}, até {} busca(s) por pedido)", config.tipo(), maxUsos);
    }

    private BuscaWebServidor() {
        this.definicao = null;
        this.maxUsos = 0;
    }

    /** Instância desligada, para quem monta o Assistente à mão (testes e chamadas internas). */
    public static BuscaWebServidor desligada() {
        return new BuscaWebServidor();
    }

    public boolean ativa() {
        return definicao != null;
    }

    /** A definição pronta, ou {@code null} quando a busca está desligada. */
    public ObjectNode definicao() {
        return definicao;
    }

    /** Teto de buscas por pedido, ou zero quando desligada. */
    public int maxUsos() {
        return maxUsos;
    }
}
