package dev.jarvis.memoria;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * O recado de que uma memória foi esquecida, para o {@code Assistente} ver no turno em que isso
 * aconteceu.
 *
 * <p>Existe por causa de onde o esquecimento roda. O {@code esquecer_memoria} pede uma aprovação ao
 * {@code Aprovacoes} no turno N, e o {@code Supplier} que apaga de verdade roda no turno N+1, quando
 * o usuário responde. Esse {@code Supplier} carrega o {@code OuvinteProgresso} capturado no turno N
 * — é o que toda ferramenta de aprovação faz —, e marcar {@code memoriaEsquecida} nele escreveria
 * num campo que ninguém mais lê: quem decide apagar o histórico da conversa é o {@code progresso}
 * do turno <b>corrente</b>.
 *
 * <p>Daí a caixa de recados, no mesmo desenho do {@code AnotadorDeMemoria.drenarAviso}: quem fez o
 * trabalho fora do turno deixa o recado, e o {@code Assistente} o consome no lugar certo, logo
 * depois de consumir a aprovação. Uso único — um recado drenado não volta.
 *
 * <p>Em memória de propósito: um esquecimento que o processo não chegou a anunciar não tem histórico
 * para apagar depois do restart, porque o {@code MemoriaService} já tirou o registro do disco.
 */
@Component
public class EsquecimentosDeMemoria {

    public record Esquecido(long usuario, String id, long revisao) { }

    private final Map<Long, Esquecido> recados = new HashMap<>();

    public synchronized void marcar(long usuario, String id, long revisao) {
        if (usuario <= 0) return;
        recados.put(usuario, new Esquecido(usuario, id, revisao));
    }

    public synchronized Optional<Esquecido> drenar(long usuario) {
        return usuario <= 0 ? Optional.empty() : Optional.ofNullable(recados.remove(usuario));
    }
}
