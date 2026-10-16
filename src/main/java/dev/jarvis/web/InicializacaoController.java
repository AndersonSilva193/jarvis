package dev.jarvis.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.jarvis.inicializacao.InicializacaoService;

/**
 * A apresentação falada que a HUD pede no instante em que a tela acende.
 *
 * Chega aqui quando o agente do PC abre a tela com {@code ?inicio=1} — duas palmas ou
 * "Jarvis, iniciar" na frente do computador.
 *
 * POST, e não GET, porque a chamada tem efeito: pode gastar uma ida ao Claude. O cache de dois
 * minutos vive no serviço, para palmas repetidas não virarem duas chamadas.
 *
 * O token da HUD é exigido pelo {@link TokenHudFiltro}, que já protege tudo sob {@code /api} que
 * não seja {@code /api/saude} nem rota do PC — nada a configurar aqui.
 */
@RestController
@RequestMapping("/api/inicializacao")
public class InicializacaoController {

    private final InicializacaoService inicializacao;

    public InicializacaoController(InicializacaoService inicializacao) {
        this.inicializacao = inicializacao;
    }

    @PostMapping
    public InicializacaoDto abrir() {
        return InicializacaoDto.de(inicializacao.gerar());
    }
}
