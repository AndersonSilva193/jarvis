package dev.jarvis.ferramentas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.holograma.ComandoDoHolograma;
import dev.jarvis.holograma.DetalheHolograma;
import dev.jarvis.holograma.EstadoDoHolograma;
import dev.jarvis.holograma.EstadoDoHologramaService;
import dev.jarvis.holograma.FalasDoHolograma;
import dev.jarvis.holograma.GaleriaDeHologramas;
import dev.jarvis.holograma.HologramaService;
import dev.jarvis.holograma.PromptDeHolograma;
import dev.jarvis.holograma.ResolvedorDeAlvo;
import dev.jarvis.holograma.SaidaHolograma;
import dev.jarvis.proativo.AvisosProativos;
import dev.jarvis.proativo.FalasService;
import dev.jarvis.tela.PresencaDaHud;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * O holograma inteiro numa ferramenta só: abrir, carregar um objeto e mexer na cena.
 *
 * <p>Uma ferramenta com {@code acao} em enum, e não dez: pesa menos no contexto (o Qwen local tem
 * 8 mil tokens), e um pedido composto — "abre o holograma e carrega o motor V8 explodido" — vira
 * três chamadas da mesma ferramenta na mesma resposta, que o {@code CicloDeFerramentas} executa em
 * ordem.
 *
 * <p>Tudo que é número ou id sai daqui resolvido: o "mais" vira o fator final, partindo do estado
 * atual (ou do que o servidor acabou de mandar, que a HUD ainda não reportou), e o alvo "os pistões"
 * vira a lista de grupos e peças pelo {@link ResolvedorDeAlvo}. Empate não manda nada: volta ao
 * modelo com as opções, e ele pergunta.
 *
 * <p>Nível <i>faz e avisa</i>. Sem HUD, a cena não existe: as ações de cena dizem isso, e o
 * {@code carregar} oferece gerar e guardar na galeria ({@code gerar_na_galeria}).
 */
@Component
public class HologramaFerramenta extends FerramentaDeTela {

    private static final Logger log = LoggerFactory.getLogger(HologramaFerramenta.class);

    static final String NOME = "holograma";

    static final List<String> ACOES = List.of("abrir", "fechar", "carregar", "explodir", "montar",
            "girar", "girar_continuo", "parar", "zoom", "vista", "destacar", "isolar", "esconder",
            "mostrar_tudo", "selecionar", "explicar", "resetar", "cancelar_geracao", "galeria",
            "gerar_na_galeria");

    static final String SEM_HUD = "O holograma precisa da HUD aberta. Diga isso em uma frase.";
    static final String SEM_HUD_CARREGAR = "A HUD está fechada. Ofereça gerar o objeto agora e deixá-lo "
            + "salvo na galeria; se o dono aceitar, chame holograma com acao=gerar_na_galeria e o mesmo objeto.";
    static final String FECHADO = "O holograma está fechado. Carregue um objeto antes (acao=carregar).";
    /** O passo de "mais" e "menos" na explosão. */
    static final double PASSO = 0.25;
    private static final int MAX_LISTA_GALERIA = 8;

    /** A geração para a galeria, sem HUD. Interface para o teste não subir o serviço inteiro. */
    @FunctionalInterface
    interface GeracaoSemTela {
        String gerar(String objeto, DetalheHolograma detalhe, Consumer<SaidaHolograma.Fim> aoTerminar);
    }

    private final EstadoDoHologramaService estado;
    private final GaleriaDeHologramas galeria;
    private final FalasDoHolograma falas;
    private final GeracaoSemTela semTela;
    /** O recado de "ficou pronto na galeria", que sai pelo Telegram. */
    private final Consumer<String> avisar;
    private final FalasService frases;

