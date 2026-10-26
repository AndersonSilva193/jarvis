package dev.jarvis.proativo;

import java.util.ArrayList;
import java.util.List;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.telegram.TelegramClient;

/** Guarda as mensagens em vez de mandar: é o que os testes conferem no lugar da rede. */
class TelegramFalso extends TelegramClient {

    final List<String> mensagens = new ArrayList<>();

    TelegramFalso(JarvisProperties props) {
        super(null, null, props);
    }

    @Override
    public void enviarMensagem(long chatId, String texto) {
        mensagens.add(texto);
    }

    String ultima() {
        return mensagens.isEmpty() ? "" : mensagens.get(mensagens.size() - 1);
    }
}
