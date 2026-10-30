package dev.jarvis.web;

import java.util.ArrayList;
import java.util.List;

import dev.jarvis.holograma.EstadoDoHolograma;

/**
 * O retrato do holograma como a HUD o manda no {@code POST /api/holograma/estado}.
 *
 * <p>Validação à mão, como no {@link TelaEstadoDto}: nome e grupos vão parar no prompt, e as peças
 * na resolução de alvo. Campo torto reprova o retrato inteiro, e o anterior continua valendo.
 */
public record EstadoDoHologramaDto(Boolean aberto, Objeto objeto, Boolean gerando, List<PecaDto> pecas,
        Double explosao, String selecionada, List<String> isolado, List<String> escondidos, String vista) {

    public record Objeto(String id, String nome) { }

    public record PecaDto(String id, String nome, String grupo) { }

    /** O teto da montagem; a HUD nunca tem mais que isso na cena. */
    static final int MAX_PECAS = 250;
    private static final int MAX_NOME = 80;
    private static final int MAX_FILTRO = 60;
    private static final String ID = "[a-z0-9_]{1,40}";

    public List<String> erros() {
        List<String> erros = new ArrayList<>();
        if (aberto == null) erros.add("aberto é obrigatório");
        if (objeto != null) {
            if (objeto.id() != null && !objeto.id().isEmpty() && !objeto.id().matches(ID)) erros.add("objeto.id inválido");
            if (objeto.nome() != null && objeto.nome().length() > MAX_NOME) erros.add("objeto.nome longo demais");
        }
        List<PecaDto> lista = pecas == null ? List.of() : pecas;
        if (lista.size() > MAX_PECAS) erros.add("mais de " + MAX_PECAS + " peças");
        for (int i = 0; i < Math.min(lista.size(), MAX_PECAS); i++) {
            PecaDto peca = lista.get(i);
            if (peca == null || peca.id() == null || !peca.id().matches(ID)) {
                erros.add("peça " + (i + 1) + ": id inválido");
            } else if (peca.grupo() == null || !peca.grupo().matches(ID)) {
                erros.add("peça " + (i + 1) + ": grupo inválido");
            } else if (peca.nome() != null && peca.nome().length() > MAX_NOME) {
                erros.add("peça " + (i + 1) + ": nome longo demais");
            }
        }
        if (explosao != null && !(explosao >= 0 && explosao <= 1)) erros.add("explosao fora de 0..1");
        if (selecionada != null && !selecionada.isEmpty() && !selecionada.matches(ID)) erros.add("selecionada inválida");
        for (List<String> filtro : List.of(isolado == null ? List.<String>of() : isolado,
                escondidos == null ? List.<String>of() : escondidos)) {
            if (filtro.size() > MAX_FILTRO) erros.add("filtro com mais de " + MAX_FILTRO + " itens");
            for (String item : filtro) {
                if (item == null || !item.matches(ID)) {
                    erros.add("filtro com item inválido");
                    break;
                }
            }
        }
        if (vista != null && !vista.isEmpty() && !EstadoDoHolograma.VISTAS.contains(vista)) erros.add("vista desconhecida");
        return erros;
    }

    public EstadoDoHolograma paraDominio(String recebidoEm) {
        if (!Boolean.TRUE.equals(aberto)) return EstadoDoHolograma.fechado(recebidoEm);
        List<EstadoDoHolograma.Peca> convertidas = (pecas == null ? List.<PecaDto>of() : pecas).stream()
                .map(p -> new EstadoDoHolograma.Peca(p.id(),
                        p.nome() == null || p.nome().isBlank() ? p.id() : p.nome().strip(), p.grupo()))
                .toList();
        return new EstadoDoHolograma(true, objeto == null ? "" : objeto.id(), objeto == null ? "" : objeto.nome(),
                Boolean.TRUE.equals(gerando), convertidas, explosao == null ? 0 : explosao, selecionada,
                isolado, escondidos, vista, recebidoEm);
    }
}
