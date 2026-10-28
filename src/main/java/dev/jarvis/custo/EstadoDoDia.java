package dev.jarvis.custo;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O que um dia acumulou: os baldes por origem e por modelo, mais os dois sinalizadores de aviso.
 *
 * Os sinalizadores moram aqui — e, por tabela, no arquivo — porque um restart no meio da tarde
 * re-mandaria ao dono os avisos de 80% e de 100% que ele já leu.
 *
 * O total e as buscas do dia <b>não</b> são campos: são somados dos baldes por modelo. Dois
 * contadores do mesmo fato acabam discordando, e o que o arquivo guarda de totais serve para ler
 * com {@code cat}, não para o medidor confiar.
 */
class EstadoDoDia {

    private final LocalDate dia;
    private final Map<Origem, Uso> porOrigem = new EnumMap<>(Origem.class);
    private final Map<String, Uso> porModelo = new LinkedHashMap<>();
    private final Map<String, Long> porFerramenta = new LinkedHashMap<>();
    /**
     * As chamadas ao modelo do PC, por origem: tokens e contagem, custo sempre zero.
     *
     * <p>Balde separado, e não uma entrada a mais em {@link #porModelo}: o relatório falado soma
     * dólares e tokens dali, e o {@code Cache: X% lido.} divide pelo prompt inteiro. Tokens locais
     * lá dentro baixariam a taxa de cache do Claude sem o Claude ter mudado nada.
     */
    private final Map<Origem, Uso> locais = new EnumMap<>(Origem.class);
    /** Quantas vezes uma tarefa que podia ir ao local foi ao Claude, por motivo. */
    private final Map<String, Long> reservas = new LinkedHashMap<>();
    /** Chamadas de ferramenta do local com argumento torto: o número que o escalonamento lê. */
    long errosDeFormato;
    /**
     * As buscas da {@code buscar_na_web} por provedor ({@code tavily}, {@code searxng},
     * {@code anthropic}). Contagem, não dólar: é dela que sai a cota do mês do Tavily, e o custo da
     * reserva Anthropic já entra pelos tokens e pelo {@code buscasWeb} do modelo.
     */
    private final Map<String, Long> buscasPorProvedor = new LinkedHashMap<>();

    boolean avisou80;
    boolean avisou100;
    /** O teto que valia quando um sinalizador subiu. Teto trocado no meio do dia reabre os avisos. */
    double tetoGravado;

    EstadoDoDia(LocalDate dia) {
        this.dia = dia;
    }

    LocalDate dia() {
        return dia;
    }

    Map<Origem, Uso> porOrigem() {
        return porOrigem;
    }

    Map<String, Uso> porModelo() {
        return porModelo;
    }

    Map<String, Long> porFerramenta() {
        return porFerramenta;
    }

    void registrarFerramenta(String nome) {
        porFerramenta.merge(nome, 1L, Long::sum);
    }

    Map<Origem, Uso> locais() {
        return locais;
    }

    Map<String, Long> reservas() {
        return reservas;
    }

    void somarLocal(Origem origem, long entrada, long saida) {
        locais.computeIfAbsent(origem, o -> new Uso()).somar(1, entrada, saida, 0, 0, 0, 0, false);
    }

    Map<String, Long> buscasPorProvedor() {
        return buscasPorProvedor;
    }

    void registrarBusca(String provedor) {
        buscasPorProvedor.merge(provedor, 1L, Long::sum);
    }

    void registrarReserva(String motivo) {
        reservas.merge(motivo, 1L, Long::sum);
    }

    void somar(Origem origem, String modelo, long entrada, long saida, long cacheEscrito,
            long cacheLido, long buscasWeb, double custoUsd, boolean semPreco) {
        // Uma soma é uma resposta do modelo: o 1 é a contagem de chamadas dos dois baldes.
        porOrigem.computeIfAbsent(origem, o -> new Uso())
                .somar(1, entrada, saida, cacheEscrito, cacheLido, buscasWeb, custoUsd, semPreco);
        porModelo.computeIfAbsent(modelo, m -> new Uso())
                .somar(1, entrada, saida, cacheEscrito, cacheLido, buscasWeb, custoUsd, semPreco);
    }

    double totalUsd() {
        return porModelo.values().stream().mapToDouble(uso -> uso.custoUsd).sum();
    }

    long buscasWeb() {
        return porModelo.values().stream().mapToLong(uso -> uso.buscasWeb).sum();
    }

    boolean vazio() {
        return porModelo.isEmpty() && porFerramenta.isEmpty() && locais.isEmpty()
                && reservas.isEmpty() && errosDeFormato == 0 && buscasPorProvedor.isEmpty();
    }

    /**
     * Os modelos que consumiram sem preço configurado, em ordem de aparição.
     *
     * É daqui que sai o WARN de uma vez por dia: quem marca o modelo é a primeira soma dele, e a
     * lista vem de volta do disco na partida — senão cada restart repetiria o aviso.
     */
    List<String> semPreco() {
        return porModelo.entrySet().stream()
                .filter(e -> e.getValue().semPreco)
                .map(e -> e.getKey())
                .toList();
    }

    boolean conheceSemPreco(String modelo) {
        Uso uso = porModelo.get(modelo);
        return uso != null && uso.semPreco;
    }

    /** Cópia profunda, para a gravação serializar fora do lock sem o acumulador mudar embaixo. */
    EstadoDoDia copia() {
        EstadoDoDia copia = new EstadoDoDia(dia);
        porOrigem.forEach((origem, uso) -> copia.porOrigem.put(origem, uso.copia()));
        porModelo.forEach((modelo, uso) -> copia.porModelo.put(modelo, uso.copia()));
        copia.porFerramenta.putAll(porFerramenta);
        locais.forEach((origem, uso) -> copia.locais.put(origem, uso.copia()));
        copia.reservas.putAll(reservas);
        copia.errosDeFormato = errosDeFormato;
        copia.buscasPorProvedor.putAll(buscasPorProvedor);
        copia.avisou80 = avisou80;
        copia.avisou100 = avisou100;
        copia.tetoGravado = tetoGravado;
        return copia;
    }
}
