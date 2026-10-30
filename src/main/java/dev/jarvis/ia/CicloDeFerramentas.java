package dev.jarvis.ia;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.custo.Origem;
import dev.jarvis.ferramentas.Ferramentas;
import dev.jarvis.ferramentas.ResultadoFerramenta;
import dev.jarvis.ia.provedor.Bloco;
import dev.jarvis.ia.provedor.ConversorAnthropic;
import dev.jarvis.ia.provedor.FormatoNeutro;
import dev.jarvis.ia.provedor.InstrucoesDeSistema;
import dev.jarvis.ia.provedor.ListaDeFerramentas;
import dev.jarvis.ia.provedor.Mensagem;
import dev.jarvis.ia.provedor.OpcoesDoPedido;
import dev.jarvis.ia.provedor.PedidoAoModelo;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.ia.provedor.RespostaDoModelo;
import dev.jarvis.ia.provedor.RespostaDoModelo.MotivoDeParada;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O laço de ferramentas de um pedido: manda o histórico ao modelo, executa as ferramentas que ele
 * pedir e repete até a resposta final (o chamado "loop de agente").
 *
 * <p>Saiu do {@link Assistente}, que continua dono de tudo que é <b>da conversa</b> — o lock, o
 * histórico, as aprovações, os anexos e o anotador. Aqui mora só o que é <b>do turno</b>: os
 * portões de repetição, a parada pedida pelo usuário e as saídas antecipadas. Não é bean: o
 * {@code Assistente} o constrói com as mesmas peças que recebeu, e nenhum construtor de teste muda.
 *
 * <p>Fala com o modelo pelo {@link ProvedorDeModelo}, no formato neutro, e não sabe qual modelo é.
 * O que é da Anthropic chega aqui fechado — a busca na web como {@link Bloco.Opaco}, a busca de
 * ferramentas como a marca {@code buscaSobDemanda} — e quem abre é o provedor.
 */
final class CicloDeFerramentas {

    /** Limite de idas e vindas com ferramentas por pedido, para nunca entrar em loop infinito. */
    private static final int MAX_PASSOS = 8;
    /** A partir desta chamada idêntica no mesmo pedido, a ferramenta é recusada sem executar. */
    private static final int LIMITE_REPETICAO = 3;
    /**
     * O limite folgado. Consulta de estado ("já terminou?") e ação em passos (volume, avançar) são
     * repetidas de propósito: aí o modelo não está em círculo, está contando.
     */
    private static final int LIMITE_REPETICAO_FOLGADO = 5;
    /** As de limite folgado que o prefixo {@code status_} não pega: as duas de ação em passos. */
    private static final List<String> REPETIVEIS = List.of("controlar_tela", "controlar_pc", "holograma");
    /** Recusas por repetição que encerram o turno: insistir depois da primeira é andar em círculo. */
    private static final int BLOQUEIOS_PARA_PARAR = 2;

    private static final Logger log = LoggerFactory.getLogger(CicloDeFerramentas.class);

    private final ProvedorDeModelo modelo;
    private final Ferramentas ferramentas;
    private final SistemaDoJarvis sistema;
    private final BuscaWebServidor buscaWeb;
    /** Só para medir o histórico no log, na forma em que ele vai ao disco. */
    private final FormatoNeutro formato;

    CicloDeFerramentas(ProvedorDeModelo modelo, Ferramentas ferramentas, SistemaDoJarvis sistema,
            BuscaWebServidor buscaWeb, JsonMapper json) {
        this.modelo = modelo;
        this.ferramentas = ferramentas;
        this.sistema = sistema;
        this.buscaWeb = buscaWeb;
        this.formato = new FormatoNeutro(json);
    }