    @Autowired
    public HologramaFerramenta(PresencaDaHud hud, JsonMapper json, EstadoDoHologramaService estado,
            GaleriaDeHologramas galeria, FalasDoHolograma falas, HologramaService geracao,
            AvisosProativos avisos, FalasService frases) {
        this(hud, json, estado, galeria, falas, geracao::gerarSemTela,
                texto -> avisos.avisar("holograma:galeria", texto, texto, null), frases);
    }

    HologramaFerramenta(PresencaDaHud hud, JsonMapper json, EstadoDoHologramaService estado,
            GaleriaDeHologramas galeria, FalasDoHolograma falas, GeracaoSemTela semTela,
            Consumer<String> avisar, FalasService frases) {
        super(hud, json);
        this.estado = estado;
        this.galeria = galeria;
        this.falas = falas;
        this.semTela = semTela;
        this.avisar = avisar;
        this.frases = frases;
    }

    @Override public String nome() { return NOME; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.TELA; }
    @Override public boolean simples() { return true; }

    @Override
    public String descricao() {
        return "Holograma 3D da HUD. carregar mostra um objeto (abre o modo); as outras ações mexem no "
                + "objeto atual: explodir (valor 0..1, mais, menos), montar, girar (eixo, valor=graus), "
                + "girar_continuo, parar, zoom (aproximar/afastar), vista (frente/lado/cima/isometrica), "
                + "destacar/isolar/esconder/selecionar/explicar (alvo = nome ou grupo da peça). Pedido "
                + "composto vira várias chamadas em sequência.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        ObjectNode campos = esquema.putObject("properties");
        ObjectNode acao = campos.putObject("acao").put("type", "string");
        ACOES.forEach(acao.putArray("enum")::add);
        campos.putObject("objeto").put("type", "string").put("description", "O que carregar.");
        var niveis = campos.putObject("detalhe").put("type", "string").putArray("enum");
        for (DetalheHolograma nivel : DetalheHolograma.values()) niveis.add(nivel.chave());
        campos.putObject("novo").put("type", "boolean").put("description", "Gerar de novo, sem a galeria.");
        ObjectNode provedor = campos.putObject("provedor").put("type", "string");
        provedor.putArray("enum").add("haiku").add("local");
        campos.putObject("alvo").put("type", "string").put("description", "Nome ou grupo da peça.");
        campos.putObject("valor").put("type", "string")
                .put("description", "Fator, mais/menos, graus, aproximar/afastar ou a vista.");
        ObjectNode eixo = campos.putObject("eixo").put("type", "string");
        eixo.putArray("enum").add("x").add("y").add("z");
        campos.putObject("ligar").put("type", "boolean");
        esquema.putArray("required").add("acao");
        return esquema;
    }

