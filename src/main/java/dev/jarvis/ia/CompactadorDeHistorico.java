package dev.jarvis.ia;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.jarvis.ia.provedor.Bloco;
import dev.jarvis.ia.provedor.ConversorAnthropic;
import dev.jarvis.ia.provedor.Mensagem;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encolhe os turnos antigos do histórico: sai tudo que foi ida e vinda de ferramenta, fica o que
 * o dono pediu, o que o Jarvis respondeu e uma nota curta do que foi usado.
 *
 * <p>É o que mais pesa na entrada de cada pedido. O array {@code messages} é remontado do zero a
 * cada passo ({@code CicloDeFerramentas}), então um {@code listar_arquivos} que devolveu o
 * JSON do agente, ou uma busca na web com {@code encrypted_content}, voltava inteiro em todos os
 * pedidos seguintes até sair da janela de dez turnos — e com validade de 72 h isso dura três dias.
 *
 * <p>Trabalha no formato neutro ({@link Mensagem}), e é por isso que o turno compacto é legível por
 * qualquer provedor: ele é só texto. A busca na web é reconhecida pela
 * {@link Bloco.Opaco#consultaWeb()}, sem abrir o bloco de quem a fez.
 *
 * <p><b>O que a API aceita.</b> Busca web e busca de ferramentas têm blocos
 * {@code server_tool_use} e resultado, e a busca de ferramentas ainda pode deixar referências
 * às definições encontradas. Dentro da janela de turnos completos, todos ficam intactos. Ao
 * compactar, o turno inteiro vira texto, sem referências pendentes. Vale igual para
 * {@code tool_use}/{@code tool_result}, cuja regra é que todo resultado siga a chamada de mesmo id
 * na mensagem imediatamente anterior — removendo os dois juntos ela é satisfeita trivialmente. Os
 * blocos de pensamento já não chegam aqui: quem os tira é o {@code Assistente.semPensamentos}.
 *
 * <p><b>Idempotência.</b> O disco passa a guardar turnos já compactos, e {@link #aplicar} roda a
 * cada turno, então compactar o que já está compacto tem de devolver a mesma coisa. É o que a
 * {@link #PREFIXO_NOTA} resolve: a nota é reconhecida e preservada quando não há mais bloco de
 * ferramenta de onde derivá-la, e o corte da resposta nunca a alcança.
 */
public final class CompactadorDeHistorico {

    /**
     * Teto da nota. Constante, pela regra de sempre: não vale configuração nova para um número que
     * ninguém vai querer mexer — e ela é um lembrete, não o resultado.
     */
    static final int MAX_NOTA = 400;
    /** A marca que torna a nota reconhecível na segunda passada. Montada em Java, nunca pelo modelo. */
    static final String PREFIXO_NOTA = "[ferramentas: ";
    /** O corte da resposta longa. Curto e com reticências, como o {@code AcaoRegistrada.encurtar}. */
    private static final String RETICENCIAS = "[…]";
    /** O identificador do catálogo e o da memória são UUID; é por isso que ele é pescado em prosa. */
    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** Até onde descer atrás de um {@code id} no JSON que a ferramenta devolveu. */
    private static final int PROFUNDIDADE = 3;
    /** Os rótulos que uma ferramenta usa para o nome do que ela devolveu, na ordem de preferência. */
    private static final List<String> ROTULOS = List.of("nome", "assunto", "titulo");

    private final JsonMapper json;
    private final int turnosCompletos;
    private final int maxCaracteresResposta;

    public CompactadorDeHistorico(JsonMapper json, int turnosCompletos, int maxCaracteresResposta) {
        this.json = json;
        this.turnosCompletos = Math.max(0, turnosCompletos);
        this.maxCaracteresResposta = Math.max(1, maxCaracteresResposta);
    }

    /** O que vale para quem monta o {@code Assistente} à mão, como o {@code BuscaWebServidor.desligada}. */
    public static CompactadorDeHistorico padrao(JsonMapper json) {
        return new CompactadorDeHistorico(json, 1, 1500);
    }

    /**
     * Compacta todos os turnos menos os últimos, no próprio {@code Deque}.
     *
     * <p>O {@code ArrayDeque} não tem acesso por índice, e são dez elementos no máximo: copiar,
     * limpar e recolocar é mais barato que qualquer alternativa esperta.
     */
    public void aplicar(Deque<List<Mensagem>> historico) {
        int compactar = historico.size() - turnosCompletos;
        if (compactar <= 0) {
            return;
        }
        List<List<Mensagem>> todos = new ArrayList<>(historico);
        historico.clear();
        for (int i = 0; i < todos.size(); i++) {
            historico.addLast(i < compactar ? compactar(todos.get(i)) : todos.get(i));
        }
    }

    /**
     * Um turno vira duas mensagens: o texto do usuário e a resposta final com a nota.
     *
     * <p>Turno já compacto volta igual — a nota preexistente é reconhecida pelo
     * {@link #PREFIXO_NOTA} e recolocada, e o corte da resposta é estável porque incide só sobre o
     * texto, nunca sobre a nota.
     */
    List<Mensagem> compactar(List<Mensagem> turno) {
        if (turno.isEmpty()) {
            return turno;
        }
        String pedido = textoDoUsuario(turno);
        String bruto = textoDaResposta(turno);
        String notaAnterior = notaEmbutida(bruto);
        String resposta = cortar(semNota(bruto, notaAnterior));
        // Nota deste turno quando há bloco de ferramenta; senão a que já estava lá. Sem isso, a
        // segunda passada apagaria o que a primeira escreveu.
        String nota = notaDe(turno);
        if (nota.isEmpty()) {
            nota = notaAnterior;
        }
        String falado = nota.isEmpty() ? resposta : resposta + "\n" + nota;

        List<Mensagem> compacto = new ArrayList<>(2);
        compacto.add(Mensagem.texto(Mensagem.Papel.USUARIO, pedido));
        // Mensagem sem conteúdo a API recusa, como no semPensamentos.
        compacto.add(Mensagem.texto(Mensagem.Papel.ASSISTENTE,
                falado.isBlank() ? "(sem resposta)" : falado));
        return compacto;
    }

    /**
     * A nota: que ferramenta rodou e que identificador ela devolveu.
     *
     * <p>Montada <b>em Java, de dado real</b> — o nome vem do bloco {@code tool_use}, o
     * identificador do resultado que a ferramenta devolveu e a consulta do {@code input.query} da
     * busca. É a regra da {@code pergunta} do {@code Aprovacoes}: deixar o modelo narrar o que ele
     * próprio fez seria deixá-lo escolher o que o Jarvis lembra.
     *
     * <p>O identificador vai <b>inteiro</b>. Abreviar um UUID de 36 caracteres convidaria o modelo
     * a mandar para {@code apagar_arquivo} um id que nenhuma ferramenta aceita.
     */
    String nota(List<ObjectNode> turno) {
        ConversorAnthropic conversor = new ConversorAnthropic(json);
        return notaDe(turno.stream().map(conversor::mensagemDe).toList());
    }

    /** A {@link #nota(List)} de um turno já no formato neutro. */
    String notaDe(List<Mensagem> turno) {
        Map<String, String> resultados = resultadosPorId(turno);
        List<String> segmentos = new ArrayList<>();
        List<String> consultas = new ArrayList<>();
        for (Mensagem mensagem : turno) {
            for (Bloco bloco : mensagem.blocos()) {
                if (bloco instanceof Bloco.Chamada chamada) {
                    String achados = identificadores(resultados.get(chamada.id()));
                    segmentos.add(achados.isEmpty() ? chamada.nome() : chamada.nome() + " → " + achados);
                } else if (bloco instanceof Bloco.Opaco opaco) {
                    String consulta = opaco.consultaWeb();
                    if (!consulta.isBlank() && !consultas.contains(consulta)) {
                        consultas.add(consulta);
                    }
                }
            }
        }
        for (String consulta : consultas) {
            segmentos.add("busca na web: '" + consulta + "'");
        }
        return segmentos.isEmpty() ? "" : fechar(segmentos);
    }

    /**
     * Junta os segmentos dentro do teto, contando o que ficou de fora.
     *
     * <p>Descarta do fim para o começo e termina em "e mais N": um teto que se declara truncado é
     * honesto, como o {@code DiaDoDiario.MAX_ACOES}. Nunca corta no meio de um identificador, que é
     * justamente o que tornaria a nota pior que nenhuma.
     */
    private String fechar(List<String> segmentos) {
        List<String> cabem = new ArrayList<>(segmentos);
        while (true) {
            int fora = segmentos.size() - cabem.size();
            String nota = PREFIXO_NOTA + String.join("; ", cabem)
                    + (fora == 0 ? "" : " e mais " + fora) + "]";
            if (nota.length() <= MAX_NOTA || cabem.isEmpty()) {
                return nota;
            }
            cabem.removeLast();
        }
    }

    /** O conteúdo de cada resultado de ferramenta, pelo id da chamada. */
    private Map<String, String> resultadosPorId(List<Mensagem> turno) {
        Map<String, String> porId = new LinkedHashMap<>();
        for (Mensagem mensagem : turno) {
            for (Bloco bloco : mensagem.blocos()) {
                if (bloco instanceof Bloco.Resultado resultado) {
                    porId.put(resultado.id(), resultado.conteudo());
                }
            }
        }
        return porId;
    }

    /**
     * Os identificadores de um resultado, em "nome (id x), outro (id y)".
     *
     * <p>Dois caminhos porque as ferramentas devolvem de dois jeitos: {@code listar_arquivos} e
     * {@code listar_lixeira} devolvem o JSON do agente (em {@code arquivos} e em {@code itens} —
     * chaves diferentes de propósito, por isso a varredura não procura chave nenhuma, só objetos
     * com {@code id}); {@code restaurar_arquivo} e {@code buscar_memoria} devolvem prosa com o
     * UUID no meio. Não achando nada, o nome da ferramenta sozinho já diz o que foi feito.
     */
    private String identificadores(String resultado) {
        if (resultado == null || resultado.isBlank()) {
            return "";
        }
        List<String> achados = new ArrayList<>();
        try {
            JsonNode raiz = json.readTree(resultado);
            if (raiz.isObject() || raiz.isArray()) {
                colher(raiz, PROFUNDIDADE, achados);
            }
        } catch (RuntimeException naoEraJson) {
            // Resultado em prosa: o UUID abaixo é o que sobra. Ferramenta nenhuma promete JSON.
        }
        if (achados.isEmpty()) {
            Matcher procura = UUID.matcher(resultado);
            while (procura.find()) {
                String id = "(id " + procura.group() + ")";
                if (!achados.contains(id)) {
                    achados.add(id);
                }
            }
        }
        return String.join(", ", achados);
    }

    /** Desce no JSON recolhendo objetos que tenham um {@code id} textual. */
    private void colher(JsonNode no, int profundidade, List<String> achados) {
        if (profundidade < 0) {
            return;
        }
        if (no.isArray()) {
            for (JsonNode item : no) {
                colher(item, profundidade - 1, achados);
            }
            return;
        }
        if (!no.isObject()) {
            return;
        }
        String id = Json.texto(no.path("id"));
        if (!id.isBlank()) {
            String rotulo = "";
            for (String chave : ROTULOS) {
                rotulo = Json.texto(no.path(chave));
                if (!rotulo.isBlank()) {
                    break;
                }
            }
            String entrada = rotulo.isBlank() ? "(id " + id + ")" : rotulo + " (id " + id + ")";
            if (!achados.contains(entrada)) {
                achados.add(entrada);
            }
        }
        for (JsonNode filho : no.values()) {
            colher(filho, profundidade - 1, achados);
        }
    }

    /** O pedido: a primeira mensagem do usuário do turno, que já nasce como texto. */
    private String textoDoUsuario(List<Mensagem> turno) {
        for (Mensagem mensagem : turno) {
            if (mensagem.papel() == Mensagem.Papel.USUARIO) {
                if (textoSimples(mensagem)) {
                    return mensagem.texto();
                }
                String texto = blocosDeTexto(mensagem);
                if (!texto.isBlank()) {
                    return texto;
                }
            }
        }
        return "(sem pedido)";
    }

    /**
     * A resposta final: o texto da última mensagem do assistente que tenha algum.
     *
     * <p>De trás para a frente porque o turno pode ter terminado numa saída antecipada do laço, e
     * aí a última mensagem é a dos {@code tool_result}, não a do assistente.
     */
    private String textoDaResposta(List<Mensagem> turno) {
        for (int i = turno.size() - 1; i >= 0; i--) {
            Mensagem mensagem = turno.get(i);
            if (mensagem.papel() != Mensagem.Papel.ASSISTENTE) {
                continue;
            }
            String texto = textoSimples(mensagem) ? mensagem.texto() : blocosDeTexto(mensagem);
            if (!texto.isBlank()) {
                return texto;
            }
        }
        return "";
    }

    /**
     * Texto nascido em Java (o pedido, o turno já compacto): sai como está, sem aparar. O do modelo
     * vem em blocos e é aparado — a mesma distinção entre {@code content} em string e em array de
     * antes do formato neutro.
     */
    private static boolean textoSimples(Mensagem mensagem) {
        return mensagem.blocos().size() == 1
                && mensagem.blocos().getFirst() instanceof Bloco.Texto t && t.original() == null;
    }

    private String blocosDeTexto(Mensagem mensagem) {
        return mensagem.texto().trim();
    }

    /** A nota que uma passada anterior já tinha anexado, se a última linha for uma. */
    private String notaEmbutida(String texto) {
        int quebra = texto.lastIndexOf('\n');
        String ultima = (quebra < 0 ? texto : texto.substring(quebra + 1)).trim();
        return ultima.startsWith(PREFIXO_NOTA) && ultima.endsWith("]") ? ultima : "";
    }

    private String semNota(String texto, String nota) {
        if (nota.isEmpty()) {
            return texto;
        }
        return texto.substring(0, texto.lastIndexOf(nota)).stripTrailing();
    }

    private String cortar(String texto) {
        if (texto.length() <= maxCaracteresResposta) {
            return texto;
        }
        return texto.substring(0, maxCaracteresResposta - RETICENCIAS.length()).stripTrailing()
                + RETICENCIAS;
    }
}
