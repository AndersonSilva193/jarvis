package dev.jarvis.ia;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;

/**
 * O que uma tarefa de fundo mudou e se já conferiu — o estado de uma {@code Execucao} do
 * {@link CicloDeFerramentas}, sem lock próprio, como o resto dela.
 *
 * <p>Duas regras moram aqui. A primeira: depois de uma ferramenta que muda estado, o
 * {@code concluir_tarefa} é recusado até uma ferramenta de leitura rodar sem erro; a recusa sugere
 * qual, por um mapa simples. A segunda: falhas seguidas de ferramentas que mudam estado são
 * contadas — na primeira o modelo é convidado a tentar outro caminho uma vez, na segunda a tarefa
 * pergunta ao dono. A pergunta é montada aqui, em Java, do nome da ferramenta e do texto que ela
 * devolveu: o modelo não escolhe o que o dono lê sobre o que deu errado.
 */
public final class ConferenciaDoResultado {

    /** Acrescentada ao {@code tool_result} da primeira falha. */
    static final String NOTA_PRIMEIRA_FALHA = "\nNota do servidor: tente outro caminho uma vez, se houver.";
    /** Acrescentada ao {@code tool_result} da segunda falha quando o dono pede outra tentativa. */
    static final String NOTA_DONO_PEDIU = "\nNota do servidor: o dono pediu para tentar outra coisa;"
            + " se não houver outro caminho, encerre explicando.";

    /**
     * Não mudam nada e também não conferem nada: esperar, concluir, mostrar na tela ou lembrar
     * como se faz não dizem se a mudança aconteceu.
     */
    private static final Set<String> NAO_CONFEREM = Set.of("concluir_tarefa", "aguardar", "aguardar_tarefa",
            "mostrar_na_tela", "grafico_de_dados", "tocar_youtube", "abrir_pagina", "controlar_tela",
            "holograma", "ouvir_resposta", "ler_procedimento");

    /** Com que ferramenta se confere o que cada uma mudou. */
    private static final Map<String, String> SUGESTOES = Map.ofEntries(
            Map.entry("editar_arquivo", "ler_arquivo"),
            Map.entry("desfazer_edicao", "ler_arquivo"),
            Map.entry("criar_arquivo", "listar_arquivos"),
            Map.entry("apagar_arquivo", "listar_arquivos"),
            Map.entry("restaurar_arquivo", "listar_arquivos"),
            Map.entry("rodar_rotina", "status_operacao_pc ou verificar_site"),
            Map.entry("cancelar_operacao_pc", "status_operacao_pc"),
            Map.entry("tarefa_no_pc", "controlar_pc com captura_tela"),
            Map.entry("cancelar_tarefa_no_pc", "status_tarefa_no_pc"),
            Map.entry("controlar_pc", "controlar_pc com status_pc ou captura_tela"),
            Map.entry("criar_lembrete", "listar_lembretes"),
            Map.entry("cancelar_lembrete", "listar_lembretes"),
            Map.entry("registrar_memoria", "buscar_memoria"),
            Map.entry("atualizar_memoria", "buscar_memoria"),
            Map.entry("esquecer_memoria", "buscar_memoria"),
            Map.entry("desfazer_anotacao", "buscar_memoria"),
            Map.entry("criar_gatilho", "listar_gatilhos"),
            Map.entry("pausar_gatilho", "listar_gatilhos"),
            Map.entry("apagar_gatilho", "listar_gatilhos"),
            Map.entry("conceder_permissao", "minhas_permissoes"),
            Map.entry("revogar_permissao", "minhas_permissoes"),
            Map.entry("agendar_tarefa_planilha", "listar_tarefas_planilha"),
            Map.entry("revogar_tarefa_planilha", "listar_tarefas_planilha"),
            Map.entry("desfazer_acao", "acoes_do_jarvis"));
    private static final String SUGESTAO_PADRAO = "uma ferramenta de leitura ou consulta";