    /**
     * Roda o turno até a resposta final, acrescentando a {@code turno} cada mensagem trocada.
     *
     * @param historico  os turnos anteriores, só lidos
     * @param detalhados recebe o resultado integral de cada ferramenta executada
     * @param parado     a marca de "o usuário pediu para parar", conferida a cada passo
     * @throws PedidoInterrompido quando a marca acende
     */
    String executar(Deque<List<Mensagem>> historico, List<Mensagem> turno, Canal canal,
            OuvinteProgresso progresso, List<ResultadoFerramenta> detalhados, BooleanSupplier parado)
            throws Exception {
        // Montado UMA vez por pedido, de propósito. O bloco dinâmico tem a hora: remontá-lo a cada
        // ida e vinda de ferramenta mudaria o system no meio do turno, e o modelo rejeita (HTTP 400)
        // os blocos de pensamento que ele devolveu sob o system anterior. É aqui, então, que o
        // resumo da memória é decidido: com o usuário do pedido e a mensagem dele, uma vez só.
        InstrucoesDeSistema system = SistemaDoJarvis.instrucoes(sistema.blocos(canal,
                progresso.usuarioAutenticado(), turno.getFirst().texto()));
        // As ferramentas também: elas são renderizadas antes do system e entram no mesmo prefixo
        // do cache de prompt. As adiadas são achadas pela busca de ferramentas do provedor, e a da
        // busca na web é server-side — quem executa é a Anthropic, e só ela a recebe.
        ListaDeFerramentas definicoes = new ListaDeFerramentas(
                ferramentas.definicoesNeutras(canal, !progresso.idTarefaPlanilha().isBlank()), true,
                buscaWeb.ativa()
                        ? List.of(Bloco.Opaco.de(ConversorAnthropic.PROVEDOR, buscaWeb.definicao()))
                        : List.of());
        // O esforço, pelo mesmo motivo do system e das definições: decidido uma vez, antes do laço.
        // Um nível que mudasse entre passos invalidaria o prefixo que o cache automático da
        // conversa acabou de escrever. A tarefa agendada gasta na mesma Origem.CONVERSA e pensa com
        // o ESFORCO_TAREFA — é o canal que separa as duas, e só aqui ele é conhecido.
        PerfilDeEsforco esforco = canal == Canal.TAREFA
                ? PerfilDeEsforco.TAREFA : PerfilDeEsforco.CONVERSA;
        int buscas = 0;
        AtomicInteger restante = new AtomicInteger(4_000);
        AtomicBoolean esqueceu = new AtomicBoolean();
        // Pesquisa real no pedido: a nativa (contada pela API) ou uma buscar_na_web que achou algo.
        // É o que libera o "pesquisaAtual" de criar_arquivo e criar_planilha_google.
        AtomicBoolean pesquisou = new AtomicBoolean();
        Map<String, String> evidencias = new HashMap<>();
        String ultimoErroDeArquivo = "";
        String ultimaFalhaDeArquivo = "";
        String falhaEdicao = "";
        // A conta das repetições vale pelo turno inteiro, não por passo: o modelo repete a mesma
        // consulta de uma ida e vinda para a outra, que é justamente onde o teto de passos não
        // ajuda — ele só percebe depois de oito chamadas pagas ao Claude.
        Map<String, Integer> chamadasPorAssinatura = new HashMap<>();
        int bloqueios = 0;
        String ultimoResultadoUtil = "";

        for (int passo = 0; passo < MAX_PASSOS; passo++) {
            // A conferência de cada passo: o usuário pediu para parar, e o passo que vem custaria
            // mais uma chamada paga ao modelo para um turno que não vai ser usado.
            if (parado.getAsBoolean()) throw new PedidoInterrompido();
            List<Mensagem> mensagens = new ArrayList<>();
            int caracteresDoHistorico = 0;
            for (List<Mensagem> turnoAnterior : historico) {
                for (Mensagem m : turnoAnterior) {
                    mensagens.add(m);
                    if (passo == 0) caracteresDoHistorico += formato.paraJson(m).toString().length();
                }
            }
            mensagens.addAll(turno);
            // Uma vez por pedido, e só com histórico: é o número que se compara antes e depois da
            // compactação. O par dele é o "Claude: entrada=..." do ClaudeClient, que é em tokens —
            // um diz o que foi montado aqui (no formato do disco), o outro o que a API cobrou.
            if (passo == 0 && !historico.isEmpty()) {
                log.info("Histórico enviado: {} turno(s), {} caracteres",
                        historico.size(), caracteresDoHistorico);
            }

            // A Origem.CONVERSA é o que liga o cache automático do provedor: o prefixo deste
            // passo é o do passo anterior mais o que a ferramenta devolveu, então do segundo passo
            // em diante tudo que já foi enviado volta como leitura de cache em vez de entrada
            // cheia. São até oito passos por pedido, e MAX_PASSOS tem de caber na janela de
            // lookback de 20 posições da API (cada passo acrescenta duas: tool_use e tool_result).
            RespostaDoModelo resposta = modelo.responder(new PedidoAoModelo(system, mensagens,
                    definicoes, OpcoesDoPedido.de(Origem.CONVERSA, esforco)));
            buscas += resposta.uso().buscasNaWeb();
            List<Bloco> conteudo = resposta.blocos();

            // A API não aceita mensagem vazia no histórico.
            turno.add(conteudo.isEmpty() ? Mensagem.texto(Mensagem.Papel.ASSISTENTE, "(sem resposta)")
                    : new Mensagem(Mensagem.Papel.ASSISTENTE, conteudo));

            MotivoDeParada motivo = resposta.motivo();
            // O provedor pausa turno longo de busca. Continuar é reenviar a mensagem do assistente
            // inalterada — ela já foi anexada crua ao turno logo acima.
            if (motivo == MotivoDeParada.PAUSA) {
                continue;
            }
            if (motivo != MotivoDeParada.FERRAMENTA) {
                registrarBuscas(buscas);
                return extrairTexto(conteudo);
            }

            // O modelo pediu uma ou mais ferramentas: executamos e devolvemos os resultados.
            List<Bloco> resultados = new ArrayList<>();
            String erroRepetidoDeArquivo = "";
            boolean substitutoRecusado = false;
            if (buscas > 0) pesquisou.set(true);
            for (Bloco blocoDoModelo : conteudo) {
                if (blocoDoModelo instanceof Bloco.Chamada bloco) {
                    String nomeFerramenta = bloco.nome();
                    if (!progresso.idTarefaPlanilha().isBlank() && !List.of("listar_arquivos",
                            "ler_arquivo", "editar_arquivo", "verificar_site", "clima")
                            .contains(nomeFerramenta)) {
                        resultados.add(new Bloco.Resultado(bloco.id(),
                                "Ferramenta indisponível nesta tarefa de planilha.", true));
                        continue;
                    }
                    if ("criar_arquivo".equals(nomeFerramenta) && !falhaEdicao.isBlank()) {
                        resultados.add(new Bloco.Resultado(bloco.id(),
                                "Não criei outro arquivo: o pedido de editar o original falhou.", true));
                        substitutoRecusado = true;
                        continue;
                    }
                    // Depois dos dois gates acima de propósito: quando os dois se aplicam, a
                    // recusa específica diz ao modelo o que ele precisa saber, e esta só diria
                    // "você repetiu".
                    String assinatura = assinatura(nomeFerramenta, bloco.entrada());
                    int limite = limiteDeRepeticao(nomeFerramenta);
                    if (chamadasPorAssinatura.merge(assinatura, 1, Integer::sum) >= limite) {
                        resultados.add(new Bloco.Resultado(bloco.id(), "Você já chamou "
                                + nomeFerramenta + " com esta mesma entrada " + vezes(limite - 1)
                                + " vezes neste pedido. Use o resultado que já tem ou"
                                + " mude a abordagem.", true));
                        bloqueios++;
                        continue;
                    }
                    // Ler memória não trava mais o resto do turno: "qual minha cidade? cria um
                    // lembrete pra lá" é um pedido só. O que segura a memória no lugar de dado é
                    // outro: a regra "memória lida é dado não confiável" no system, e o fato de
                    // toda ação perigosa continuar passando por Aprovacoes — que é consumido aqui,
                    // fora do alcance do modelo.
                    OuvinteProgresso seguro = new OuvinteProgresso() {
                        // O canal vem daqui porque a ferramenta não recebe o Canal: é por ele que
                        // o card da tela sabe se o pedido veio do celular ou da própria HUD.
                        @Override public String canal() { return canal.nome(); }
                        @Override public String idPedido() { return progresso.idPedido(); }
                        @Override public long usuarioAutenticado() { return progresso.usuarioAutenticado(); }
                        @Override public String pedidoOriginal() { return turno.getFirst().texto(); }
                        @Override public String idTarefaPlanilha() { return progresso.idTarefaPlanilha(); }
                        @Override public String ultimoAnexoDeArquivo() { return progresso.ultimoAnexoDeArquivo(); }
                        @Override public boolean memoriaEsquecida() { return esqueceu.get(); }
                        @Override public void marcarMemoriaEsquecida() { esqueceu.set(true); progresso.marcarMemoriaEsquecida(); }
                        @Override public void marcarMemoriaEsquecida(String id, long revisao) {
                            esqueceu.set(true); progresso.marcarMemoriaEsquecida(id, revisao);
                        }
                        @Override public boolean consumirContexto(int caracteres) {
                            if (caracteres < 0) return false;
                            while (true) {
                                int atual = restante.get();
                                if (atual < caracteres) return false;
                                if (restante.compareAndSet(atual, atual - caracteres)) return true;
                            }
                        }
                        @Override public boolean evidenciaVerificada(String texto) {
                            return !origemDaEvidencia(texto).isEmpty();
                        }
                        @Override public String origemDaEvidencia(String texto) {
                            return evidencias.entrySet().stream().filter(e -> e.getKey().contains(texto))
                                    .map(e -> "ferramenta:" + e.getValue()).findFirst().orElse("");
                        }
                        @Override public boolean pesquisaFeita() { return pesquisou.get(); }
                        @Override public void ferramenta(String nome, String descricao, Integer atual, Integer total) {
                            OuvinteProgresso.avisar(() -> progresso.ferramenta(nome, descricao, atual, total));
                        }
                    };
                    // A parada vale também dentro do passo que já voltou com tool_use: o que o
                    // usuário mandou parar é a ferramenta, não a chamada ao modelo.
                    if (parado.getAsBoolean()) throw new PedidoInterrompido();
                    int anteriores = detalhados.size();
                    Bloco.Resultado resultado = ferramentas.executar(bloco, seguro, detalhados::add);
                    // A Ferramentas limita apenas o texto que volta ao modelo. As decisões deste
                    // laço continuam usando o resultado integral recebido pelo callback.
                    String textoIntegral = detalhados.size() > anteriores
                            ? detalhados.getLast().texto() : resultado.conteudo();
                    if ("editar_arquivo".equals(nomeFerramenta)) {
                        boolean preparada = detalhados.size() > anteriores
                                && (detalhados.getLast().confirmacao() != null
                                        || !detalhados.getLast().artefatos().isEmpty());
                        falhaEdicao = preparada ? "" : textoIntegral;
                    }
                    if ("buscar_na_web".equals(nomeFerramenta) && !resultado.erro()
                            && textoIntegral.startsWith("Fonte: "))
                        pesquisou.set(true);
                    if (List.of("verificar_site", "status_servidor", "clima").contains(nomeFerramenta)
                            && !resultado.erro())
                        evidencias.put(textoIntegral, nomeFerramenta);
                    resultados.add(resultado);
                    // O que vai na frase de desistência, se o modelo insistir na repetição.
                    // "Útil" aqui é "executou e não estourou": falha de negócio volta sem
                    // is_error, que a Ferramentas só marca em exceção ou ferramenta desconhecida.
                    if (!resultado.erro()) {
                        String util = textoIntegral;
                        if (!util.isBlank()) ultimoResultadoUtil = util;
                    }
                    if ("criar_arquivo".equals(nomeFerramenta)
                            || "criar_planilha_google".equals(nomeFerramenta)) {
                        String textoResultado = textoIntegral;
                        ultimoErroDeArquivo = textoResultado.startsWith("Arquivo criado:")
                                || textoResultado.startsWith("Planilha criada:") ? ""
                                : textoResultado.substring(0, Math.min(240, textoResultado.length()));
                        if (!ultimoErroDeArquivo.isBlank()) {
                            // A mesma assinatura do portão acima, mais o erro: aqui basta a
                            // segunda tentativa, e o motivo real vale mais que "você repetiu".
                            String chave = assinatura + '\u0000' + ultimoErroDeArquivo;
                            if (chave.equals(ultimaFalhaDeArquivo)) erroRepetidoDeArquivo = ultimoErroDeArquivo;
                            ultimaFalhaDeArquivo = chave;
                        } else ultimaFalhaDeArquivo = "";
                    }
                }
            }
            // Busca de ferramentas e busca web já vêm resolvidas pelo servidor do provedor e não
            // passam pelo registro de ferramentas. Se o turno só tinha desses blocos, não
            // há resultado para devolver — e mensagem de content vazio a API recusa.
            if (resultados.isEmpty()) {
                registrarBuscas(buscas);
                return extrairTexto(conteudo);
            }
            turno.add(new Mensagem(Mensagem.Papel.USUARIO, resultados));
            if (esqueceu.get()) return "Memória esquecida.";
            if (substitutoRecusado) return "Não alterei o arquivo original e não criei um substituto. "
                    + falhaEdicao.substring(0, Math.min(240, falhaEdicao.length()));
            // Repetir a mesma entrada recusada desperdiça chamadas do modelo e acabava no erro
            // genérico da HUD. O turno já tem o tool_result, então o histórico continua válido.
            if (!erroRepetidoDeArquivo.isBlank()) return erroRepetidoDeArquivo;
            // Por último: as saídas acima sabem dizer o que deu errado, e esta só sabe que o
            // modelo estava andando em círculo. O turno já tem os tool_result das recusas, então
            // o histórico continua válido.
            if (bloqueios >= BLOQUEIOS_PARA_PARAR) {
                log.warn("Turno encerrado: {} chamada(s) repetida(s) recusada(s)", bloqueios);
                return ("Parei porque estava repetindo a mesma consulta sem avançar. "
                        + ultimoResultadoUtil.substring(0,
                                Math.min(240, ultimoResultadoUtil.length()))).trim();
            }
        }
        registrarBuscas(buscas);
        throw new IllegalStateException("precisei de passos demais para resolver isso. "
                + (ultimoErroDeArquivo.isBlank() ? "Tente um pedido mais simples." : ultimoErroDeArquivo));
    }

