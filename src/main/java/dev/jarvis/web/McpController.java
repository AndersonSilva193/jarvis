package dev.jarvis.web;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.jarvis.mcp.ServidorMcp;

/**
 * O endpoint MCP dos agentes do escritório, protegido pelo {@link TokenMcpFiltro}.
 *
 * <p>Transporte <i>Streamable HTTP</i>, só a parte que o Jarvis usa: POST com uma mensagem JSON-RPC
 * e resposta em {@code application/json}. GET e DELETE são 405 — o servidor não abre stream e não
 * guarda sessão, e o protocolo prevê exatamente essa resposta.
 *
 * <p>Requisição com {@code Origin} é recusada com 403. O protocolo manda conferir a origem, por
 * causa de páginas que tentam falar com servidores locais pelo navegador; quem chama aqui é o Hermes
 * Agent, que não é navegador e não manda esse cabeçalho, então qualquer origem é suspeita.
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    /** Uma mensagem JSON-RPC de ferramenta não chega perto disto. */
    static final int MAX_CORPO = 64 * 1024;

    private final ServidorMcp servidor;

    public McpController(ServidorMcp servidor) {
        this.servidor = servidor;
    }

    @PostMapping
    public ResponseEntity<String> mensagem(
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origem,
            @RequestBody(required = false) byte[] corpo) {
        if (origem != null && !origem.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (corpo != null && corpo.length > MAX_CORPO) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        String texto = corpo == null ? "" : new String(corpo, StandardCharsets.UTF_8);
        Optional<String> resposta = servidor.tratar(texto);
        if (resposta.isEmpty()) {
            // Notificação: o protocolo pede 202 sem corpo.
            return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(resposta.get());
    }

    @GetMapping
    public ResponseEntity<Void> semStream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST")
                .build();
    }

    @DeleteMapping
    public ResponseEntity<Void> semSessao() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST")
                .build();
    }
}
