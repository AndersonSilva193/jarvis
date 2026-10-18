package dev.jarvis.computer;

import java.util.List;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/** Cada adaptador preserva o protocolo e o estado nativo da sua API. */
public interface ProvedorComputerUse {
    record Chamada(String id, String nome, JsonNode entrada) { }
    record Passo(List<Chamada> chamadas, String texto, long tokensEntrada, long tokensSaida) {
        public boolean terminou() { return chamadas.isEmpty(); }
    }
    record Resultado(String id, byte[] imagem, String texto, boolean erro) { }
    String nome();
    String modelo();
    Passo iniciar(String objetivo, byte[] capturaInicial, ArrayNode sistema, Duration restante) throws Exception;
    Passo continuar(List<Resultado> resultados, Duration restante) throws Exception;
}
