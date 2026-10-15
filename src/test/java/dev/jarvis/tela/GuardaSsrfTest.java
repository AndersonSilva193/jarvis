package dev.jarvis.tela;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.tela.GuardaSsrf.DestinoRecusadoException;

/**
 * Testa a trava de SSRF. Sem rede: todos os casos usam literal de IP ou "localhost", que resolvem
 * sem sair da máquina — é a razão de a validação ser uma função separada do download.
 */
class GuardaSsrfTest {

    private static void recusa(String url) {
        assertThrows(DestinoRecusadoException.class,
                () -> GuardaSsrf.exigirPublico(GuardaSsrf.validarForma(url)), url);
    }

    @Test
    @DisplayName("endereço de loopback é recusado")
    void loopback() {
        recusa("http://localhost/");
        recusa("http://localhost:80/admin");
        recusa("http://127.0.0.1/");
        recusa("http://127.1.2.3/");
        recusa("http://[::1]/");
    }

    @Test
    @DisplayName("faixas privadas são recusadas")
    void faixasPrivadas() {
        recusa("http://10.0.0.5/");
        recusa("http://192.168.1.1/");
        recusa("http://172.16.0.1/");
        recusa("http://172.31.255.254/");
    }

    @Test
    @DisplayName("o metadado de nuvem e o endereço indefinido são recusados")
    void linkLocalEIndefinido() {
        recusa("http://169.254.169.254/latest/meta-data/");
        recusa("http://0.0.0.0/");
        recusa("http://[fd00::1]/");
    }

    @Test
    @DisplayName("IPv4 escrito como IPv6 não escapa da checagem")
    void ipv4Mapeado() {
        recusa("http://[::ffff:10.0.0.5]/");
        recusa("http://[::ffff:127.0.0.1]/");
    }

    @Test
    @DisplayName("CGNAT e faixa de benchmark também ficam fora")
    void faixasMenosObvias() {
        recusa("http://100.64.0.1/");
        recusa("http://198.18.0.1/");
    }

    @Test
    @DisplayName("só http e https, sem credencial e só nas portas padrão")
    void formaDoEndereco() {
        assertThrows(DestinoRecusadoException.class, () -> GuardaSsrf.validarForma("ftp://exemplo.com/"));
        assertThrows(DestinoRecusadoException.class, () -> GuardaSsrf.validarForma("file:///etc/passwd"));
        assertThrows(DestinoRecusadoException.class, () -> GuardaSsrf.validarForma("javascript:alert(1)"));
        assertThrows(DestinoRecusadoException.class,
                () -> GuardaSsrf.validarForma("http://usuario:senha@exemplo.com/"));
        assertThrows(DestinoRecusadoException.class,
                () -> GuardaSsrf.validarForma("http://exemplo.com:22/"),
                "porta livre transformaria isto num scanner de portas da rede interna");
        assertThrows(DestinoRecusadoException.class, () -> GuardaSsrf.validarForma("   "));
        assertThrows(DestinoRecusadoException.class, () -> GuardaSsrf.validarForma(null));

        assertEquals("https://exemplo.com/pagina",
                GuardaSsrf.validarForma("https://exemplo.com/pagina").toString());
        assertEquals("http://exemplo.com:80/",
                GuardaSsrf.validarForma("http://exemplo.com:80/").toString());
    }

    @Test
    @DisplayName("redirecionamento para IP privado é barrado antes de ser seguido")
    void redirecionamentoParaRedeInterna() {
        URI atual = URI.create("https://8.8.8.8/a");

        assertThrows(DestinoRecusadoException.class,
                () -> GuardaSsrf.proximoSalto(atual, "http://10.0.0.5/"));
        assertThrows(DestinoRecusadoException.class,
                () -> GuardaSsrf.proximoSalto(atual, "http://169.254.169.254/latest/meta-data/"),
                "é o salto que o atacante usa: domínio público redirecionando para o metadado");
        assertThrows(DestinoRecusadoException.class,
                () -> GuardaSsrf.proximoSalto(atual, "file:///etc/passwd"));
        assertThrows(DestinoRecusadoException.class, () -> GuardaSsrf.proximoSalto(atual, ""));
        assertThrows(DestinoRecusadoException.class, () -> GuardaSsrf.proximoSalto(atual, null));
    }

    @Test
    @DisplayName("Location relativo é resolvido contra a URL atual e revalidado")
    void redirecionamentoRelativo() {
        // Literal de IP público, e não um domínio: assim o caso vale sem resolver nome nenhum.
        URI atual = URI.create("https://8.8.8.8/pasta/pagina.html");

        assertEquals("https://8.8.8.8/pasta/outra.html",
                GuardaSsrf.proximoSalto(atual, "outra.html").toString());
        assertEquals("https://8.8.8.8/raiz",
                GuardaSsrf.proximoSalto(atual, "/raiz").toString());
    }

    @Test
    @DisplayName("endereço público passa")
    void publicoPassa() {
        assertEquals("http://8.8.8.8/",
                GuardaSsrf.exigirPublico(GuardaSsrf.validarForma("http://8.8.8.8/")).toString());
        assertEquals("https://1.1.1.1/pagina",
                GuardaSsrf.exigirPublico(GuardaSsrf.validarForma("https://1.1.1.1/pagina")).toString());
    }

    @Test
    @DisplayName("host que não resolve é recusado, não vira exceção de rede")
    void hostQueNaoResolve() {
        recusa("http://nao-existe-mesmo-" + System.nanoTime() + ".invalid/");
    }
}
