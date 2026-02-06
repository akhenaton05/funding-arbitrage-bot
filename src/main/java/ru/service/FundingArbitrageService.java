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
import ru.config.FundingConfig;
import ru.dto.exchanges.Direction;
import ru.dto.exchanges.FundingOpenSignal;
import ru.dto.funding.ArbitrageRates;
import ru.dto.funding.HoldingMode;
import ru.event.FundingAlertEvent;
import ru.event.NewArbitrageEvent;
import ru.utils.FundingArbitrageContext;

import javax.xml.bind.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
public class FundingArbitrageService {

    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;
    private final FundingArbitrageContext fundingContext;
    private final ApplicationEventPublisher eventPublisher;
    private final FundingConfig fundingConfig;
    private static final String API_URL = "https://api.loris.tools/funding";

    public FundingArbitrageService(CloseableHttpClient httpClient,
                                   FundingArbitrageContext fundingContext,
                                   ApplicationEventPublisher eventPublisher, FundingConfig fundingConfig) {
        this.fundingConfig = fundingConfig;
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
        Map<String, Object> aster = fundingRates.get("aster");

        if (Objects.isNull(extended) || Objects.isNull(aster)) {
            log.error("Required exchanges not found");
            return Collections.emptyList();
        }

        List<ArbitrageRates> arbitrageRates = new ArrayList<>();

        for (Map.Entry<String, Object> entry : extended.entrySet()) {
            String symbol = entry.getKey();

            if (aster.containsKey(symbol)) {
                double extendedRate = ((Number) entry.getValue()).doubleValue();
                double asterRate = ((Number) aster.get(symbol)).doubleValue();
                double arbitrage = Math.abs(extendedRate - asterRate);

                String action = extendedRate < asterRate ?
                        "BUY extended, SELL aster" :
                        "SELL extended, BUY aster";

                arbitrageRates.add(ArbitrageRates.builder()
                        .symbol(symbol)
                        .arbitrageRate(arbitrage)
                        .extendedRate(extendedRate)
                        .asterRate(asterRate)
                        .action(action)
                        .build());
            }
        }

        //Sorting
        arbitrageRates.sort(Comparator.comparingDouble(ArbitrageRates::getArbitrageRate).reversed());

        return arbitrageRates;
    }

    @Scheduled(cron = "0 44 * * * *")
    private void fundingTracker() {
        try {
            List<ArbitrageRates> arbitrageRates = calculateArbitrageRates();

            if (arbitrageRates.isEmpty()) {
                log.warn("[FundingBot] No arbitrage rates calculated");
                return;
            }

            ArbitrageRates topRate = arbitrageRates.getFirst();
            double fundingRate = topRate.getArbitrageRate();

            // Минимальный порог для открытия
            double minRate = fundingConfig.getThresholds().getSmartModeRate();

            if (fundingRate < minRate) {
                log.info("[FundingBot] Skipping {}: {}bps < {}bps (min threshold)",
                        topRate.getSymbol(), fundingRate, minRate);
                return;
            }

            //Mode selecting
            double fastThreshold = fundingConfig.getThresholds().getFastModeRate();
            HoldingMode selectedMode;
            int leverage;

            if (fundingRate >= fastThreshold) {
                // Fast Mode: >= 150 bps
                selectedMode = HoldingMode.FAST_MODE;
                leverage = fundingConfig.getFast().getLeverage();

                log.info("[FundingBot] FastMode selected: {}bps >= {}bps",
                        fundingRate, fastThreshold);
                log.info("[FundingBot] Opening {} with {}x leverage (close after funding rate received)",
                        topRate.getSymbol(), leverage);
            } else {
                // Smart Mode: 50-149 bps
                selectedMode = HoldingMode.SMART_MODE;
                leverage = fundingConfig.getSmart().getLeverage();

                log.info("[FundingBot] SmartMode selected: {}bps < {}bps",
                        fundingRate, fastThreshold);
                log.info("[FundingBot] Opening {} with {}x leverage (hold until unprofitable)",
                        topRate.getSymbol(), leverage);
            }

            //Sending event to listeners
            for (Long chatId : fundingContext.getSubscriberIds()) {
                eventPublisher.publishEvent(new FundingAlertEvent(chatId, topRate, selectedMode, leverage));

                FundingOpenSignal signal = convertToSignal(topRate, selectedMode, leverage);
                eventPublisher.publishEvent(new NewArbitrageEvent(signal));
            }

        } catch (Exception e) {
            log.error("[FundingBot] Error in funding tracker", e);
        }
    }

    private FundingOpenSignal convertToSignal(ArbitrageRates rates, HoldingMode mode, Integer leverage) {
        boolean sellExtended = rates.getAction().startsWith("SELL extended");

        FundingOpenSignal signal = new FundingOpenSignal();
        signal.setTicker(rates.getSymbol());
        signal.setExtendedDirection(sellExtended ? Direction.SHORT : Direction.LONG);
        signal.setAsterDirection(sellExtended ? Direction.LONG : Direction.SHORT);
        signal.setAction(rates.getAction());
        signal.setMode(mode);
        signal.setLeverage(leverage);

        return signal;
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



