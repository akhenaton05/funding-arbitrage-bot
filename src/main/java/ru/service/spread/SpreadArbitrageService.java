package ru.service.spread;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.config.FundingConfig;
import ru.config.SpreadConfig;
import ru.dto.exchanges.Direction;
import ru.dto.exchanges.ExchangePosition;
import ru.dto.exchanges.ExchangeType;
import ru.dto.funding.ArbitrageRates;
import ru.dto.funding.FundingOpenSignal;
import ru.dto.funding.HoldingMode;
import ru.event.FundingAlertEvent;
import ru.event.NewArbitrageEvent;
import ru.utils.FundingArbitrageContext;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SpreadArbitrageService {

    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;
    private final FundingArbitrageContext fundingContext;
    private final ApplicationEventPublisher eventPublisher;
    private final SpreadConfig spreadConfig;
    private static final String API_URL = "https://api.loris.tools/funding";

    //Cache for API response for parsing
    private Map<String, Object> cachedFullResponse = null;
    private long lastFetchTime = 0;
    private static final long CACHE_TTL_MS = 60000; // 1 min cache life

    private static final Set<String> SUPPORTED_EXCHANGES = Set.of(
            "lighter",
            "extended",
            "aster",
            "hyperliquid"
    );

    public SpreadArbitrageService(CloseableHttpClient httpClient,
                                  FundingArbitrageContext fundingContext,
                                  ApplicationEventPublisher eventPublisher, SpreadConfig spreadConfig) {
        this.spreadConfig = spreadConfig;
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
        this.fundingContext = fundingContext;
        this.eventPublisher = eventPublisher;
    }

    public Set<String> getOiFilteredSymbols() {
        Map<String, Integer> oiRankings = getOiRankings();

        if (oiRankings.isEmpty()) {
            log.warn("[SpreadScan] OI rankings empty, nothing to scan");
            return Collections.emptySet();
        }

        Set<String> symbols = oiRankings.entrySet().stream()
                .filter(e -> e.getValue() <= spreadConfig.getOi().getMaxRank())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        log.info("[SpreadScan] {} symbols after OI filter (maxRank={})",
                symbols.size(), spreadConfig.getOi().getMaxRank());

        return symbols;
    }

    private Map<String, Object> getFullApiResponse() {
        long now = System.currentTimeMillis();

        if (cachedFullResponse != null && (now - lastFetchTime) < CACHE_TTL_MS) {
            log.debug("[SpreadScan] Using cached API response");
            return cachedFullResponse;
        }

        try {
            HttpGet httpGet = new HttpGet(API_URL);

            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                log.debug("Received response: {}", responseBody);

                cachedFullResponse = objectMapper.readValue(responseBody, Map.class);
                lastFetchTime = now;

                log.info("[SpreadScan] Full API response received and cached");
                return cachedFullResponse;
            }

        } catch (Exception e) {
            log.error("[SpreadScan] Failed to get API response", e);
            return Collections.emptyMap();
        }
    }

    private Map<String, Integer> getOiRankings() {
        Map<String, Object> fullResponse = getFullApiResponse();

        if (fullResponse.isEmpty()) {
            log.warn("[SpreadScan] Full response is empty");
            return Collections.emptyMap();
        }

        Map<String, Object> oiRankingsRaw = (Map<String, Object>) fullResponse.get("oi_rankings");

        if (oiRankingsRaw == null) {
            log.warn("[SpreadScan] No oi_rankings in API response");
            return Collections.emptyMap();
        }

        Map<String, Integer> oiRankings = new HashMap<>();

        for (Map.Entry<String, Object> entry : oiRankingsRaw.entrySet()) {
            String symbol = entry.getKey();
            Object rankValue = entry.getValue();

            try {
                int rank;
                if (rankValue instanceof String) {
                    String rankStr = (String) rankValue;
                    rank = rankStr.contains("+") ? 999 : Integer.parseInt(rankStr);
                } else {
                    rank = ((Number) rankValue).intValue();
                }

                oiRankings.put(symbol, rank);

            } catch (Exception e) {
                log.debug("[SpreadScan] Failed to parse rank for {}: {}", symbol, rankValue);
            }
        }

        log.info("[SpreadScan] Parsed {} OI rankings", oiRankings.size());
        return oiRankings;
    }
}



