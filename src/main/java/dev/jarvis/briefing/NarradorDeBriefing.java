package dev.jarvis.briefing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.jarvis.briefing.BriefingService.Secao;
import dev.jarvis.custo.Origem;
import dev.jarvis.ia.Canal;
import dev.jarvis.ia.ClaudeClient;
import dev.jarvis.ia.SistemaDoJarvis;
import dev.jarvis.ia.provedor.ListaDeFerramentas;
import dev.jarvis.ia.provedor.Mensagem;
import dev.jarvis.ia.provedor.OpcoesDoPedido;
import dev.jarvis.ia.provedor.PedidoAoModelo;
import dev.jarvis.ia.provedor.ProvedorAnthropic;
import dev.jarvis.ia.provedor.ProvedorDeApoio;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import org.springframework.beans.factory.annotation.Qualifier;
import dev.jarvis.ia.provedor.RespostaDoModelo;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lê o briefing em voz de gente: pega as seções já montadas em Java e pede ao Claude que apresente
 * cada uma, no canal {@link Canal#BRIEFING} (fala para voz, sem markdown nem link).
 *
 * <p>A narração vem <b>por seção</b>, e não como um texto só, porque a HUD fala um card por vez:
 * cada bloco é dito enquanto o seu card está na tela. Um bloco único de prosa não teria onde
 * encaixar nessa coreografia.
 *
 * <p>Fala com o {@link ProvedorDeModelo} direto, e não pelo Assistente, por dois motivos. Primeiro,
 * dependência: o Assistente puxa as Ferramentas, e entre elas está a do briefing — o ciclo
 * BriefingService → Assistente → Ferramentas → BriefingFerramenta → BriefingService nem sobe.
 * Segundo, risco: mandar a lista de ferramentas aqui abriria a porta para o modelo chamar
 * {@code briefing_do_dia} no meio da narração do próprio briefing. Sem ferramenta não há laço.
 *
 * <p>Os números nunca são recalculados pelo modelo: chegam prontos e a instrução é de apresentação.
 * Qualquer falha — rede, formato torto, contagem diferente — devolve vazio, e quem pediu fica com o
 * texto determinístico.
 */
@Component
public class NarradorDeBriefing {

    private static final Logger log = LoggerFactory.getLogger(NarradorDeBriefing.class);

    /** Os pedaços voltam marcados com [0], [1], ...; [0] é a abertura e o resto segue as seções. */
    private static final Pattern MARCADOR = Pattern.compile("(?m)^[ \\t]*\\[(\\d+)\\][ \\t]*");

    private final ProvedorDeModelo modelo;
    private final SistemaDoJarvis sistema;

    /** Sobre o {@link ClaudeClient}, que é como os testes o montam. */
    public NarradorDeBriefing(ClaudeClient claude, SistemaDoJarvis sistema, JsonMapper json) {
        this(ProvedorAnthropic.sobre(claude, json), sistema);
    }

    /** O de apoio: o modelo do PC primeiro, o Claude se ele não puder ou errar as marcas. */
    @Autowired
    public NarradorDeBriefing(@Qualifier(ProvedorDeApoio.QUALIFICADOR) ProvedorDeModelo modelo,
            SistemaDoJarvis sistema) {
        this.modelo = modelo;
        this.sistema = sistema;
    }

    /**
     * Narra a abertura e cada seção, numa chamada só.
     *
     * @return lista com {@code secoes.size() + 2} falas — a de índice 0 é a abertura, as do meio
     *         seguem as seções e a última são as observações — ou lista vazia se algo não bateu.
     *         Nunca lança: briefing sem enfeite é melhor que manhã sem briefing.
     */
    public List<String> narrar(String nomeUsuario, List<Secao> secoes) {
        if (secoes.isEmpty()) {
            return List.of();
        }
        try {
            // Sem ferramenta nenhuma, de propósito: é o que impede o modelo de chamar o
            // briefing_do_dia no meio da narração do próprio briefing. As marcas fora de ordem ou
            // de contagem reprovam o modelo local, e aí o Claude narra.
            int esperados = secoes.size() + 2;
            RespostaDoModelo resposta = modelo.responder(new PedidoAoModelo(
                    SistemaDoJarvis.instrucoes(sistema.blocos(Canal.BRIEFING)),
                    List.of(Mensagem.texto(Mensagem.Papel.USUARIO, instrucao(nomeUsuario, secoes))),
                    ListaDeFerramentas.vazia(), OpcoesDoPedido.de(Origem.BRIEFING)),
                    r -> !separar(extrairTexto(r.textos()), esperados).isEmpty());
            List<String> falas = separar(extrairTexto(resposta.textos()), esperados);

            if (falas.isEmpty()) {
                log.warn("A narração do briefing voltou fora do formato esperado; fica o texto montado em Java.");
            }
            return falas;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Não consegui narrar o briefing ({}); fica o texto montado em Java.", e.getMessage());
            return List.of();
        }
    }

    private static String instrucao(String nomeUsuario, List<Secao> secoes) {
        StringBuilder pedido = new StringBuilder("""
                Apresente o briefing de hoje de %s. Os dados abaixo já estão prontos e conferidos:
                não altere nenhum número, nome ou horário, não acrescente nada que não esteja aqui e
                não comente o que faltou.

                Responda com %d trechos, cada um começando com a sua marca entre colchetes, na ordem,
                e nada além disso:
                [0] escreva apenas "Seu briefing está pronto.". O cumprimento por horário é inserido
                pelo aplicativo. Nas demais seções, não acrescente cumprimentos nem presuma manhã ou noite.
                """.formatted(nomeUsuario, secoes.size() + 2));

        for (int i = 0; i < secoes.size(); i++) {
            pedido.append("[%d] a seção \"%s\".%n".formatted(i + 1, secoes.get(i).titulo()));
        }
        pedido.append(("[%d] as observações: uma ou duas frases fechando o briefing — o que, no "
                + "conjunto do dia, merece atenção. Sem número novo e sem nada que não esteja aqui.%n")
                .formatted(secoes.size() + 1));
        pedido.append("\nDados:\n");
        for (int i = 0; i < secoes.size(); i++) {
            Secao secao = secoes.get(i);
            pedido.append("%n[%d] %s%n%s%n".formatted(i + 1, secao.titulo(), secao.conteudo()));
        }
        return pedido.toString();
    }

    /**
     * Separa a resposta nos trechos marcados. Devolve vazio se faltar marca, sobrar, vier fora de
     * ordem ou algum trecho sair em branco — na dúvida, o texto determinístico.
     */
    static List<String> separar(String resposta, int esperados) {
        List<String> trechos = new ArrayList<>();
        Matcher m = MARCADOR.matcher(resposta);
        if (!m.find() || Integer.parseInt(m.group(1)) != 0) {
            return List.of();
        }
        int indiceEsperado = 0;
        while (true) {
            int inicioDoTexto = m.end();
            if (Integer.parseInt(m.group(1)) != indiceEsperado) {
                return List.of();
            }
            boolean temProximo = m.find();
            String trecho = (temProximo ? resposta.substring(inicioDoTexto, m.start()) : resposta.substring(inicioDoTexto))
                    .strip();
            if (trecho.isEmpty()) {
                return List.of();
            }
            trechos.add(trecho);
            indiceEsperado++;
            if (!temProximo) {
                break;
            }
        }
        return trechos.size() == esperados ? List.copyOf(trechos) : List.of();
    }

    private static String extrairTexto(List<String> textos) {
        StringBuilder sb = new StringBuilder();
        for (String texto : textos) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(texto);
        }
        return sb.toString().trim();
    }
}
