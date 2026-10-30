package dev.jarvis.web;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import dev.jarvis.config.JarvisProperties;

/**
 * Liga o {@link CanalDoHologramaHandler} em {@code /ws/holograma}, fora de {@code /api}: o
 * {@link TokenHudFiltro} não o vê, e quem confere bilhete, código e retomada é o handler.
 *
 * <p>As origens são as mesmas do CORS ({@code HUD_ORIGEM}); sem origem configurada, só a mesma
 * origem passa — o padrão do Spring. Cliente sem {@code Origin} (o script de medição, os testes)
 * não é navegador e passa.
 */
@Configuration
@EnableWebSocket
public class CanalDoHologramaConfig implements WebSocketConfigurer {

    public static final String ROTA = "/ws/holograma";
    private static final List<String> PARAMETROS = List.of("bilhete", "par", "retomada");

    private final CanalDoHologramaHandler handler;
    private final List<String> origens;

    public CanalDoHologramaConfig(CanalDoHologramaHandler handler, JarvisProperties props) {
        this.handler = handler;
        this.origens = props.origensDaHud();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registro) {
        registro.addHandler(handler, ROTA)
                .addInterceptors(new CopiaDaQuery())
                .setAllowedOrigins(origens.toArray(String[]::new));
    }

    /** Só copia {@code bilhete}, {@code par} e {@code retomada} da query para os atributos. */
    private static final class CopiaDaQuery implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest pedido, ServerHttpResponse resposta,
                WebSocketHandler alvo, Map<String, Object> atributos) {
            var query = UriComponentsBuilder.fromUri(pedido.getURI()).build().getQueryParams();
            for (String nome : PARAMETROS) {
                String valor = query.getFirst(nome);
                if (valor != null) {
                    atributos.put(nome, valor);
                }
            }
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest pedido, ServerHttpResponse resposta,
                WebSocketHandler alvo, Exception erro) {
        }
    }
}
