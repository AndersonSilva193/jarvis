package dev.jarvis.ia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;

/**
 * Guarda o texto da personalidade (o bloco fixo do system) e o relê sozinho quando o arquivo muda,
 * para editar o jeito do Jarvis na VPS sem recompilar nem reiniciar o container.
 *
 * Se o arquivo não existir, não puder ser lido ou estiver em branco, devolve um texto mínimo de
 * reserva e avisa no log — o bot continua de pé, só mais sem graça.
 */
@Service
public class PersonalidadeService {

    private static final Logger log = LoggerFactory.getLogger(PersonalidadeService.class);

    private final Path arquivo;
    private final String reserva;

    private String texto;
    /** Data de modificação do que está em memória. Null enquanto estivermos na reserva. */
    private FileTime lidoEm;
    /** Para avisar só na transição para a reserva, e não a cada mensagem. */
    private boolean naReserva;

    /**
     * O @Autowired é obrigatório: como existe também o construtor de teste abaixo, a classe tem
     * dois construtores, e aí o Spring não escolhe sozinho — ele procura um construtor vazio,
     * não acha, e a aplicação nem sobe.
     */
    @Autowired
    public PersonalidadeService(JarvisProperties props) {
        this(props.arquivoDaPersonalidade(), props.nomeUsuario());
    }

    PersonalidadeService(Path arquivo, String nomeUsuario) {
        this.arquivo = arquivo;
        this.reserva = ("Você é o Jarvis, assistente pessoal de %s; responda em português do Brasil, "
                + "curto e direto").formatted(nomeUsuario == null || nomeUsuario.isBlank() ? "chefe" : nomeUsuario);
        this.texto = reserva;
    }

    /**
     * O texto da personalidade, relido se o arquivo tiver mudado desde a última vez.
     * É um {@code stat} por chamada ao Claude: barato perto do resto da requisição.
     */
    public synchronized String texto() {
        try {
            FileTime modificadoEm = Files.getLastModifiedTime(arquivo);
            if (modificadoEm.equals(lidoEm)) {
                return texto;
            }
            String lido = Files.readString(arquivo, StandardCharsets.UTF_8).strip();
            if (lido.isEmpty()) {
                return cairNaReserva("o arquivo de personalidade {} está vazio");
            }
            texto = lido;
            lidoEm = modificadoEm;
            naReserva = false;
            log.info("Personalidade carregada de {} ({} caracteres)", arquivo, texto.length());
            return texto;
        } catch (IOException | RuntimeException e) {
            return cairNaReserva("não consegui ler o arquivo de personalidade {}");
        }
    }

    /** Volta para o texto mínimo, avisando só na transição para não encher o log a cada mensagem. */
    private String cairNaReserva(String motivo) {
        if (!naReserva) {
            log.warn(motivo + "; usando a personalidade mínima de reserva", arquivo);
        }
        texto = reserva;
        lidoEm = null;
        naReserva = true;
        return texto;
    }

    /**
     * Estimativa grosseira de tokens, só para decidir se vale mandar o {@code cache_control}:
     * em português um token dá por volta de 3 caracteres. Errar para mais não custa nada — a API
     * simplesmente não cacheia um prefixo curto demais, sem cobrar nem reclamar.
     */
    public static int estimarTokens(String texto) {
        return texto == null ? 0 : texto.length() / 3;
    }
}
