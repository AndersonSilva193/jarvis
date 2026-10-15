package dev.jarvis.tela;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trava de SSRF para o {@code abrir_pagina}: é a VPS que vai buscar um endereço ditado pelo
 * modelo, então o endereço precisa ser provadamente público antes de qualquer conexão.
 *
 * <p>O que barramos: esquema que não seja http/https, credencial embutida na URL, porta fora de
 * 80/443, e todo endereço que não seja roteável na internet — loopback, privado (10/8, 172.16/12,
 * 192.168/16), link-local (incluindo o <b>169.254.169.254</b> dos metadados de nuvem), CGNAT,
 * multicast e os endereços das próprias interfaces desta máquina.
 *
 * <p><b>Risco residual, dito às claras:</b> o {@code HttpClient} do Java não tem gancho de
 * resolução de nome, então entre a nossa checagem e a conexão dele existe uma janela de
 * <i>DNS rebinding</i> — um domínio hostil pode devolver IP público aqui e 127.0.0.1 lá.
 * Fechar isso exigiria fixar o IP no socket (o que quebraria o SNI e a validação do certificado)
 * ou uma dependência nova. O que fazemos é reduzir o dano: nenhum cookie e nenhum cabeçalho de
 * autenticação saem daqui, o corpo é lido com teto de 1 MB e 8 s, cada salto de redirecionamento
 * é revalidado, e da página só voltam quatro metadados {@code og:} — nada do HTML cru chega ao
 * modelo nem à HUD.
 *
 * <p>O IP público da VPS não é enumerável de dentro do container (o Docker faz NAT, e as
 * interfaces mostram só o IP interno e a bridge). Alcançá-lo cairia no nginx, que serve apenas a
 * HUD e exige token, então o que importa está coberto pelas faixas privadas e locais.
 */
public final class GuardaSsrf {

    private static final Logger log = LoggerFactory.getLogger(GuardaSsrf.class);

    /** Teto de saltos de um Location:. Cada salto é revalidado antes de ser seguido. */
    public static final int MAX_REDIRECIONAMENTOS = 3;

    private static final int LIMITE_URL = 2048;

    /** Endereços desta máquina, descobertos uma vez: a lista de interfaces não muda em runtime. */
    private static volatile Set<InetAddress> locais;

    private GuardaSsrf() {
    }

    /** Destino recusado, com o motivo em texto curto para a ferramenta devolver ao modelo. */
    public static class DestinoRecusadoException extends IllegalArgumentException {
        public DestinoRecusadoException(String motivo) {
            super(motivo);
        }
    }

    /**
     * A forma da URL, sem tocar na rede: só http/https, host presente, sem credencial embutida,
     * porta só a padrão do esquema e sem zona de IPv6.
     */
    public static URI validarForma(String url) {
        if (url == null || url.isBlank()) {
            throw new DestinoRecusadoException("endereço vazio");
        }
        String limpo = url.trim();
        if (limpo.length() > LIMITE_URL) {
            throw new DestinoRecusadoException("endereço longo demais");
        }
        URI destino;
        try {
            destino = new URI(limpo);
        } catch (URISyntaxException e) {
            throw new DestinoRecusadoException("endereço inválido");
        }
        String esquema = destino.getScheme() == null ? "" : destino.getScheme().toLowerCase();
        if (!esquema.equals("http") && !esquema.equals("https")) {
            throw new DestinoRecusadoException("só abro endereços http ou https");
        }
        if (destino.getHost() == null || destino.getHost().isBlank()) {
            throw new DestinoRecusadoException("endereço sem host");
        }
        // Credencial na URL vira cabeçalho de autenticação: nunca mandamos nenhum.
        if (destino.getUserInfo() != null || destino.getAuthority().indexOf('@') >= 0) {
            throw new DestinoRecusadoException("não abro endereço com usuário e senha embutidos");
        }
        if (destino.getHost().indexOf('%') >= 0) {
            throw new DestinoRecusadoException("endereço com zona de interface não vale");
        }
        int porta = destino.getPort();
        int padrao = esquema.equals("https") ? 443 : 80;
        if (porta != -1 && porta != padrao) {
            throw new DestinoRecusadoException("só abro as portas padrão (80 e 443)");
        }
        return destino;
    }

