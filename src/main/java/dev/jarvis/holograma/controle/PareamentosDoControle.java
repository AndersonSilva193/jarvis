package dev.jarvis.holograma.controle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Quem controla qual HUD. Só ids de sessão entram aqui — nada de WebSocket —, então as regras do
 * pareamento são testadas sem rede.
 *
 * <p>A HUD autenticada pede um código ({@link #gerarCodigo}); o celular entra com ele uma vez só,
 * dentro de {@link #VALIDADE_DO_CODIGO}, e recebe a sua chave de retomada. A HUD também tem a dela.
 * Um lado que cai segura o pareamento por {@link #TOLERANCIA_DE_QUEDA}: voltando com a chave nesse
 * prazo, o par continua; passado o prazo, {@link #vencidos} encerra.
 *
 * <p>Um celular por HUD: enquanto houver par, a HUD não recebe código novo e nenhum código entra.
 * Tudo {@code synchronized}: são poucas sessões, e o repasse (30 por segundo) só faz uma consulta.
 */
@Component
public class PareamentosDoControle {

    public static final Duration VALIDADE_DO_CODIGO = Duration.ofMinutes(5);
    public static final Duration TOLERANCIA_DE_QUEDA = Duration.ofSeconds(30);

    public enum Papel { HUD, CONTROLE }

    /** Por que o celular não entrou. Cada um vira um código de fechamento diferente no handler. */
    public enum Recusa { INVALIDO, OCUPADO }

    /**
     * Uma entrada aceita: a chave que este lado guarda para voltar, a sessão do outro lado (vazia se
     * ele está caído) e se foi retomada — para avisar "voltou" em vez de "conectado".
     */
    public record Entrada(Papel papel, String retomada, Optional<String> outroLado, boolean retomou) { }

    public record Resultado(Optional<Entrada> entrada, Optional<Recusa> recusa) {
        static Resultado aceito(Entrada entrada) { return new Resultado(Optional.of(entrada), Optional.empty()); }
        static Resultado recusado(Recusa recusa) { return new Resultado(Optional.empty(), Optional.of(recusa)); }
    }

    /** Um lado caiu ou o par acabou: quem ainda está vivo do outro lado, para avisar. */
    public record Aviso(Papel quemCaiu, Optional<String> outroLado) { }

    /** Um par encerrado: as sessões que ainda estavam abertas, para fechar. */
    public record Encerrado(Optional<String> hud, Optional<String> controle) { }

    private static final class Par {
        String hud;
        String controle;
        final String retomadaHud = Segredos.novo();
        final String retomadaControle = Segredos.novo();
        Instant hudCaiuEm;
        Instant controleCaiuEm;
    }

    private record Codigo(String hud, Instant expira) { }

    private final Clock relogio;
    /** Pelo código, o pedido da HUD que o gerou. Uma HUD tem no máximo um código vivo. */
    private final Map<String, Codigo> codigos = new HashMap<>();
    private final List<Par> pares = new ArrayList<>();

    @Autowired
    public PareamentosDoControle() {
        this(Clock.systemUTC());
    }

    PareamentosDoControle(Clock relogio) {
        this.relogio = relogio;
    }

    /** Código novo para o QR; o anterior desta HUD deixa de valer. Vazio quando já há celular. */
    public synchronized Optional<String> gerarCodigo(String hud) {
        if (parDe(hud).isPresent()) {
            return Optional.empty();
        }
        codigos.values().removeIf(c -> c.hud().equals(hud) || vencido(c));
        String codigo = Segredos.novo();
        codigos.put(codigo, new Codigo(hud, relogio.instant().plus(VALIDADE_DO_CODIGO)));
        return Optional.of(codigo);
    }

    public synchronized Resultado entrarComCodigo(String codigo, String controle) {
        Codigo pedido = Segredos.formatoValido(codigo) ? codigos.remove(codigo) : null;
        if (pedido == null || vencido(pedido)) {
            return Resultado.recusado(Recusa.INVALIDO);
        }
        if (parDe(pedido.hud()).isPresent()) {
            return Resultado.recusado(Recusa.OCUPADO);
        }
        Par par = new Par();
        par.hud = pedido.hud();
        par.controle = controle;
        pares.add(par);
        return Resultado.aceito(new Entrada(Papel.CONTROLE, par.retomadaControle, Optional.of(par.hud), false));
    }

    /**
     * Um lado volta com a chave. A HUD só chega aqui depois de passar pelo bilhete — a chave sozinha
     * não autentica uma HUD.
     */
    public synchronized Resultado retomar(Papel papel, String retomada, String sessao) {
        if (!Segredos.formatoValido(retomada)) {
            return Resultado.recusado(Recusa.INVALIDO);
        }
        for (Par par : pares) {
            if (papel == Papel.HUD && par.retomadaHud.equals(retomada)) {
                par.hud = sessao;
                par.hudCaiuEm = null;
                return Resultado.aceito(new Entrada(papel, retomada, Optional.ofNullable(par.controle), true));
            }
            if (papel == Papel.CONTROLE && par.retomadaControle.equals(retomada)) {
                par.controle = sessao;
                par.controleCaiuEm = null;
                return Resultado.aceito(new Entrada(papel, retomada, Optional.ofNullable(par.hud), true));
            }
        }
        return Resultado.recusado(Recusa.INVALIDO);
    }

    /** A chave de retomada desta HUD, para ela guardar; vazia sem par. */
    public synchronized Optional<String> retomadaDaHud(String hud) {
        return parDe(hud).filter(par -> hud.equals(par.hud)).map(par -> par.retomadaHud);
    }
    /** Para onde vai o que esta sessão mandar. Vazio sem par ou com o outro lado caído. */
    public synchronized Optional<String> outroLado(String sessao) {
        return parDe(sessao).map(par -> sessao.equals(par.hud) ? par.controle : par.hud);
    }

    /** A sessão fechou. O par fica esperando a volta; quem está do outro lado precisa saber. */
    public synchronized Optional<Aviso> caiu(String sessao) {
        codigos.values().removeIf(c -> c.hud().equals(sessao));
        Optional<Par> encontrado = parDe(sessao);
        if (encontrado.isEmpty()) {
            return Optional.empty();
        }
        Par par = encontrado.get();
        if (sessao.equals(par.hud)) {
            par.hud = null;
            par.hudCaiuEm = relogio.instant();
            return Optional.of(new Aviso(Papel.HUD, Optional.ofNullable(par.controle)));
        }
        par.controle = null;
        par.controleCaiuEm = relogio.instant();
        return Optional.of(new Aviso(Papel.CONTROLE, Optional.ofNullable(par.hud)));
    }

    /** O "Desconectar" da HUD ou o "Sair" do celular: o par acaba na hora, sem tolerância. */
    public synchronized Optional<Encerrado> encerrar(String sessao) {
        Optional<Par> encontrado = parDe(sessao);
        encontrado.ifPresent(pares::remove);
        return encontrado.map(par -> new Encerrado(Optional.ofNullable(par.hud), Optional.ofNullable(par.controle)));
    }

    /** Os pares com um lado caído há mais que a tolerância saem; e os códigos vencidos também. */
    public synchronized List<Encerrado> vencidos() {
        Instant limite = relogio.instant().minus(TOLERANCIA_DE_QUEDA);
        codigos.values().removeIf(this::vencido);
        List<Encerrado> saida = new ArrayList<>();
        pares.removeIf(par -> {
            boolean venceu = (par.hudCaiuEm != null && !par.hudCaiuEm.isAfter(limite))
                    || (par.controleCaiuEm != null && !par.controleCaiuEm.isAfter(limite));
            if (venceu) {
                saida.add(new Encerrado(Optional.ofNullable(par.hud), Optional.ofNullable(par.controle)));
            }
            return venceu;
        });
        return saida;
    }

    private boolean vencido(Codigo codigo) {
        return !relogio.instant().isBefore(codigo.expira());
    }

    private Optional<Par> parDe(String sessao) {
        return pares.stream().filter(par -> sessao.equals(par.hud) || sessao.equals(par.controle)).findFirst();
    }
}
