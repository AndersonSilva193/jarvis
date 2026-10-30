package dev.jarvis.holograma;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * As frases curtas do holograma, com variação para o Jarvis não soar gravado.
 *
 * <p>Catálogo fechado, como o {@code FalaDaTela}: o texto é escrito aqui, e só os marcadores vêm de
 * dado — {@code {objeto}} é o pedido (higienizado), {@code {nome}} o nome que a galeria guardou e
 * {@code {n}} fica para a HUD trocar pelo número do {@code fim}, que só ela vê. A frase sorteada
 * nunca repete a anterior do mesmo tipo.
 */
@Component
public class FalasDoHolograma {

    public enum Tipo { CONSTRUINDO, DA_GALERIA, PRONTO }

    private static final Map<Tipo, List<String>> FALAS = new EnumMap<>(Map.of(
            Tipo.CONSTRUINDO, List.of("Construindo {objeto}.", "Montando {objeto}, senhor.",
                    "Gerando {objeto}.", "Um momento: {objeto} a caminho."),
            Tipo.DA_GALERIA, List.of("{nome} na tela.", "Aqui está: {nome}.", "{nome}, da galeria."),
            Tipo.PRONTO, List.of("Pronto: {n} peças.", "Concluído, senhor: {n} peças.",
                    "{n} peças, montagem completa.")));

    private final RandomGenerator sorteio;
    private final Map<Tipo, Integer> anterior = new EnumMap<>(Tipo.class);

    @Autowired
    public FalasDoHolograma() {
        this(RandomGenerator.getDefault());
    }

    public FalasDoHolograma(RandomGenerator sorteio) {
        this.sorteio = sorteio;
    }

    public String construindo(String objeto) {
        return sortear(Tipo.CONSTRUINDO).replace("{objeto}", objeto.strip());
    }

    public String daGaleria(String nome) {
        String frase = sortear(Tipo.DA_GALERIA).replace("{nome}", nome.strip());
        return Character.toUpperCase(frase.charAt(0)) + frase.substring(1);
    }

    /** O molde do fim, com o {@code {n}} ainda por trocar. */
    public String pronto() {
        return sortear(Tipo.PRONTO);
    }

    private synchronized String sortear(Tipo tipo) {
        List<String> opcoes = FALAS.get(tipo);
        int escolha = sorteio.nextInt(opcoes.size());
        Integer ultima = anterior.get(tipo);
        if (ultima != null && ultima == escolha && opcoes.size() > 1) {
            escolha = (escolha + 1) % opcoes.size();
        }
        anterior.put(tipo, escolha);
        return opcoes.get(escolha);
    }
}
