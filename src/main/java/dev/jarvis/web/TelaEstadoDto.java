package dev.jarvis.web;

import java.util.ArrayList;
import java.util.List;

import dev.jarvis.tela.CardTela;
import dev.jarvis.tela.EstadoDaTela;

/**
 * O retrato da tela como a HUD o manda no POST /api/tela/estado.
 *
 * Validação à mão, como no resto da camada web (o projeto não usa Bean Validation): é conteúdo
 * vindo do navegador e vai acabar dentro do prompt do modelo, então tamanho e vocabulário são
 * conferidos aqui.
 */
public record TelaEstadoDto(List<JanelaDto> janelas, boolean historicoAberto,
        boolean briefingNaTela) {

    /** @param id o mesmo id que o servidor gerou no card; é por ele que o controlar_tela acha o alvo. */
    public record JanelaDto(String id, String tipo, String titulo, String estado,
            boolean fixado, boolean emFoco, boolean tocando) {
    }

    private static final int MAX_TITULO = 160;
    private static final String ID_VALIDO = "[A-Za-z0-9_-]{1,32}";

    /** Os problemas encontrados, em texto. Lista vazia significa que o retrato está bom. */
    public List<String> erros() {
        List<String> erros = new ArrayList<>();
        List<JanelaDto> lista = janelas == null ? List.of() : janelas;
        for (int i = 0; i < lista.size(); i++) {
            JanelaDto janela = lista.get(i);
            String onde = "janela " + (i + 1) + ": ";
            if (janela == null) {
                erros.add(onde + "vazia");
                continue;
            }
            if (janela.id() == null || !janela.id().matches(ID_VALIDO)) {
                erros.add(onde + "id inválido");
            }
            if (janela.tipo() == null || (!CardTela.TIPOS.contains(janela.tipo())
                    && !"briefing".equals(janela.tipo()) && !"resposta".equals(janela.tipo()))) {
                erros.add(onde + "tipo desconhecido");
            }
            if (janela.titulo() != null && janela.titulo().length() > MAX_TITULO) {
                erros.add(onde + "título acima de " + MAX_TITULO + " caracteres");
            }
            if (janela.estado() == null || !EstadoDaTela.ESTADOS.contains(janela.estado())) {
                erros.add(onde + "estado deve ser normal, minimizado ou maximizado");
            }
        }
        return erros;
    }

    /** @param recebidoEm o relógio é nosso: a hora que a HUD diria não é a que vale. */
    public EstadoDaTela paraDominio(String recebidoEm) {
        List<EstadoDaTela.Janela> convertidas = (janelas == null ? List.<JanelaDto>of() : janelas).stream()
                .map(j -> new EstadoDaTela.Janela(j.id(), j.tipo(),
                        j.titulo() == null ? "" : j.titulo(), j.estado(),
                        j.fixado(), j.emFoco(), j.tocando()))
                .toList();
        return new EstadoDaTela(convertidas, historicoAberto, briefingNaTela, recebidoEm);
    }
}
