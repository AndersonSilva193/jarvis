package dev.jarvis.pc;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import dev.jarvis.conversa.Anexo;
import dev.jarvis.conversa.AnexosService;
import dev.jarvis.conversa.RegistroConversas;
import dev.jarvis.ferramentas.ResultadoFerramenta.Artefato;
import dev.jarvis.telegram.TelegramClient;

/** Marca a tentativa antes do envio; a consulta por ID é a fonte de verdade após uma queda. */
@Component
public class AvisosOperacoesPc {
    private final OperacoesPcService operacoes;
    private final TelegramClient telegram;
    private final RegistroConversas conversas;
    private final ArquivosPcService arquivos;
    private final AnexosService anexos;
    public AvisosOperacoesPc(OperacoesPcService operacoes, TelegramClient telegram,
            RegistroConversas conversas, ArquivosPcService arquivos, AnexosService anexos) {
        this.operacoes = operacoes; this.telegram = telegram;
        this.conversas = conversas; this.arquivos = arquivos; this.anexos = anexos;
    }
    @Scheduled(fixedDelay = 2000)
    public void avisar() {
        for (String id : operacoes.avisosPendentes()) {
            var estado = operacoes.estado(id).orElse(null);
            if (estado == null || !operacoes.marcarAviso(id)) continue;
            var resultado = operacoes.resultado(id).orElse(null);
            String texto = textoDoAviso(id, estado, resultado);
            String canal = operacoes.canal(id);
            long chatId = operacoes.chatId(id);
            List<Anexo> referencias = new ArrayList<>();
            boolean telegramAvisado = false;
            boolean entregaFalhou = false;
            boolean edicao = List.of("aplicar_edicao", "aplicar_desfazer").contains(estado.acao());
            if (resultado != null && resultado.temImagem()) {
                try {
                    var imagem = operacoes.arquivoDeImagem(id);
                    if (Files.isRegularFile(imagem)) referencias.add(anexos.registrar(new Artefato(
                            imagem, "tela-do-pc" + ("image/jpeg".equals(resultado.mime()) ? ".jpg" : ".png"),
                            resultado.mime())));
                    if ("telegram".equals(canal) && chatId > 0) {
                        telegram.enviarFoto(chatId, resultado.imagem(), "tela-do-pc.png", texto);
                        telegramAvisado = true;
                    }
                } catch (Exception ignorada) { /* A tentativa já está registrada; status segue consultável. */ }
            }
            if (resultado != null && resultado.arquivo()) {
                try {
                    var arquivo = arquivos.obter(id).orElse(null);
                    if (arquivo != null) {
                        String nome = resultado.mensagem();
                        referencias.add(anexos.registrar(new Artefato(arquivo, nome,
                                "application/octet-stream")));
                        if ("telegram".equals(canal) && chatId > 0) {
                            telegram.enviarDocumento(chatId, Files.readAllBytes(arquivo), nome);
                            telegramAvisado = true;
                        }
                    } else entregaFalhou = true;
                } catch (Exception ignorada) { entregaFalhou = true; }
            }
            if (edicao && resultado != null && resultado.ok() && (!resultado.arquivo() || entregaFalhou)
                    && !texto.contains("salvo, entrega falhou")) texto += " salvo, entrega falhou.";
            if ("telegram".equals(canal) && chatId > 0 && (!telegramAvisado || edicao))
                try { telegram.enviarMensagem(chatId, texto); }
                catch (Exception ignorada) { /* O estado segue consultável pelo ID. */ }
            if ("hud".equals(canal) && chatId > 0)
                conversas.registrarResultadoTardio(chatId, id, texto, referencias);
        }
    }

    static String textoDoAviso(String id, OperacoesPcService.Retrato estado, ResultadoPc resultado) {
        boolean edicao = List.of("aplicar_edicao", "aplicar_desfazer").contains(estado.acao());
        if (edicao) {
            if (resultado != null && resultado.ok()) {
                String acao = "aplicar_desfazer".equals(estado.acao())
                        ? "Edição desfeita" : "Arquivo atualizado";
                return acao + ": " + resultado.mensagem() + ".";
            }
            return "Edição do arquivo " + estado.estado() + ": " + estado.mensagem();
        }
        String texto = "Operação do PC " + id + ": " + estado.estado() + ". " + estado.mensagem();
        if (resultado != null && resultado.dados() != null) texto += "\n" + resultado.dados();
        return texto;
    }
}
