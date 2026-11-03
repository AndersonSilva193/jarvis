package dev.jarvis.tela;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import dev.jarvis.diario.AcaoRegistrada;

/** Dados da casa, sem fontes web e sem o limite de oito itens de uma pesquisa do modelo. */
public final class MontadorDeLinhaDoTempo {
    private MontadorDeLinhaDoTempo() { }
    public static CardTela montar(LocalDate dia, List<AcaoRegistrada> acoes, ZoneId zona,
            String consultadoEm, int descartadas) {
        if (acoes.isEmpty() || acoes.size() > 500) throw new IllegalArgumentException("Dia do diário inválido");
        List<DadosDoCard.ItemDoCard> marcos = acoes.stream().map(a -> (DadosDoCard.ItemDoCard)
                new DadosDoCard.ItemDoCard.Marco(a.instante().atZone(zona).format(DateTimeFormatter.ofPattern("HH:mm")),
                        "[" + a.id() + "] " + a.descricao() + " · " + a.origem().chave()
                                + (a.reversivel() ? " · " + a.estadoDesfazer() : " · sem reversão registrada")))
                .toList();
        String resumo = acoes.size() + " ações registradas."
                + (descartadas > 0 ? " Lista incompleta: " + descartadas + " ações além do limite diário." : "");
        return new CardTela(CardTela.novoId(), CardTela.PESQUISA, "Ações do Jarvis · " + dia,
                new DadosDoCard.Pesquisa("Jarvis", "Diário de ações", dia.toString(), "linha_do_tempo",
                        marcos, resumo, List.of(), consultadoEm, null), "hud", consultadoEm);
    }
}
