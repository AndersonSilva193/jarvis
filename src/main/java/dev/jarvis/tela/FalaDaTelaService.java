package dev.jarvis.tela;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Faz a tela aberta dizer uma das frases do {@link FalaDaTela}.
 *
 * É o caminho de volta do despertar: o agente do PC detecta as palmas, vê que a tela <b>já</b>
 * está aberta e, em vez de abrir outra, pede ao Jarvis que ela diga "Já estou aqui, senhor.".
 *
 * Publica evento de domínio e para por aí — quem traduz para SSE é o {@code EventosHud}, porque a
 * camada web não entra nos serviços. Como o evento é um {@link TelaEvento}, ele pega carona no
 * {@code @EventListener} que já existe lá: nenhuma fiação nova de SSE.
 */
@Service
public class FalaDaTelaService {

    private static final Logger log = LoggerFactory.getLogger(FalaDaTelaService.class);

    /** O que aconteceu com o pedido, para o controller escolher o status HTTP. */
    public enum Resultado {
        /** Publicada para a tela. */
        FALADA,
        /** Chave fora do catálogo. */
        DESCONHECIDA,
        /** Não há HUD conectada para ouvir. */
        SEM_TELA
    }

    private final ApplicationEventPublisher eventos;
    private final PresencaDaHud hud;

    public FalaDaTelaService(ApplicationEventPublisher eventos, PresencaDaHud hud) {
        this.eventos = eventos;
        this.hud = hud;
    }

    /**
     * Manda a tela falar a frase da chave.
     *
     * <p>Sem HUD conectada não publica nada e diz isso a quem pediu, a mesma regra das ferramentas
     * de tela: falar para ninguém deixaria o agente achando que confirmou algo que o usuário nunca
     * ouviu.
     */
    public Resultado pedir(String chave) {
        String texto = FalaDaTela.texto(chave);
        if (texto == null) {
            log.warn("Pedido de fala com chave desconhecida: '{}'", chave);
            return Resultado.DESCONHECIDA;
        }
        if (!hud.conectada()) {
            return Resultado.SEM_TELA;
        }
        eventos.publishEvent(TelaEvento.deFala(texto));
        log.info("Tela falando '{}'", chave);
        return Resultado.FALADA;
    }
}
