package ru.client.lighter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import ru.client.ExchangeClient;

@Slf4j
@Setter
@Component
@ConfigurationProperties(prefix = "exchanges.lighter")
public class LighterClient implements ExchangeClient {

    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;
    private String apiKey;
    private String baseUrl;

    public LighterClient(CloseableHttpClient httpClient) {
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
    }

    @Override
    public Double getBalance() {
        return 0.0;
    }
}
