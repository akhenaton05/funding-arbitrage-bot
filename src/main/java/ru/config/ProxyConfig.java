package ru.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "proxy")
public class ProxyConfig {
    private boolean enabled;
    private String host;
    private int port;
    private String username;
    private String password;

    @Bean("createHttpClient")
    public CloseableHttpClient createHttpClient() {
        var clientBuilder = HttpClients.custom();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(30, TimeUnit.SECONDS))
                .setResponseTimeout(Timeout.of(60, TimeUnit.SECONDS))
                .build();
        clientBuilder.setDefaultRequestConfig(requestConfig);

        if (enabled) {
            log.info("Using proxy: {}:{}", host, port);
            HttpHost proxy = new HttpHost(host, port);
            clientBuilder.setProxy(proxy);

            if (username != null && !password.isEmpty()) {
                log.info("Using proxy authentication for user: {}", username);
                BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                        new AuthScope(host, port),
                        new UsernamePasswordCredentials(username, password.toCharArray())
                );
                clientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            }
        } else {
            log.info("Proxy disabled");
        }

        return clientBuilder.build();
    }
}
