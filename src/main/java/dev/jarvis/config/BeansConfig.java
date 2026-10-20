package dev.jarvis.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import dev.jarvis.conversa.HistoricoEmArquivo;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class BeansConfig {

    @Bean
    public JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * A pasta onde o histórico da conversa sobrevive ao restart do container.
     *
     * Fica aqui, e não num {@code @Component} em {@code dev.jarvis.conversa}, porque o caminho e a
     * validade vêm da configuração: a classe em si não conhece o {@link JarvisProperties}, e é isso
     * que permite aos testes construí-la apontando para uma pasta temporária.
     */
    @Bean
    public HistoricoEmArquivo historicoEmArquivo(JarvisProperties config, JsonMapper json) {
        return new HistoricoEmArquivo(config.pastaDoHistorico(), config.validadeDoHistorico(), json);
    }

    /**
     * O cliente HTTP do Java compartilhado por Telegram, Claude e ferramentas.
     *
     * É o padrão: quem pede um HttpClient sem qualificar recebe este.
     */
    @Bean
    @Primary
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * O mesmo cliente, mas sem seguir redirecionamento sozinho. Existe só para o
     * {@code PaginaService}, e por exigência de segurança: o {@code abrir_pagina} busca um
     * endereço ditado pelo modelo, e cada salto de {@code Location:} precisa ser revalidado contra
     * a trava de SSRF antes de ser seguido. Com {@code Redirect.NORMAL} o cliente já teria
     * conectado no destino novo antes de qualquer checagem — que é exatamente o buraco.
     *
     * Também não tem CookieHandler, como o de cima: nada de cookie sai para a página buscada.
     */
    @Bean("httpSemRedirecionar")
    public HttpClient httpSemRedirecionar() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
