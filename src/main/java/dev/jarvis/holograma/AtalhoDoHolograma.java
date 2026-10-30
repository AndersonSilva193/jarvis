package dev.jarvis.holograma;

import java.util.Optional;

import org.springframework.stereotype.Component;

import dev.jarvis.tela.PresencaDaHud;

/**
 * Decide se uma mensagem vai pelo caminho rápido do holograma: o modo aberto, a HUD ouvindo e uma
 * frase que o {@link IntentoDoHolograma} reconhece inteira. Fora disso, a mensagem é do modelo.
 *
 * <p>Separado do {@code Assistente} para ele não precisar conhecer HUD nem estado do holograma — só
 * pergunta "isto é atalho?" e executa a ferramenta {@code holograma} com o que voltar.
 */
@Component
public class AtalhoDoHolograma {

    private final PresencaDaHud hud;
    private final EstadoDoHologramaService estado;

    public AtalhoDoHolograma(PresencaDaHud hud, EstadoDoHologramaService estado) {
        this.hud = hud;
        this.estado = estado;
    }

    /** O modo está aberto e há HUD: é quando o atalho vale e quando o log diz qual caminho foi. */
    public boolean ativo() {
        return hud.conectada() && estado.abertoAgora();
    }

    public Optional<IntentoDoHolograma.Intento> reconhecer(String texto) {
        return ativo() ? IntentoDoHolograma.reconhecer(texto) : Optional.empty();
    }
}
