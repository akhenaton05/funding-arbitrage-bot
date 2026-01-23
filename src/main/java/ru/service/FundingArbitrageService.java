package ru.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import ru.dto.funding.ArbitrageRates;
import ru.event.FundingAlertEvent;
import ru.utils.FundingArbitrageContext;

import javax.xml.bind.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@Slf4j
public class FundingArbitrageService {

    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;
    private final FundingArbitrageContext fundingContext;
    private final ApplicationEventPublisher eventPublisher;
    private static final String API_URL = "https://api.loris.tools/funding";

    public FundingArbitrageService(CloseableHttpClient httpClient,
                                   FundingArbitrageContext fundingContext,
                                   ApplicationEventPublisher eventPublisher) {
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
        this.fundingContext = fundingContext;
        this.eventPublisher = eventPublisher;
    }

    public Map<String, Map<String, Object>> getFundingRates() {
        try {
            HttpGet httpGet = new HttpGet(API_URL);
            return executeRequest(httpGet);
        } catch (Exception e) {
            log.error("Failed to get funding rates", e);
            throw new RuntimeException(e);
        }
    }

    public List<ArbitrageRates> calculateArbitrageRates() {
        Map<String, Map<String, Object>> fundingRates = getFundingRates();

        Map<String, Object> extended = fundingRates.get("extended");
        Map<String, Object> variational = fundingRates.get("variational");

        if (Objects.isNull(extended) || Objects.isNull(variational)) {
            log.error("Required exchanges not found");
            return Collections.emptyList();
        }

        List<ArbitrageRates> arbitrageRates = new ArrayList<>();

        for (Map.Entry<String, Object> entry : extended.entrySet()) {
            String symbol = entry.getKey();

            if (variational.containsKey(symbol)) {
                double extendedRate = ((Number) entry.getValue()).doubleValue();
                double variationalRate = ((Number) variational.get(symbol)).doubleValue();
                double arbitrage = Math.abs(extendedRate - variationalRate);

                String action = extendedRate < variationalRate ?
                        "BUY extended, SELL variational" :
                        "SELL extended, BUY variational";

                arbitrageRates.add(ArbitrageRates.builder()
                        .symbol(symbol)
                        .arbitrageRate(arbitrage)
                        .extendedRate(extendedRate)
                        .variationalRate(variationalRate)
                        .action(action)
                        .build());
            }
        }

        // Сортируем по убыванию арбитража
        arbitrageRates.sort(Comparator.comparingDouble(ArbitrageRates::getArbitrageRate).reversed());

        return arbitrageRates;
    }

    // Scheduled метод - только для автоматических алертов
    @Scheduled(cron = "0 53 * * * *") // каждый час в 53 минуты
    private void fundingTracker() {
        try {
            List<ArbitrageRates> arbitrageRates = calculateArbitrageRates();

            if (arbitrageRates.isEmpty()) {
                log.warn("No arbitrage rates calculated");
                return;
            }

            ArbitrageRates topRate = arbitrageRates.getFirst();

            if (topRate.getArbitrageRate() > 100) {
                log.info("High arbitrage detected: {} - {}%", topRate.getSymbol(), topRate.getArbitrageRate());

                for (Long chatId : fundingContext.getSubscriberIds()) {
                    eventPublisher.publishEvent(
                            new FundingAlertEvent(chatId, formatAlert(topRate))
                    );
                }
            }
        } catch (Exception e) {
            log.error("Error in funding tracker", e);
        }
    }

    private String formatAlert(ArbitrageRates rate) {
        return String.format("🚨 *High Arbitrage Alert* 🚨\n\n" +
                        "*Symbol:* %s\n" +
                        "*Max Arb:* %.2f%%\n" +
                        "*Extended:* %.2f%%\n" +
                        "*Variational:* %.2f%%\n" +
                        "*Action:* %s",
                rate.getSymbol(),
                rate.getArbitrageRate(),
                rate.getExtendedRate(),
                rate.getVariationalRate(),
                rate.getAction());
    }

    private Map<String, Map<String, Object>> executeRequest(HttpGet httpGet) throws Exception {
        try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            log.debug("Received response: {}", responseBody);
            return parseResponse(responseBody);
        } catch (Exception e) {
            log.error("Error sending request", e);
            throw new Exception("Error sending request: " + e.getMessage());
        }
    }

    private Map<String, Map<String, Object>> parseResponse(String responseBody) throws Exception {
        Map<String, Object> fullResponse = objectMapper.readValue(responseBody, Map.class);
        log.info("Full response received");

        Map<String, Map<String, Object>> fundingRates =
                (Map<String, Map<String, Object>>) fullResponse.get("funding_rates");

        if (Objects.isNull(fundingRates)) {
            throw new ValidationException("Failed to get funding rates");
        }

        log.info("Funding rates found");
        return fundingRates;
    }
}