    /**
     * Resolve o nome e exige que <b>todos</b> os endereços sejam públicos. Todos, e não o
     * primeiro: um host que devolve um IP público e um privado serve justamente para escolher o
     * privado na hora da conexão.
     */
    public static URI exigirPublico(URI destino) {
        InetAddress[] enderecos;
        try {
            enderecos = InetAddress.getAllByName(destino.getHost());
        } catch (UnknownHostException e) {
            throw new DestinoRecusadoException("não consegui resolver o endereço");
        }
        if (enderecos.length == 0) {
            throw new DestinoRecusadoException("não consegui resolver o endereço");
        }
        for (InetAddress ip : enderecos) {
            if (privado(ip)) {
                log.warn("Destino recusado por SSRF: {} resolve para endereço não público",
                        destino.getHost());
                throw new DestinoRecusadoException("esse endereço aponta para a rede interna");
            }
        }
        return destino;
    }

    /**
     * O próximo salto de um {@code Location:}, resolvido contra a URL atual (o cabeçalho pode vir
     * relativo) e revalidado inteiro — forma e endereço.
     */
    public static URI proximoSalto(URI atual, String location) {
        if (location == null || location.isBlank()) {
            throw new DestinoRecusadoException("redirecionamento sem destino");
        }
        URI absoluto;
        try {
            absoluto = atual.resolve(location.trim());
        } catch (RuntimeException e) {
            throw new DestinoRecusadoException("redirecionamento inválido");
        }
        return exigirPublico(validarForma(absoluto.toString()));
    }

    /** Se o endereço não é roteável na internet — ou é desta própria máquina. */
    static boolean privado(InetAddress ip) {
        InetAddress alvo = desembrulhar(ip);
        if (alvo.isLoopbackAddress() || alvo.isAnyLocalAddress() || alvo.isLinkLocalAddress()
                || alvo.isSiteLocalAddress() || alvo.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = alvo.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xFF;
            int b = bytes[1] & 0xFF;
            // Faixas que o java.net não classifica: CGNAT, atribuições do IETF e benchmark.
            if (a == 100 && b >= 64 && b <= 127) {
                return true;
            }
            if (a == 192 && b == 0 && (bytes[2] & 0xFF) == 0) {
                return true;
            }
            if (a == 198 && (b == 18 || b == 19)) {
                return true;
            }
            if (a == 0 || a == 127 || a >= 240) {
                return true;
            }
        } else {
            int primeiro = bytes[0] & 0xFF;
            int segundo = bytes[1] & 0xFF;
            // fc00::/7 (unique local), 2002::/16 (6to4) e 2001::/32 (Teredo) tunelam para IPv4.
            if ((primeiro & 0xFE) == 0xFC) {
                return true;
            }
            if (primeiro == 0x20 && segundo == 0x02) {
                return true;
            }
            if (primeiro == 0x20 && segundo == 0x01
                    && (bytes[2] & 0xFF) == 0 && (bytes[3] & 0xFF) == 0) {
                return true;
            }
        }
        return enderecosLocais().contains(alvo);
    }

    /** O IPv4 escrito como IPv6 ("::ffff:10.0.0.1") é o mesmo endereço; comparamos a forma IPv4. */
    private static InetAddress desembrulhar(InetAddress ip) {
        if (!(ip instanceof Inet6Address seis)) {
            return ip;
        }
        byte[] bytes = seis.getAddress();
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return ip;
            }
        }
        if ((bytes[10] & 0xFF) != 0xFF || (bytes[11] & 0xFF) != 0xFF) {
            return ip;
        }
        try {
            return InetAddress.getByAddress(new byte[] { bytes[12], bytes[13], bytes[14], bytes[15] });
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    /** Os endereços das interfaces desta máquina, para o alvo não ser a própria VPS. */
    static Set<InetAddress> enderecosLocais() {
        Set<InetAddress> guardados = locais;
        if (guardados != null) {
            return guardados;
        }
        Set<InetAddress> encontrados = new HashSet<>();
        try {
            for (NetworkInterface placa : NetworkInterface.networkInterfaces().toList()) {
                placa.inetAddresses().forEach(encontrados::add);
            }
        } catch (SocketException e) {
            // Sem a lista, as faixas privadas acima já cobrem o caminho que importa.
            log.debug("Não consegui listar as interfaces de rede: {}", e.getMessage());
        }
        locais = Set.copyOf(encontrados);
        return locais;
    }
}
