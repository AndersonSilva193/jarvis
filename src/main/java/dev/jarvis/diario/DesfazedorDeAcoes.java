package dev.jarvis.diario;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.ferramentas.ResultadoFerramenta;
import dev.jarvis.ia.provedor.ModoDeIa;
import dev.jarvis.ia.provedor.SeletorDeModelo;
import dev.jarvis.memoria.MemoriaService;
import dev.jarvis.memoria.ProcedimentosService;
import dev.jarvis.pc.FluxoEdicaoPc;
import dev.jarvis.pc.LixeiraPcService;
import dev.jarvis.pc.OperacoesPcService;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.pc.TarefasPlanilhaService;
import dev.jarvis.util.Json;
import tools.jackson.databind.json.JsonMapper;

/** Único controle de posse e conclusão das reversões, inclusive dos atalhos especializados. */
@Service
public class DesfazedorDeAcoes {
    private final DiarioDeAcoes diario;
    private final FluxoEdicaoPc edicoes;
    private final LixeiraPcService lixeira;
    private final OperacoesPcService operacoes;
    private final MemoriaService memoria;
    private final ProcedimentosService procedimentos;
    private final SeletorDeModelo modelos;
    private final TarefasPlanilhaService tarefas;
    private final JsonMapper json;

    public DesfazedorDeAcoes(DiarioDeAcoes diario, FluxoEdicaoPc edicoes, LixeiraPcService lixeira,
            OperacoesPcService operacoes, MemoriaService memoria, ProcedimentosService procedimentos,
            SeletorDeModelo modelos, TarefasPlanilhaService tarefas, JsonMapper json) {
        this.diario = diario; this.edicoes = edicoes; this.lixeira = lixeira; this.operacoes = operacoes;
        this.memoria = memoria; this.procedimentos = procedimentos; this.modelos = modelos;
        this.tarefas = tarefas; this.json = json;
    }

    public Optional<String> porEdicao(long usuario, String editId) {
        return diario.porDesfazer(usuario, d -> d instanceof Desfazer.Edicao e && e.editId().equals(editId))
                .map(AcaoRegistrada::id);
    }

    public ResultadoFerramenta desfazer(String id, OuvinteProgresso contexto) {
        long usuario = contexto.usuarioAutenticado();
        if (usuario <= 0 || contexto.idPedido() == null)
            return ResultadoFerramenta.texto("Pedido autenticado necessário para desfazer.");
        Optional<AcaoRegistrada> encontrada = id == null || id.isBlank()
                ? diario.ultimaReversivel(usuario) : diario.localizar(id, usuario);
        if (encontrada.isEmpty()) return ResultadoFerramenta.texto(
                "Não encontrei uma ação reversível desse usuário" + (id == null || id.isBlank() ? " nas últimas 24 horas." : " com esse ID."));
        AcaoRegistrada acao = encontrada.get();
        if (!acao.reversivel()) return ResultadoFerramenta.texto("Aconteceu: " + acao.descricao()
                + ". Não tenho como desfazer esta ação pelo diário.");
        if ("desfeita".equals(acao.estadoDesfazer())) return ResultadoFerramenta.texto("Esta ação já foi desfeita. Não a executei de novo.");
        if ("em_andamento".equals(acao.estadoDesfazer())) return reconciliar(acao, contexto);
        boolean pc = acao.desfazer() instanceof Desfazer.Edicao || acao.desfazer() instanceof Desfazer.Lixeira
                || acao.desfazer() instanceof Desfazer.Arquivo;
        if (pc && !lixeira.online()) return ResultadoFerramenta.texto("O PC está offline; não iniciei o desfazer.");
        String comando = pc ? UUID.randomUUID().toString() : "";
        try { diario.mudarEstado(acao.id(), usuario, "disponivel", "em_andamento", comando); }
        catch (IOException e) { return ResultadoFerramenta.texto(e.getMessage()); }
        if (pc && contexto.contextoArquivo() != null) {
            contexto.contextoArquivo().operacaoPendente(comando);
            contexto.contextoArquivo().exigirReleitura();
        }
        try {
            String resposta = executar(acao, contexto, comando);
            concluir(acao, contexto, comando);
            liberarContexto(contexto, comando);
            return ResultadoFerramenta.texto(resposta);
        } catch (Recusa e) {
            try { diario.mudarEstado(acao.id(), usuario, "em_andamento", "disponivel", ""); }
            catch (IOException falha) { return ResultadoFerramenta.texto("Não executei; a reserva do desfazer permanece pendente no diário."); }
            liberarContexto(contexto, comando);
            return ResultadoFerramenta.texto(e.getMessage());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return ResultadoFerramenta.texto("Não consegui confirmar o desfazer. Mantive a ação reservada para não repetir "
                    + "um efeito incerto." + (comando.isEmpty() ? " Confira o estado antes de tentar outra reversão."
                            : " Consulte status_operacao_pc: " + comando + "."));
        }
    }