    /**
     * Quantas buscas na web o pedido gastou. Cada uma é cobrada à parte dos tokens, então bater no
     * teto merece aviso: é o sinal de pedido caro, ou de pergunta que não precisava de busca.
     */
    private void registrarBuscas(int buscas) {
        if (buscas <= 0) {
            return;
        }
        if (buscaWeb.maxUsos() > 0 && buscas >= buscaWeb.maxUsos()) {
            log.warn("Busca na web: {} buscas neste pedido, o teto (BUSCA_WEB_MAX_USOS)", buscas);
        } else {
            log.info("Busca na web: {} busca(s) neste pedido", buscas);
        }
    }

    /** Quantas chamadas idênticas a ferramenta aguenta antes de a próxima ser recusada. */
    private static int limiteDeRepeticao(String nome) {
        return nome.startsWith("status_") || REPETIVEIS.contains(nome)
                ? LIMITE_REPETICAO_FOLGADO : LIMITE_REPETICAO;
    }

    /** A mesma chamada é o mesmo nome com a mesma entrada, seja qual for a ordem das chaves. */
    private static String assinatura(String nome, JsonNode entrada) {
        return nome + '\u0000' + Json.canonico(entrada);
    }

    /**
     * O número da recusa por extenso. O texto da recusa é fixo, mas a contagem acompanha o limite
     * da ferramenta: dizer "duas vezes" na quinta chamada de um {@code status_} seria o servidor
     * contando errado para o modelo.
     */
    private static String vezes(int quantas) {
        return switch (quantas) {
            case 2 -> "duas";
            case 4 -> "quatro";
            default -> String.valueOf(quantas);
        };
    }

    private static String extrairTexto(List<Bloco> conteudo) {
        StringBuilder sb = new StringBuilder();
        for (Bloco bloco : conteudo) {
            if (bloco instanceof Bloco.Texto texto) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(texto.texto());
            }
        }
        return sb.isEmpty() ? "(sem resposta)" : sb.toString().trim();
    }
}
