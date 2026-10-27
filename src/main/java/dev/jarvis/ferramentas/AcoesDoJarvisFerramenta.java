package dev.jarvis.ferramentas;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import dev.jarvis.diario.AcaoRegistrada;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** O que o Jarvis fez — sozinho e depois de um "sim" — hoje, ontem ou na semana. */
@Component
public class AcoesDoJarvisFerramenta implements Ferramenta {

    /** Quantas linhas cabem na resposta antes de ela virar leitura de planilha em voz alta. */
    private static final int MAX_LINHAS = 8;

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM");

    private final DiarioDeAcoes diario;
    private final JsonMapper json;

    public AcoesDoJarvisFerramenta(DiarioDeAcoes diario, JsonMapper json) {
        this.diario = diario;
        this.json = json;
    }

    @Override
    public String nome() {
        return "acoes_do_jarvis";
    }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.SISTEMA; }
    @Override public boolean simples() { return true; }

    @Override
    public String descricao() {
        return "Consulta o diário do que o Jarvis fez hoje, ontem ou na semana: arquivos, "
                + "memória, rotinas, tarefas visuais e ações aprovadas. Use para 'o que você fez hoje?', "
                + "'mexeu nos meus arquivos?' ou 'o que anotou?'. O campo assunto filtra a lista. "
                + "Gasto em dólares é custo_do_jarvis; lembretes futuros são listar_lembretes; "
                + "lembranças guardadas são listar_memorias.";
    }

    /**
     * Dois {@code enum} fechados, e nenhum número livre.
     *
     * É a regra de {@code verificar_site} e a do {@code custo_do_jarvis}: um inteiro de dias
     * convidaria o modelo a pedir trinta e ler trinta arquivos no caminho crítico de um turno —
     * para responder a uma pergunta de quatro palavras.
     */
    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        ObjectNode propriedades = esquema.putObject("properties");

        ObjectNode periodo = propriedades.putObject("periodo");
        periodo.put("type", "string");
        periodo.put("description", "hoje (padrão), ontem, semana = últimos 7 dias.");
        periodo.putArray("enum").add("hoje").add("ontem").add("semana");

        ObjectNode assunto = propriedades.putObject("assunto");
        assunto.put("type", "string");
        assunto.put("description", "tudo (padrão); arquivos = edição, criação, restauração e "
                + "exclusão; memoria = o que anotei e corrigi; pc = rotina, monitor e desligar.");
        assunto.putArray("enum").add("tudo").add("arquivos").add("memoria").add("pc");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) {
        String periodo = entrada == null ? "" : Json.texto(entrada.path("periodo")).trim();
        String assunto = entrada == null ? "" : Json.texto(entrada.path("assunto")).trim();
        // O fuso e o "hoje" vêm do diário, não de um LocalDate.now() daqui: duas noções de dia
        // divergiriam na virada da meia-noite e na hora de formatar a hora de cada ação.
        ZoneId zona = diario.zona();

        DiarioDeAcoes.Dia dia = switch (periodo) {
            case "ontem" -> diario.deOntem();
            case "semana" -> diario.periodo(7);
            default -> diario.deHoje();
        };

        Set<TipoDeAcao> filtro = tiposDe(assunto);
        List<AcaoRegistrada> acoes = dia.acoes().stream()
                .filter(acao -> filtro.isEmpty() || filtro.contains(acao.tipo()))
                .toList();

        if (acoes.isEmpty()) {
            return nadaFeito(periodo, assunto);
        }

        StringBuilder texto = new StringBuilder(titulo(periodo));
        texto.append(": ").append(acoes.size()).append(acoes.size() == 1 ? " ação." : " ações.");

        // Da mais recente para a mais antiga: quem pergunta "o que você fez?" quer o que acabou de
        // acontecer, e é o fim da lista que o teto de linhas corta.
        List<AcaoRegistrada> recentes = new ArrayList<>(acoes);
        recentes.sort((a, b) -> b.instante().compareTo(a.instante()));

        boolean varriosDias = !dia.de().equals(dia.ate());
        for (AcaoRegistrada acao : recentes.stream().limit(MAX_LINHAS).toList()) {
            var quando = acao.instante().atZone(zona);
            texto.append(' ')
                    .append(varriosDias ? quando.format(DIA) + " " + quando.format(HORA)
                            : quando.format(HORA))
                    .append(" — ").append(acao.descricao())
                    .append(acao.tipo().autonoma() ? "" : " (aprovado)").append('.');
        }
        int sobrando = recentes.size() - MAX_LINHAS;
        if (sobrando > 0) {
            texto.append(" E mais ").append(sobrando).append('.');
        }
        if (dia.descartadas() > 0) {
            texto.append(" Houve ").append(dia.descartadas())
                    .append(" ação(ões) além do que o diário guarda por dia; a lista está incompleta.");
        }
        return texto.toString();
    }

    /** Conjunto vazio = sem filtro. Assunto que não conhecemos cai em "tudo", não em nada. */
    private static Set<TipoDeAcao> tiposDe(String assunto) {
        Set<TipoDeAcao> tipos = new LinkedHashSet<>();
        switch (assunto) {
            case "arquivos" -> {
                tipos.add(TipoDeAcao.EDICAO_ARQUIVO);
                tipos.add(TipoDeAcao.DESFAZER_EDICAO);
                tipos.add(TipoDeAcao.CRIAR_ARQUIVO);
                tipos.add(TipoDeAcao.RESTAURAR_ARQUIVO);
                tipos.add(TipoDeAcao.APAGAR_ARQUIVO);
            }
            case "memoria" -> {
                tipos.add(TipoDeAcao.ANOTAR_MEMORIA);
                tipos.add(TipoDeAcao.ATUALIZAR_MEMORIA);
                tipos.add(TipoDeAcao.ESQUECER_MEMORIA);
            }
            case "pc" -> {
                tipos.add(TipoDeAcao.ROTINA);
                tipos.add(TipoDeAcao.COMPUTER_USE);
                tipos.add(TipoDeAcao.DESLIGAR_PC);
                tipos.add(TipoDeAcao.TAREFA_PLANILHA);
            }
            default -> { /* tudo */ }
        }
        return tipos;
    }

    private static String titulo(String periodo) {
        return switch (periodo) {
            case "ontem" -> "Ontem";
            case "semana" -> "Últimos 7 dias";
            default -> "Hoje";
        };
    }

    /** Frase, não lista vazia: isto é lido em voz alta, e "nenhum resultado" não é resposta. */
    private static String nadaFeito(String periodo, String assunto) {
        String quando = switch (periodo) {
            case "ontem" -> "ontem";
            case "semana" -> "nos últimos 7 dias";
            default -> "hoje";
        };
        String oQue = switch (assunto) {
            case "arquivos" -> " nos arquivos";
            case "memoria" -> " na memória";
            case "pc" -> " no PC";
            default -> "";
        };
        return "Não mexi em nada" + oQue + " " + quando + ".";
    }
}