    private String executar(AcaoRegistrada acao, OuvinteProgresso contexto, String comando) throws Exception {
        return switch (acao.desfazer()) {
            case Desfazer.Edicao d -> {
                ResultadoPc preparo = edicoes.preparar("preparar_desfazer", json.createObjectNode().put("editId", d.editId()), contexto);
                if (!preparo.ok() || preparo.dados() == null) throw new Recusa("Não preparei o desfazer: " + preparo.mensagem());
                ResultadoPc r = lixeira.executar("aplicar_desfazer", json.createObjectNode().put("editId", d.editId()), contexto, comando);
                conferir(r); invalidar(contexto);
                yield "Voltei " + r.mensagem().replaceFirst("^salvo, entrega falhou: ", "") + " para a versão anterior.";
            }
            case Desfazer.Lixeira d -> {
                ResultadoPc r = lixeira.executar("restaurar_da_lixeira", json.createObjectNode().put("idLixeira", d.idLixeira()), contexto, comando);
                conferir(r);
                if (contexto.contextoArquivo() != null && r.dados() != null) contexto.contextoArquivo().registrar(r.dados(), false);
                invalidar(contexto);
                yield "Restaurei " + nome(r) + ".";
            }
            case Desfazer.Arquivo d -> {
                ResultadoPc r = lixeira.executar("mover_para_lixeira", json.createObjectNode().put("id", d.id()), contexto, comando);
                conferir(r); invalidar(contexto);
                yield "Movi " + nome(r) + " para a lixeira, desfazendo aquela ação.";
            }
            case Desfazer.Anotacao d -> {
                for (String id : d.ids()) {
                    var atual = memoria.ler(contexto.usuarioAutenticado(), id);
                    if (atual == null || atual.revisao() != 1) throw new Recusa("A anotação mudou ou já foi retirada; nada foi apagado.");
                }
                for (String id : d.ids()) memoria.desfazerEscrita(contexto.usuarioAutenticado(), id, 0);
                yield "Tirei a anotação. Não vou anotar esse assunto de novo.";
            }
            case Desfazer.Memoria d -> {
                var atual = memoria.ler(contexto.usuarioAutenticado(), d.id());
                if (atual == null || atual.revisao() != d.revisaoAnterior() + 1)
                    throw new Recusa("A memória mudou depois desta ação; nada foi desfeito.");
                memoria.desfazerEscrita(contexto.usuarioAutenticado(), d.id(), d.revisaoAnterior());
                yield "Voltei a memória para a versão anterior.";
            }
            case Desfazer.Procedimento d -> procedimentos.desfazer(contexto.usuarioAutenticado(), d.nome(), d.revisaoAnterior());
            case Desfazer.Modelo d -> {
                boolean posterior = diario.haPosterior(acao.id(), a -> a.tipo() == TipoDeAcao.TROCA_DE_MODELO
                        && !"desfeita".equals(a.estadoDesfazer()));
                if (posterior || !modelos.modo().chave().equals(d.atual()))
                    throw new Recusa("Houve outra troca de modelo; não alterei o modo atual.");
                modelos.restaurar(ModoDeIa.deChave(d.anterior()).orElseThrow(), contexto.canal());
                yield "Voltei ao modo de IA anterior.";
            }
            case Desfazer.Tarefa d -> {
                if (!tarefas.revogar(d.id(), contexto.usuarioAutenticado())) throw new Recusa("A tarefa já não está disponível para esse usuário.");
                yield "Revoguei o agendamento. Edições já executadas permanecem no diário para desfazer separadamente.";
            }
        };
    }

    private ResultadoFerramenta reconciliar(AcaoRegistrada acao, OuvinteProgresso contexto) {
        if (!acao.comandoDesfazer().isEmpty()) {
            var r = operacoes.resultado(acao.comandoDesfazer());
            if (r.isPresent() && r.get().ok()) {
                try { concluir(acao, contexto, acao.comandoDesfazer()); invalidar(contexto);
                    liberarContexto(contexto, acao.comandoDesfazer());
                    return ResultadoFerramenta.texto("O PC confirmou o desfazer. Registrei a conclusão; não executei de novo.");
                } catch (IOException e) { return ResultadoFerramenta.texto("O desfazer foi confirmado, mas a conclusão ainda não pôde ser persistida."); }
            }
            if (r.isPresent() && !incerto(r.get())) {
                try { diario.mudarEstado(acao.id(), contexto.usuarioAutenticado(), "em_andamento", "disponivel", ""); }
                catch (IOException e) { return ResultadoFerramenta.texto("A reserva ainda está pendente no diário."); }
                liberarContexto(contexto, acao.comandoDesfazer());
                return ResultadoFerramenta.texto("O PC recusou o desfazer: " + r.get().mensagem() + ". Não tentei de novo.");
            }
        }
        return ResultadoFerramenta.texto("O desfazer desta ação já está em andamento ou tem resultado incerto. Não executei de novo.");
    }

    private void concluir(AcaoRegistrada acao, OuvinteProgresso contexto, String comando) throws IOException {
        diario.concluirDesfazer(acao, contexto.canal(), comando);
    }
    private static boolean incerto(ResultadoPc r) {
        return r.dados() != null && "RESULTADO_INCERTO".equals(Json.texto(r.dados().path("codigoErro")));
    }
    private static void conferir(ResultadoPc r) throws IOException {
        if (!r.ok()) { if (incerto(r)) throw new IOException("Resultado incerto"); throw new Recusa("Não desfiz: " + r.mensagem()); }
    }
    private static String nome(ResultadoPc r) { return r.dados() == null ? r.mensagem() : Json.texto(r.dados().path("nome")); }
    private static void invalidar(OuvinteProgresso contexto) { if (contexto.contextoArquivo() != null) contexto.contextoArquivo().exigirReleitura(); }
    private static void liberarContexto(OuvinteProgresso contexto, String comando) {
        if (!comando.isEmpty() && contexto.contextoArquivo() != null) contexto.contextoArquivo().operacaoTerminou(comando);
    }
    private static class Recusa extends IOException { Recusa(String mensagem) { super(mensagem); } }
}
