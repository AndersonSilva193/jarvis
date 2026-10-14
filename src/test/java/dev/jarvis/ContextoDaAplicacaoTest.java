package dev.jarvis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import dev.jarvis.agenda.ResumoDiario;
import dev.jarvis.ferramentas.Ferramentas;
import dev.jarvis.ia.Assistente;
import dev.jarvis.ia.PersonalidadeService;
import dev.jarvis.telegram.TelegramPoller;

/**
 * O único teste que levanta o contexto do Spring. Ele existe porque erro de fiação de bean
 * (construtor ambíguo, dependência faltando, ciclo) não aparece em teste de função pura: o
 * `mvn package` passa e o container só quebra na partida, já em produção.
 *
 * Nada de rede aqui: as credenciais são de mentira, o cron do resumo vem desligado ("-") e o
 * poller do Telegram é trocado por um dublê.
 */
@SpringBootTest(properties = {
        // As duas únicas configurações sem default no application.yml.
        "jarvis.telegram.token=teste",
        "jarvis.anthropic.api-key=teste",
        // "-" é como o Spring desliga um @Scheduled por configuração.
        "jarvis.resumo-diario.cron=-",
})
class ContextoDaAplicacaoTest {

    /**
     * O poller de verdade abre uma thread não-daemon que fica chamando a API do Telegram. O dublê
     * não sobe nada: o contexto fecha limpo e a suíte não depende da rede.
     */
    @MockitoBean
    TelegramPoller telegramPoller;

    @Autowired
    PersonalidadeService personalidade;
    @Autowired
    Assistente assistente;
    @Autowired
    ResumoDiario resumoDiario;
    @Autowired
    Ferramentas ferramentas;

    @Test
    @DisplayName("o contexto sobe com todos os beans ligados")
    void contextoSobe() {
        assertNotNull(personalidade);
        assertNotNull(assistente);
        assertNotNull(resumoDiario);
    }

    @Test
    @DisplayName("as ferramentas são descobertas sozinhas pelo Spring")
    void ferramentasRegistradas() {
        // Ferramenta nova é só uma classe @Component: registro vazio significa injeção quebrada.
        assertFalse(ferramentas.definicoes().isEmpty(), "o registro de ferramentas subiu vazio");
    }
}
