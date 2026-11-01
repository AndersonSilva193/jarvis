package dev.jarvis.conversa;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;

/** Alvo real de uma conversa. A identidade persiste; a prova de leitura não sobrevive ao restart. */
public final class ContextoArquivo {
    private static final Set<String> EDITAVEIS = Set.of("txt", "md", "json", "xlsx");
    private static final Duration VALIDADE_LEITURA = Duration.ofMinutes(10);
    public record Estado(String id, String nome, String formato, String versao,
            boolean precisaReler, String operacaoPendente) { }
    public record Salvo(long usuario, String canal, Estado alvo) { }

    private final Clock relogio;
    private Estado alvo;
    private Instant lidoEm;
    private String pedidoAmbiguo = "";

    public ContextoArquivo() { this(Clock.systemUTC()); }
    ContextoArquivo(Clock relogio) { this.relogio = relogio; }

    public synchronized Estado estado() {
        if (alvo != null && !alvo.precisaReler() && lidoEm != null
                && !lidoEm.isAfter(relogio.instant().minus(VALIDADE_LEITURA))) exigirReleitura();
        return alvo;
    }

    public static boolean nomeValido(String nome) {
        return nome != null && !nome.isBlank() && nome.length() <= 255
                && !nome.matches("(?s).*[\\\\/:\\p{Cntrl}].*") && !nome.startsWith(".");
    }

    public static String formato(String nome) {
        int ponto = nome == null ? -1 : nome.lastIndexOf('.');
        return ponto < 0 ? "" : nome.substring(ponto + 1).toLowerCase(Locale.ROOT);
    }

    public synchronized void criado(String nome) {
        if (alvo != null && !alvo.operacaoPendente().isEmpty()) return;
        if (nomeValido(nome) && !EDITAVEIS.contains(formato(nome))) { limpar(); return; }
        if (nomeValido(nome) && EDITAVEIS.contains(formato(nome))) {
            alvo = new Estado("", nome, formato(nome), "", true, "");
            lidoEm = null;
        }
    }

    /** Somente metadados vindos do catálogo/leitor, nunca campos propostos pelo modelo. */
    public synchronized boolean registrar(JsonNode dados, boolean leitura) {
        String id = Json.texto(dados.path("id"));
        String nome = Json.texto(dados.path("nome"));
        String formato = Json.texto(dados.path("formato"));
        String versao = Json.texto(dados.path("versaoEsperada"));
        if (!id.matches("[0-9a-fA-F-]{36}") || !nomeValido(nome)
                || !EDITAVEIS.contains(formato) || !formato(nome).equals(formato)
                || !versao.matches("[0-9a-f]{64}")) return false;
        // Uma consulta não resolve uma gravação cujo resultado ainda é desconhecido.
        if (alvo != null && !alvo.operacaoPendente().isEmpty()) return false;
        boolean mesmaVersao = alvo != null && alvo.id().equalsIgnoreCase(id) && alvo.versao().equals(versao);
        boolean reler = !leitura && (!mesmaVersao || alvo.precisaReler());
        alvo = new Estado(id.toLowerCase(Locale.ROOT), nome, formato, versao, reler, "");
        if (leitura) lidoEm = relogio.instant();
        else if (!mesmaVersao) lidoEm = null;
        return true;
    }

    public synchronized void limpar() {
        if (alvo != null && !alvo.operacaoPendente().isEmpty()) return;
        alvo = null; lidoEm = null;
    }

    /** Não deixa o modelo escolher sozinho um dos resultados e editar ainda neste pedido. */
    public synchronized void selecaoAmbigua(String pedido) {
        limpar();
        pedidoAmbiguo = pedido == null ? "" : pedido;
    }

    public synchronized boolean aguardaEsclarecimento(String pedido) {
        return !pedidoAmbiguo.isEmpty() && pedidoAmbiguo.equals(pedido);
    }

    public synchronized void exigirReleitura() {
        lidoEm = null;
        if (alvo != null) alvo = new Estado(alvo.id(), alvo.nome(), alvo.formato(), alvo.versao(), true,
                alvo.operacaoPendente());
    }

    public synchronized void restaurar(Estado salvo) {
        if (salvo == null || !nomeValido(salvo.nome()) || salvo.formato() == null || !EDITAVEIS.contains(salvo.formato())
                || !formato(salvo.nome()).equals(salvo.formato())
                || salvo.id() == null || !salvo.id().matches("(?:[0-9a-fA-F-]{36})?")
                || salvo.versao() == null || !salvo.versao().matches("(?:[0-9a-f]{64})?")
                || salvo.operacaoPendente() == null
                || !salvo.operacaoPendente().matches("(?:[0-9a-fA-F-]{36})?")) return;
        alvo = salvo;
        exigirReleitura();
    }

    public synchronized boolean corresponde(String id, String nome) {
        return alvo != null && alvo.id().equalsIgnoreCase(id) && alvo.nome().equals(nome);
    }

    public synchronized String erroEdicao(String id, String versao) {
        if (alvo == null) return "Selecione e leia o arquivo com listar_arquivos/ler_arquivo antes de editar.";
        if (!alvo.operacaoPendente().isEmpty()) return "Resultado da edição ainda incerto. Consulte status_operacao_pc com id="
                + alvo.operacaoPendente() + " antes de tentar editar novamente.";
        if (alvo.id().isEmpty()) return "O arquivo selecionado precisa ser localizado; use ler_arquivo sem id.";
        if (!alvo.id().equalsIgnoreCase(id)) {
            limpar();
            return "O ID pertence a outro arquivo. Selecione e leia esse arquivo antes de editar.";
        }
        if (!alvo.versao().equals(versao)) {
            exigirReleitura();
            return "A versão informada diverge da versão conhecida. Releia o arquivo; nenhuma edição foi feita.";
        }
        if (alvo.precisaReler() || lidoEm == null || !lidoEm.isAfter(relogio.instant().minus(VALIDADE_LEITURA)))
            return "Releia o arquivo selecionado com ler_arquivo antes de editar; a leitura está ausente ou expirada.";
        return "";
    }

    public synchronized void operacaoPendente(String id) {
        if (alvo != null && id.matches("[0-9a-fA-F-]{36}")) {
            alvo = new Estado(alvo.id(), alvo.nome(), alvo.formato(), alvo.versao(), true, id);
            lidoEm = null;
        }
    }

    public synchronized void operacaoTerminou(String id) {
        if (alvo != null && alvo.operacaoPendente().equals(id)) {
            alvo = new Estado(alvo.id(), alvo.nome(), alvo.formato(), alvo.versao(), true, "");
            lidoEm = null;
        }
    }

    public synchronized void editado(String nome, String versao) {
        if (alvo == null || !alvo.nome().equals(nome)) return;
        alvo = new Estado(alvo.id(), nome, alvo.formato(), versao.matches("[0-9a-f]{64}") ? versao : alvo.versao(),
                true, "");
        lidoEm = null;
    }
}
