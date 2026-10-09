package com.example.gateway.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties(OrdersApiProperties.class)
public class OrdersWebClientConfig {

    @Bean
    public WebClient ordersWebClient(WebClient.Builder builder, OrdersApiProperties properties) {
        HttpClient client = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.connectTimeout().toMillis()))
                .followRedirect(false)
                .disableRetry(true);
        return builder
                .clientConnector(new ReactorClientHttpConnector(client))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }
}