    @Override
    public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso) {
        String acao = Json.texto(entrada.path("acao")).trim();
        boolean rapido = entrada.path("rapido").asBoolean(false);
        if (!ACOES.contains(acao)) {
            return ResultadoFerramenta.texto("Não conheço a ação '" + acao + "'. As válidas são: " + ACOES + ".");
        }
        log.info("Holograma: acao={} via={}", acao, rapido ? "caminho rápido" : "modelo");
        if ("gerar_na_galeria".equals(acao)) {
            return gerarNaGaleria(entrada);
        }
        if ("galeria".equals(acao)) {
            return ResultadoFerramenta.texto(listaDaGaleria());
        }
        if (telaFechada()) {
            return ResultadoFerramenta.texto("carregar".equals(acao) ? SEM_HUD_CARREGAR : SEM_HUD);
        }
        String valor = Json.texto(entrada.path("valor")).trim();
        return switch (acao) {
            case "abrir" -> enviar("Holograma aberto. Confirme em poucas palavras.",
                    ComandoDoHolograma.de(ComandoDoHolograma.ABRIR), rapido);
            case "fechar" -> enviar("Holograma fechado. Confirme em poucas palavras.",
                    ComandoDoHolograma.de(ComandoDoHolograma.FECHAR), rapido);
            case "carregar" -> carregar(entrada, rapido);
            case "cancelar_geracao" -> enviar("Geração cancelada; o que já chegou fica na tela.",
                    ComandoDoHolograma.de(ComandoDoHolograma.CANCELAR_GERACAO), rapido);
            case "explicar" -> explicar(Json.texto(entrada.path("alvo")).trim());
            default -> !estado.abertoAgora() ? ResultadoFerramenta.texto(FECHADO) : cena(acao, valor, entrada, rapido);
        };
    }

    private ResultadoFerramenta cena(String acao, String valor, JsonNode entrada, boolean rapido) {
        return switch (acao) {
            case "explodir" -> explodir(valor, rapido);
            case "montar" -> enviar("Montado.", explosao(0), rapido);
            case "girar" -> girar(Json.texto(entrada.path("eixo")).trim(), valor, rapido);
            case "girar_continuo" -> {
                boolean ligar = !entrada.has("ligar") || entrada.path("ligar").asBoolean(true);
                double velocidade = limitar(numero(valor).orElse(1.0), 0.1, 5);
                yield enviar(ligar ? "Girando." : "Giro parado.",
                        ComandoDoHolograma.de(ComandoDoHolograma.GIRAR_CONTINUO,
                                dados("ligar", ligar, "velocidade", velocidade)), rapido);
            }
            case "parar" -> enviar("Parado.", ComandoDoHolograma.de(ComandoDoHolograma.PARAR), rapido);
            case "zoom" -> zoom(valor, rapido);
            case "vista" -> vista(valor, rapido);
            case "destacar", "isolar", "esconder" -> filtrar(acao, Json.texto(entrada.path("alvo")).trim(), rapido);
            case "mostrar_tudo" -> enviar("Tudo à vista.", ComandoDoHolograma.de(ComandoDoHolograma.MOSTRAR_TUDO), rapido);
            case "selecionar" -> selecionar(Json.texto(entrada.path("alvo")).trim(), rapido);
            case "resetar" -> enviar("Vista reiniciada.", ComandoDoHolograma.de(ComandoDoHolograma.RESETAR), rapido);
            default -> ResultadoFerramenta.texto("Ação sem tratamento: " + acao);
        };
    }

    /**
     * Abre (se fechado) e mostra o objeto. Quem consulta a galeria antes de gerar é a rota que a HUD
     * vai chamar; daqui só se olha para escolher a frase — "na tela" é verdade só se já existe.
     */
    private ResultadoFerramenta carregar(JsonNode entrada, boolean rapido) {
        String bruto = Json.texto(entrada.path("objeto"));
        String objeto = PromptDeHolograma.higienizar(bruto);
        if (objeto.isEmpty()) {
            return ResultadoFerramenta.texto("Diga em 'objeto' o que carregar.");
        }
        Optional<DetalheHolograma> nivel = DetalheHolograma.porChave(Json.texto(entrada.path("detalhe")));
        if (nivel.isEmpty()) {
            return ResultadoFerramenta.texto("O detalhe é rapido, normal ou detalhado.");
        }
        boolean novo = entrada.path("novo").asBoolean(false);
        String provedor = Json.texto(entrada.path("provedor")).trim();
        if (!provedor.isEmpty() && HologramaService.provedorDoPedido(provedor).isEmpty()) {
            return ResultadoFerramenta.texto("O provedor é haiku ou local.");
        }
        Optional<GaleriaDeHologramas.HologramaSalvo> salvo = novo ? Optional.empty()
                : galeria.buscar(objeto, nivel.get());
        String fala = salvo.map(s -> falas.daGaleria(s.meta().nome().isBlank() ? objeto : s.meta().nome()))
                .orElseGet(() -> falas.construindo(objeto));
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("objeto", objeto);
        dados.put("detalhe", nivel.get().chave());
        dados.put("novo", novo);
        if (!provedor.isEmpty()) dados.put("provedor", provedor);
        dados.put("falaPronto", falas.pronto());
        String retorno = "Diga só: '" + fala + "'."
                + (salvo.isPresent() ? "" : " A montagem segue na tela; o fim é anunciado sozinho.");
        return enviar(retorno, ComandoDoHolograma.de(ComandoDoHolograma.CARREGAR, dados), rapido);
    }

    private ResultadoFerramenta explodir(String valor, boolean rapido) {
        double atual = estado.explosaoAtual();
        double fator;
        String normalizado = valor.toLowerCase(Locale.ROOT);
        if (normalizado.isEmpty()) fator = 1;
        else if (normalizado.equals("mais")) fator = atual + PASSO;
        else if (normalizado.equals("menos")) fator = atual - PASSO;
        else {
            Optional<Double> numero = numero(normalizado);
            if (numero.isEmpty()) {
                return ResultadoFerramenta.texto("Em explodir, valor é um fator de 0 a 1, 'mais' ou 'menos'.");
            }
            fator = numero.get() > 1 ? numero.get() / 100 : numero.get();
        }
        fator = Math.round(limitar(fator, 0, 1) * 100) / 100.0;
        return enviar(fator == 0 ? "Montado." : "Explosão em " + Math.round(fator * 100) + "%.",
                explosao(fator), rapido);
    }

    private static ComandoDoHolograma explosao(double fator) {
        return ComandoDoHolograma.de(ComandoDoHolograma.EXPLODIR, dados("fator", fator));
    }

    private ResultadoFerramenta girar(String eixo, String valor, boolean rapido) {
        String qual = eixo.isEmpty() ? "y" : eixo.toLowerCase(Locale.ROOT);
        if (!List.of("x", "y", "z").contains(qual)) {
            return ResultadoFerramenta.texto("O eixo é x, y ou z.");
        }
        double graus = limitar(numero(valor).orElse(90.0), -720, 720);
        return enviar("Girado " + Math.round(graus) + "°.",
                ComandoDoHolograma.de(ComandoDoHolograma.GIRAR, dados("eixo", qual, "graus", graus)), rapido);
    }

    private ResultadoFerramenta zoom(String valor, boolean rapido) {
        String normalizado = valor.toLowerCase(Locale.ROOT);
        double fator = switch (normalizado) {
            case "", "aproximar", "mais", "perto" -> 1.5;
            case "afastar", "menos", "longe" -> 1 / 1.5;
            default -> numero(normalizado).map(n -> limitar(n, 0.25, 4)).orElse(-1.0);
        };
        if (fator < 0) {
            return ResultadoFerramenta.texto("Em zoom, valor é aproximar, afastar ou um fator (2 = o dobro).");
        }
        return enviar(fator >= 1 ? "Aproximado." : "Afastado.",
                ComandoDoHolograma.de(ComandoDoHolograma.ZOOM, dados("fator", Math.round(fator * 1000) / 1000.0)), rapido);
    }

    private ResultadoFerramenta vista(String valor, boolean rapido) {
        String vista = dev.jarvis.util.Texto.normalizar(valor).replaceFirst("^(vista|visao)?\\s*(de|da|do|pela)?\\s*", "");
        if (!List.of("frente", "lado", "cima", "isometrica").contains(vista)) {
            return ResultadoFerramenta.texto("A vista é frente, lado, cima ou isometrica.");
        }
        return enviar("Vista " + (vista.equals("isometrica") ? "isométrica" : "de " + vista) + ".",
                ComandoDoHolograma.de(ComandoDoHolograma.VISTA, dados("vista", vista)), rapido);
    }

    private ResultadoFerramenta filtrar(String acao, String alvo, boolean rapido) {
        Alvo resolvido = resolver(alvo);
        if (resolvido.recusa() != null) return ResultadoFerramenta.texto(resolvido.recusa());
        String verbo = switch (acao) {
            case "destacar" -> "Destaquei";
            case "isolar" -> "Isolei";
            default -> "Escondi";
        };
        return enviar(verbo + " " + resolvido.rotulo() + ". Confirme em poucas palavras.",
                ComandoDoHolograma.de(acao, resolvido.dados()), rapido);
    }

    private ResultadoFerramenta selecionar(String alvo, boolean rapido) {
        Alvo resolvido = resolver(alvo);
        if (resolvido.recusa() != null) return ResultadoFerramenta.texto(resolvido.recusa());
        String peca = resolvido.primeiraPeca();
        return enviar("Selecionei " + resolvido.rotulo() + " (" + resolvido.grupo() + ").",
                ComandoDoHolograma.de(ComandoDoHolograma.SELECIONAR, dados("peca", peca)), rapido);
    }

    /** Não mexe na tela: devolve ao modelo o que ele precisa para explicar a peça com as próprias palavras. */
    private ResultadoFerramenta explicar(String alvo) {
        Optional<EstadoDoHolograma> retrato = estado.aberto();
        if (retrato.isEmpty()) return ResultadoFerramenta.texto(FECHADO);
        EstadoDoHolograma atual = retrato.get();
        String objeto = atual.nome().isBlank() ? "o objeto" : atual.nome();
        if (alvo.isEmpty()) {
            return atual.peca(atual.selecionada())
                    .map(p -> ResultadoFerramenta.texto("Peça '" + p.nome() + "', grupo " + p.grupo()
                            + ", do objeto '" + objeto + "'. Explique em duas ou três frases o que ela é e faz."))
                    .orElseGet(() -> ResultadoFerramenta.texto("Nenhuma peça selecionada. Pergunte qual."));
        }
        Alvo resolvido = resolver(alvo);
        if (resolvido.recusa() != null) return ResultadoFerramenta.texto(resolvido.recusa());
        long noGrupo = atual.pecas().stream().filter(p -> p.grupo().equals(resolvido.grupo())).count();
        return ResultadoFerramenta.texto((resolvido.ehGrupo() ? "Grupo '" : "Peça '") + resolvido.rotulo()
                + "', grupo " + resolvido.grupo() + " (" + noGrupo + " peças), do objeto '" + objeto
                + "'. Explique em duas ou três frases o que é e para que serve.");
    }

    /** O alvo resolvido, ou a frase de recusa que volta ao modelo. */
    private record Alvo(String recusa, String rotulo, String grupo, boolean ehGrupo, List<String> pecas,
            Map<String, Object> dados) {
        static Alvo recusado(String frase) {
            return new Alvo(frase, "", "", false, List.of(), Map.of());
        }
        String primeiraPeca() {
            return pecas.isEmpty() ? "" : pecas.get(0);
        }
    }

    private Alvo resolver(String alvo) {
        if (alvo.isEmpty()) return Alvo.recusado("Diga em 'alvo' o nome ou o grupo da peça.");
        Optional<EstadoDoHolograma> retrato = estado.aberto();
        List<EstadoDoHolograma.Peca> pecas = retrato.map(EstadoDoHolograma::pecas).orElse(List.of());
        if (pecas.isEmpty()) {
            return Alvo.recusado("Ainda não chegou nenhuma peça; tente quando a montagem avançar.");
        }
        return switch (ResolvedorDeAlvo.resolver(alvo, pecas)) {
            case ResolvedorDeAlvo.Grupo g -> {
                List<String> ids = pecas.stream().filter(p -> p.grupo().equals(g.grupo()))
                        .map(EstadoDoHolograma.Peca::id).toList();
                yield new Alvo(null, ResolvedorDeAlvo.rotuloDoGrupo(g.grupo()), g.grupo(), true, ids,
                        dados("grupos", List.of(g.grupo()), "pecas", List.of()));
            }
            case ResolvedorDeAlvo.Pecas p -> new Alvo(null, p.nome(), p.grupo(), false, p.ids(),
                    dados("grupos", List.of(), "pecas", p.ids()));
            case ResolvedorDeAlvo.Empate e -> Alvo.recusado("Mais de uma peça casa com '" + alvo + "': "
                    + String.join(", ", e.opcoes()) + ". Pergunte ao dono qual delas, sem mexer na tela.");
            case ResolvedorDeAlvo.Nenhum n -> retrato.get().gerando()
                    ? Alvo.recusado("Nada com o nome '" + alvo + "' chegou ainda; a montagem continua. "
                            + "Diga que vai aparecer e tente de novo depois.")
                    : Alvo.recusado("Não há peça '" + alvo + "' neste objeto. Grupos: "
                            + String.join(", ", retrato.get().grupos().keySet()) + ".");
        };
    }

    /** O objeto vai à galeria sem HUD; quem avisa que ficou pronto é o Telegram. */
    private ResultadoFerramenta gerarNaGaleria(JsonNode entrada) {
        String objeto = PromptDeHolograma.higienizar(Json.texto(entrada.path("objeto")));
        if (objeto.isEmpty()) return ResultadoFerramenta.texto("Diga em 'objeto' o que gerar.");
        Optional<DetalheHolograma> nivel = DetalheHolograma.porChave(Json.texto(entrada.path("detalhe")));
        if (nivel.isEmpty()) return ResultadoFerramenta.texto("O detalhe é rapido, normal ou detalhado.");
        Optional<GaleriaDeHologramas.HologramaSalvo> salvo = galeria.buscar(objeto, nivel.get());
        if (salvo.isPresent()) {
            return ResultadoFerramenta.texto("Já está na galeria: " + salvo.get().meta().nome() + " ("
                    + salvo.get().pecas().size() + " peças). Diga isso em uma frase.");
        }
        semTela.gerar(objeto, nivel.get(), fim -> {
            boolean entrou = fim != null && fim.completo() && fim.miniatura() != null;
            avisar.accept(entrou
                    ? frases.frase("holograma_na_galeria", Map.of("objeto", objeto, "pecas", String.valueOf(fim.pecas())))
                    : frases.frase("holograma_na_galeria_falhou", Map.of("objeto", objeto)));
        });
        return ResultadoFerramenta.texto("Comecei a montar " + objeto + " para a galeria; aviso quando "
                + "ficar pronto. Diga isso em uma frase.");
    }

    private String listaDaGaleria() {
        List<GaleriaDeHologramas.Item> itens = galeria.listar();
        if (itens.isEmpty()) return "A galeria está vazia.";
        StringBuilder lista = new StringBuilder("Na galeria: ");
        for (int i = 0; i < Math.min(itens.size(), MAX_LISTA_GALERIA); i++) {
            if (i > 0) lista.append("; ");
            lista.append(itens.get(i).nome()).append(" (").append(itens.get(i).pecas()).append(" peças)");
        }
        if (itens.size() > MAX_LISTA_GALERIA) lista.append(" e mais ").append(itens.size() - MAX_LISTA_GALERIA);
        return lista.append(". Para abrir um, chame carregar com o nome.").toString();
    }

    private ResultadoFerramenta enviar(String texto, ComandoDoHolograma comando, boolean rapido) {
        ComandoDoHolograma final_ = rapido ? comando.comoRapido() : comando;
        estado.anotarEnvio(final_);
        return ResultadoFerramenta.comHolograma(texto, final_);
    }

    /** "0,8", "0.8" e "80%" — o modelo escreve como ouviu. */
    static Optional<Double> numero(String texto) {
        String limpo = texto.replace("%", "").replace(',', '.').strip();
        try {
            double valor = Double.parseDouble(limpo);
            if (!Double.isFinite(valor)) return Optional.empty();
            return Optional.of(texto.contains("%") ? valor / 100 : valor);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static double limitar(double valor, double minimo, double maximo) {
        return Math.max(minimo, Math.min(maximo, valor));
    }

    private static Map<String, Object> dados(Object... pares) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) mapa.put((String) pares[i], pares[i + 1]);
        return mapa;
    }
}
