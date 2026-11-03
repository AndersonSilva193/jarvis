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
    private dev.jarvis.tela.PresencaDaHud presenca;
    @org.springframework.beans.factory.annotation.Autowired
    void configurarPresenca(dev.jarvis.tela.PresencaDaHud presenca) { this.presenca = presenca; }

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
        return "Consulta o diário de ações de hoje, ontem ou da semana: arquivos, memória, PC e ações "
                + "aprovadas. Use para 'o que você fez hoje?' e para achar o ID de desfazer_acao. assunto filtra "
                + "a categoria; horario (HH ou HH:mm) e texto encontram o alvo antes do corte de oito linhas. "
                + "Não leia IDs em voz alta. Gasto é custo_do_jarvis, lembretes futuros são listar_lembretes "
                + "e lembranças guardadas são listar_memorias.";
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
        propriedades.putObject("horario").put("type", "string").put("pattern", "^(?:[01][0-9]|2[0-3])(?::[0-5][0-9])?$")
                .put("description", "Hora local HH ou minuto HH:mm da ação procurada.");
        propriedades.putObject("texto").put("type", "string").put("maxLength", 120)
                .put("description", "Parte do nome do arquivo ou da descrição procurada.");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) {
        return consultar(entrada, 0);
    }

    private DiarioDeAcoes.Dia periodo(String periodo) {
        return switch (periodo) {
            case "ontem" -> diario.deOntem();
            case "semana" -> diario.periodo(7);
            default -> diario.deHoje();
        };
    }

    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, dev.jarvis.conversa.OuvinteProgresso contexto) {
        long usuario = contexto.usuarioAutenticado();
        if (usuario <= 0) return ResultadoFerramenta.texto("Pedido autenticado necessário para consultar ações.");
        String resumo = consultar(entrada, usuario);
        List<dev.jarvis.tela.CardTela> cards = new ArrayList<>();
        if ("hud".equals(contexto.canal()) && presenca != null && presenca.conectada()) {
            var periodo = periodo(entrada == null ? "" : Json.texto(entrada.path("periodo")));
            var porDia = new java.util.TreeMap<java.time.LocalDate, List<AcaoRegistrada>>();
            for (AcaoRegistrada acao : periodo.acoes()) {
                if (acao.usuario() != 0 && acao.usuario() != usuario) continue;
                porDia.computeIfAbsent(acao.instante().atZone(diario.zona()).toLocalDate(), d -> new ArrayList<>()).add(acao);
            }
            porDia.forEach((dia, acoes) -> cards.add(dev.jarvis.tela.MontadorDeLinhaDoTempo.montar(
                    dia, acoes, diario.zona(), diario.agora().toString(), diario.doDia(dia).descartadas())));
        }
        return new ResultadoFerramenta(resumo, List.of(), List.of(), null, cards, List.of());
    }

    private String consultar(JsonNode entrada, long usuario) {
        String periodo = entrada == null ? "" : Json.texto(entrada.path("periodo")).trim();
        String assunto = entrada == null ? "" : Json.texto(entrada.path("assunto")).trim();
        // O fuso e o "hoje" vêm do diário, não de um LocalDate.now() daqui: duas noções de dia
        // divergiriam na virada da meia-noite e na hora de formatar a hora de cada ação.
        ZoneId zona = diario.zona();

        DiarioDeAcoes.Dia dia = periodo(periodo);
        String horario = entrada == null ? "" : Json.texto(entrada.path("horario"));
        String procurado = dev.jarvis.util.Texto.normalizar(entrada == null ? "" : Json.texto(entrada.path("texto")));
        if (!horario.isEmpty() && !horario.matches("(?:[01][0-9]|2[0-3])(?::[0-5][0-9])?")) return "Horário inválido: use HH ou HH:mm.";

        Set<TipoDeAcao> filtro = tiposDe(assunto);
        List<AcaoRegistrada> acoes = dia.acoes().stream()
                .filter(a -> usuario == 0 || a.usuario() == 0 || a.usuario() == usuario)
                .filter(a -> horario.isEmpty() || a.instante().atZone(zona).format(HORA).startsWith(horario))
                .filter(a -> procurado.isEmpty() || dev.jarvis.util.Texto.normalizar(a.descricao()).contains(procurado))
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
                    .append(" — [").append(acao.id()).append("] ").append(acao.descricao())
                    .append(acao.reversivel() ? " (" + acao.estadoDesfazer() + ")" : "")
                    .append(acao.semPergunta() ? "" : " (aprovado)").append('.');
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
                tipos.add(TipoDeAcao.APRENDER_PROCEDIMENTO);
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