    /** O que cada uma tentou, em frase curta para a pergunta ao dono. */
    private static final Map<String, String> TENTATIVAS = Map.ofEntries(
            Map.entry("editar_arquivo", "editar o arquivo"),
            Map.entry("desfazer_edicao", "desfazer a edição"),
            Map.entry("criar_arquivo", "criar o arquivo"),
            Map.entry("apagar_arquivo", "mover para a lixeira"),
            Map.entry("restaurar_arquivo", "restaurar da lixeira"),
            Map.entry("tarefa_no_pc", "fazer pelo monitor do PC"),
            Map.entry("criar_planilha_google", "criar a planilha no Google"));

    private String ultimaMudanca;
    private boolean conferida = true;
    private final List<String> tentativas = new ArrayList<>();
    private final List<String> motivos = new ArrayList<>();

    /**
     * Anota uma chamada que rodou e devolve quantas falhas seguidas de mudança há agora (0 quando
     * esta não foi uma mudança que falhou).
     *
     * @param mudou  a ferramenta muda estado ({@code Ferramentas.mudaEstado})
     * @param erro   o {@code tool_result} foi {@code is_error}
     * @param falhou erro ou a falha estruturada do {@code ResultadoFerramenta}
     * @param texto  o texto integral que a ferramenta devolveu
     */
    public int registrar(String nome, JsonNode entrada, boolean mudou, boolean erro, boolean falhou, String texto) {
        if (!mudou) {
            if (!erro && !NAO_CONFEREM.contains(nome)) conferida = true;
            return 0;
        }
        ultimaMudanca = nome;
        conferida = false;
        if (!falhou) { zerarFalhas(); return 0; }
        tentativas.add(tentativa(nome, entrada));
        motivos.add(motivo(texto));
        return tentativas.size();
    }

    /** A recusa do {@code concluir_tarefa}, ou {@code null} quando ele pode rodar. */
    public String recusaDaConclusao(JsonNode entrada) {
        if (ultimaMudanca == null) return null;
        if (!conferida) return "Antes de concluir, confira o resultado (por exemplo com "
                + SUGESTOES.getOrDefault(ultimaMudanca, SUGESTAO_PADRAO) + ").";
        if (Json.texto(entrada == null ? null : entrada.path("como_conferi")).isBlank())
            return "Diga em como_conferi como você conferiu.";
        return null;
    }

    /** "Tentei a e b, e os dois falharam (motivo). Quer que eu tente outra coisa ou deixo assim?" */
    public String pergunta() {
        String a = tentativas.size() > 1 ? tentativas.get(tentativas.size() - 2) : tentativas.getLast();
        String b = tentativas.getLast();
        String motivo = String.join("; ", new LinkedHashSet<>(motivos.subList(Math.max(0, motivos.size() - 2),
                motivos.size())));
        String tentei = a.equals(b) ? "Tentei " + a + " duas vezes, e as duas falharam"
                : "Tentei " + a + " e " + b + ", e os dois falharam";
        return tentei + (motivo.isBlank() ? "" : " (" + motivo + ")")
                + ". Quer que eu tente outra coisa ou deixo assim?";
    }

    /** O dono respondeu à pergunta: a contagem recomeça. */
    public void zerarFalhas() { tentativas.clear(); motivos.clear(); }

    /** As operações pendentes terminaram e os resultados reais voltaram ao modelo. */
    public void conferidaPorResultados() { conferida = true; }

    private static String tentativa(String nome, JsonNode entrada) {
        if ("rodar_rotina".equals(nome)) {
            String rotina = Json.texto(entrada == null ? null : entrada.path("nome")).strip();
            return rotina.isEmpty() || rotina.length() > 60 ? "rodar a rotina" : "rodar a rotina " + rotina;
        }
        if ("controlar_pc".equals(nome)) {
            String acao = Json.texto(entrada == null ? null : entrada.path("acao")).strip();
            return acao.matches("[a-z_]{1,30}") ? acao.replace('_', ' ') + " no PC" : "mexer no PC";
        }
        return TENTATIVAS.getOrDefault(nome, nome.replace('_', ' '));
    }

    private static String motivo(String texto) {
        if (texto == null) return "";
        String linha = texto.strip().lines().findFirst().orElse("").strip().replaceAll("[.\\s]+$", "");
        return linha.length() > 120 ? linha.substring(0, 120) + "…" : linha;
    }
}
