package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.tela.CardTela;
import dev.jarvis.tela.DadosDoCard;
import dev.jarvis.tela.GuardaSsrf;
import dev.jarvis.tela.PaginaService;
import dev.jarvis.tela.PresencaDaHud;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Abre uma página da web na HUD, com a prévia montada pelo servidor.
 *
 * O endereço vem do modelo, então quem faz a requisição é a VPS com a trava de SSRF ligada: só
 * http e https, nada de rede interna, no máximo 3 redirecionamentos revalidados, 8 s e 1 MB.
 */
@Component
public class AbrirPaginaFerramenta extends FerramentaDeTela {

    private final PaginaService paginas;

    public AbrirPaginaFerramenta(PresencaDaHud hud, JsonMapper json, PaginaService paginas) {
        super(hud, json);
        this.paginas = paginas;
    }

    @Override
    public String nome() {
        return "abrir_pagina";
    }

    @Override
    public String descricao() {
        return "Abre uma página da web na tela do Jarvis, com título, descrição e imagem de prévia. "
                + "Use quando o usuário pede para ver uma página específica, ou quando o link de um "
                + "resultado de busca é o que ele quer olhar. Só endereços públicos http ou https: "
                + "endereço de rede interna é recusado. Algumas páginas não permitem ser exibidas "
                + "dentro de outra; nesse caso a tela mostra a prévia com o link.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        esquema.putObject("properties").putObject("url")
                .put("type", "string")
                .put("description", "O endereço completo da página, começando com http:// ou https://.");
        esquema.putArray("required").add("url");
        return esquema;
    }

    @Override
    public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso)
            throws Exception {
        if (telaFechada()) {
            return ResultadoFerramenta.texto(SEM_TELA);
        }
        String url = Json.texto(entrada.path("url")).trim();
        PaginaService.Previa previa;
        try {
            previa = paginas.baixar(url);
        } catch (GuardaSsrf.DestinoRecusadoException e) {
            // Texto, não exceção: assim o modelo explica ao usuário em vez de tentar de novo.
            return ResultadoFerramenta.texto("Não vou abrir esse endereço: " + e.getMessage() + ".");
        } catch (java.io.IOException e) {
            return ResultadoFerramenta.texto("Não consegui carregar essa página (" + e.getMessage() + ").");
        }

        CardTela card = CardTela.agora(CardTela.PAGINA, previa.titulo(),
                new DadosDoCard.Pagina(previa.url(), previa.titulo(), previa.descricao(),
                        previa.imagem(), previa.site(), previa.incorporavel()),
                origem(progresso));
        String aviso = previa.incorporavel()
                ? "Página na tela (id " + card.id() + ")."
                : "Página na tela (id " + card.id() + "), mas ela não permite ser exibida por dentro: "
                        + "a tela mostra só a prévia com o link.";
        return ResultadoFerramenta.comCard(aviso + " Diga só uma frase curta.", card);
    }
}
