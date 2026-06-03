package com.example.omniseek.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class ChromaConfig {

    @Value("${chroma.url:http://localhost:8000}")
    private String chromaUrl;

    @Value("${chroma.api-key:}")
    private String chromaApiKey;

    @Value("${chroma.timeout.connect:30}")
    private int connectTimeout;

    @Value("${chroma.timeout.read:120}")
    private int readTimeout;

    @Value("${chroma.timeout.write:120}")
    private int writeTimeout;

    @Bean
    public WebClient chromaWebClient() {
        // 针对性能较弱的设备（如 J1900）增加超时时间
        ConnectionProvider provider = ConnectionProvider.builder("chroma")
                .maxConnections(5)
                .pendingAcquireTimeout(Duration.ofSeconds(20))
                .build();
        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout * 1000)
                .responseTimeout(Duration.ofSeconds(readTimeout))
                .doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(readTimeout, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(writeTimeout, TimeUnit.SECONDS)));

        WebClient.Builder builder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(chromaUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        if (chromaApiKey != null && !chromaApiKey.isEmpty()) {
            builder.defaultHeader("Authorization", "Bearer " + chromaApiKey);
        }

        return builder.build();
    }
}
