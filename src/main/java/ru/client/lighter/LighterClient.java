package ru.client.lighter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import ru.client.ExchangeClient;
import ru.dto.exchanges.ExchangeType;
import ru.dto.exchanges.OrderResult;
import ru.dto.exchanges.lighter.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Setter
@Component
@ConfigurationProperties(prefix = "exchanges.lighter")
public class LighterClient implements ExchangeClient {

    private final ObjectMapper objectMapper;
    private final HttpClient localHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String baseUrl;

    public LighterClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ============================================
    // MARKETS
    // ============================================

    public List<LighterMarket> getMarkets() {
        String url = baseUrl + "/markets";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[Lighter] Markets API error: {}", response.statusCode());
                return List.of();
            }

            LighterMarketsResponse dto = objectMapper.readValue(
                    response.body(), LighterMarketsResponse.class);

            log.info("[Lighter] Loaded {} markets", dto.getCount());
            return dto.getData();

        } catch (Exception e) {
            log.error("[Lighter] Failed to get markets", e);
            return List.of();
        }
    }

    // ============================================
    // BALANCE
    // ============================================

    @Override
    public Double getBalance() {
        String url = baseUrl + "/balance";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[Lighter] Balance API error: {}", response.statusCode());
                return 0.0;
            }

            LighterBalanceDto dto = objectMapper.readValue(response.body(), LighterBalanceDto.class);

            if (!"OK".equals(dto.getStatus())) {
                return 0.0;
            }

            double balance = Double.parseDouble(dto.getData().getAvailableForTrade());
            log.info("[Lighter] Balance: ${}", balance);
            return balance;

        } catch (Exception e) {
            log.error("[Lighter] Failed to get balance", e);
            return 0.0;
        }
    }

    public Double getEquity() {
        String url = baseUrl + "/balance";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) return 0.0;

            LighterBalanceDto dto = objectMapper.readValue(response.body(), LighterBalanceDto.class);

            if (!"OK".equalsIgnoreCase(dto.getStatus())) return 0.0;

            double equity = Double.parseDouble(dto.getData().getEquity());
            log.info("[Lighter] Equity: ${}", equity);
            return equity;

        } catch (Exception e) {
            log.error("[Lighter] Failed to get equity", e);
            return 0.0;
        }
    }

    public Double getMarginUsed() {
        String url = baseUrl + "/balance";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) return 0.0;

            LighterBalanceDto dto = objectMapper.readValue(response.body(), LighterBalanceDto.class);

            if (!"OK".equalsIgnoreCase(dto.getStatus())) return 0.0;

            double marginUsed = Double.parseDouble(dto.getData().getMarginUsed());
            log.info("[Lighter] Margin used: ${}", marginUsed);
            return marginUsed;

        } catch (Exception e) {
            log.error("[Lighter] Failed to get margin used", e);
            return 0.0;
        }
    }

    // ============================================
    // LEVERAGE
    // ============================================

    public String setLeverage(String market, int leverage) {
        String url = baseUrl + "/user/leverage";

        if (leverage < 1 || leverage > 50) {
            log.error("[Lighter] Invalid leverage: {}. Must be 1-50x", leverage);
            return null;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("market", market);
        body.put("leverage", leverage);

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            log.info("[Lighter] Setting leverage: {}", jsonBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[Lighter] Leverage HTTP error: {}", response.statusCode());
                return null;
            }

            LighterLeverageResponse dto = objectMapper.readValue(
                    response.body(), LighterLeverageResponse.class);

            if (!"success".equalsIgnoreCase(dto.getStatus())) {
                log.warn("[Lighter] Leverage status: {}", dto.getStatus());
                return null;
            }

            log.info("[Lighter] Leverage {}x set", leverage);
            return dto.getTxHash() != null ? dto.getTxHash() : "success";

        } catch (Exception e) {
            log.error("[Lighter] Exception setting leverage", e);
            return null;
        }
    }

    // ============================================
    // OPEN POSITION
    // ============================================

    /**
     * ✅ Открывает рыночную позицию
     * Python сам обработает size и leverage
     */
    public String openMarketPosition(String market, String side, double size) {
        String url = baseUrl + "/order/market";

        Map<String, Object> body = new HashMap<>();
        body.put("market", market);
        body.put("side", side.toUpperCase());
        body.put("size", String.valueOf(size));

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            log.info("[Lighter] Opening position: {}", jsonBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            log.info("[Lighter] Response (HTTP {}): {}",
                    response.statusCode(), response.body());

            if (response.statusCode() != 200) {
                log.error("[Lighter] Open HTTP error: {}", response.statusCode());
                return null;
            }

            LighterMarketOrderResponse dto = objectMapper.readValue(
                    response.body(), LighterMarketOrderResponse.class);

            if (!"success".equalsIgnoreCase(dto.getStatus())) {
                log.error("[Lighter] Open failed: status={}", dto.getStatus());
                return null;
            }

            log.info("[Lighter] Position opened: tx={}", dto.getTxHash());
            return dto.getTxHash();

        } catch (Exception e) {
            log.error("[Lighter] Exception opening position", e);
            return null;
        }
    }

    /**
     * ✅ Открывает позицию по размеру
     */
    public String openPositionWithSize(String market, double size, String direction) {
        String side = direction.equalsIgnoreCase("LONG") ? "BUY" : "SELL";

        log.info("[Lighter] Opening {} {} size={}", direction, market, size);

        if (size <= 0) {
            log.error("[Lighter] Invalid size: {}", size);
            return null;
        }

        return openMarketPosition(market, side, size);
    }

    /**
     * ✅ Открывает позицию с фиксированным margin (НЕ РАБОТАЕТ БЕЗ ЦЕНЫ)
     */
    public String openPositionWithFixedMargin(String market, double marginUsd,
                                              int leverage, String direction) {
        log.warn("[Lighter] openPositionWithFixedMargin requires price endpoint");
        log.warn("[Lighter] Use openPositionWithSize instead");
        return null;
    }

    // ============================================
    // CLOSE POSITION
    // ============================================

    public String closePosition(String market, String currentSide) {
        String url = baseUrl + "/positions/close";

        Map<String, Object> body = new HashMap<>();
        body.put("market", market);
        body.put("current_side", currentSide.toUpperCase());

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            log.info("[Lighter] Closing position: {}", jsonBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            log.info("[Lighter] Close response (HTTP {}): {}",
                    response.statusCode(), response.body());

            if (response.statusCode() != 200) {
                log.error("[Lighter] Close HTTP error: {}", response.statusCode());
                return null;
            }

            LighterClosePositionResponse dto = objectMapper.readValue(
                    response.body(), LighterClosePositionResponse.class);

            if ("success".equalsIgnoreCase(dto.getStatus()) ||
                    "submitted".equalsIgnoreCase(dto.getStatus())) {
                log.info("[Lighter] Position closed: tx={}", dto.getTxHash());
                return dto.getTxHash();
            }

            log.error("[Lighter] Close failed: status={}", dto.getStatus());
            return null;

        } catch (Exception e) {
            log.error("[Lighter] Exception closing position", e);
            return null;
        }
    }

    public OrderResult closePositionWithResult(String market, String currentSide) {
        String txHash = closePosition(market, currentSide);

        if (txHash != null) {
            return OrderResult.builder()
                    .exchange(ExchangeType.LIGHTER)
                    .symbol(market)
                    .success(true)
                    .orderId(txHash)
                    .message("Position closed")
                    .timestamp(System.currentTimeMillis())
                    .build();
        }

        return OrderResult.builder()
                .exchange(ExchangeType.LIGHTER)
                .symbol(market)
                .success(false)
                .message("Failed to close position")
                .errorCode("CLOSE_FAILED")
                .timestamp(System.currentTimeMillis())
                .build();
    }

    // ============================================
    // POSITIONS
    // ============================================

    public List<LighterPosition> getPositions(String market, String side) {
        StringBuilder url = new StringBuilder(baseUrl + "/positions");

        if (market != null || side != null) {
            url.append("?");
            if (market != null) url.append("market=").append(market);
            if (side != null) {
                if (market != null) url.append("&");
                url.append("side=").append(side.toUpperCase());
            }
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            log.info("[Lighter] Positions response (HTTP {}): {}",
                    response.statusCode(), response.body());

            if (response.statusCode() != 200) {
                log.error("[Lighter] Positions HTTP error: {}", response.statusCode());
                return List.of();
            }

            LighterPositionsResponse dto = objectMapper.readValue(
                    response.body(), LighterPositionsResponse.class);

            return dto.getData() != null ? dto.getData() : List.of();

        } catch (Exception e) {
            log.error("[Lighter] Exception getting positions", e);
            return List.of();
        }
    }

    // ============================================
    // FUNDING
    // ============================================


    public Double getAccumulatedFunding(String market, long openTimeMs) {
        String url = baseUrl + "/funding/payments"
                + "?market=" + market
                + "&since=" + (openTimeMs / 1000); // ms → seconds

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[Lighter] Funding HTTP error: {}", response.statusCode());
                return 0.0;
            }

            JsonNode root = objectMapper.readTree(response.body());

            if (!"OK".equals(root.path("status").asText())) {
                log.warn("[Lighter] Funding status not OK: {}", root.path("status").asText());
                return 0.0;
            }

            double funding = root.path("accumulated_funding").asDouble(0.0);
            log.info("[Lighter] Accumulated funding for {} (since {}): ${}",
                    market, openTimeMs, funding);
            return funding;

        } catch (Exception e) {
            log.error("[Lighter] Error getting funding", e);
            return 0.0;
        }
    }

    // ✅ Перегрузка для обратной совместимости
    public Double getAccumulatedFunding(String market) {
        return getAccumulatedFunding(market, 0L);
    }

    public Double getFundingRate(String market) {
        String url = baseUrl + "/market/" + market + "/funding-rate";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[Lighter] Funding rate HTTP error: {}", response.statusCode());
                return 0.0;
            }

            JsonNode root = objectMapper.readTree(response.body());

            if (!"OK".equals(root.path("status").asText())) {
                log.warn("[Lighter] Funding rate status not OK for {}", market);
                return 0.0;
            }

            double rate = root.path("funding_rate").asDouble(0.0);
            String direction = root.path("direction").asText("unknown");

            log.info("[Lighter] Funding rate for {}: {} (direction={})", market, rate, direction);
            return rate;

        } catch (Exception e) {
            log.error("[Lighter] Failed to get funding rate for {}: {}", market, e.getMessage());
            return 0.0;
        }
    }



// ORDERBOOK METHODS
// ============================================

    /**
     * Получить orderbook для рынка
     */
    public LighterOrderBookResponse getOrderBook(String market, int limit) {
        String url = baseUrl + "/market/" + market + "/orderbook?limit=" + limit;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            log.info("[Lighter] OrderBook response (HTTP {})", response.statusCode());

            if (response.statusCode() != 200) {
                log.error("[Lighter] OrderBook HTTP error: {}", response.statusCode());
                return null;
            }

            LighterOrderBookResponse dto = objectMapper.readValue(
                    response.body(), LighterOrderBookResponse.class);

            if (!"OK".equals(dto.getStatus())) {
                return null;
            }

            log.info("[Lighter] OrderBook: {} bids, {} asks, mid=${}",
                    dto.getSummary().getBidsCount(),
                    dto.getSummary().getAsksCount(),
                    dto.getSummary().getMidPrice());

            return dto;

        } catch (Exception e) {
            log.error("[Lighter] Failed to get orderbook", e);
            return null;
        }
    }

    /**
     * Получить orderbook с лимитом по умолчанию (10)
     */
    public LighterOrderBookResponse getOrderBook(String market) {
        return getOrderBook(market, 10);
    }

    /**
     * Получить только best bid/ask
     */
    public LighterBestPricesResponse getBestPrices(String market) {
        String url = baseUrl + "/market/" + market + "/best-prices";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[Lighter] Best prices HTTP error: {}", response.statusCode());
                return null;
            }

            LighterBestPricesResponse dto = objectMapper.readValue(
                    response.body(), LighterBestPricesResponse.class);

            if (!"OK".equals(dto.getStatus())) {
                return null;
            }

            log.info("[Lighter] Best prices: bid=${}, ask=${}, mid=${}",
                    dto.getBestBid(), dto.getBestAsk(), dto.getMidPrice());

            return dto;

        } catch (Exception e) {
            log.error("[Lighter] Failed to get best prices", e);
            return null;
        }
    }

    /**
     * Получить глубину рынка с анализом
     */
    public LighterDepthResponse getMarketDepth(String market, int limit) {
        String url = baseUrl + "/market/" + market + "/depth?limit=" + limit;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[Lighter] Depth HTTP error: {}", response.statusCode());
                return null;
            }

            LighterDepthResponse dto = objectMapper.readValue(
                    response.body(), LighterDepthResponse.class);

            if (!"OK".equals(dto.getStatus())) {
                return null;
            }

            log.info("[Lighter] Depth: bid_value=${}, ask_value=${}, imbalance={}",
                    dto.getBids().getTotalValue(),
                    dto.getAsks().getTotalValue(),
                    dto.getImbalance().getBidAskRatio());

            return dto;

        } catch (Exception e) {
            log.error("[Lighter] Failed to get market depth", e);
            return null;
        }
    }

    /**
     * Получить глубину с лимитом по умолчанию (20)
     */
    public LighterDepthResponse getMarketDepth(String market) {
        return getMarketDepth(market, 20);
    }

    public Double calculateMaxSizeForMargin(String market, double marginUsd,
                                            int leverage, boolean isBuy) {
        String url = baseUrl + "/market/" + market + "/calculate-size";

        Map<String, Object> body = new HashMap<>();
        body.put("margin_usd", marginUsd);
        body.put("leverage", leverage);
        body.put("is_buy", isBuy);

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            log.info("[Lighter] Calculating max size: {}", jsonBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[Lighter] Calculate size HTTP error: {}", response.statusCode());
                return null;
            }

            LighterCalculateSizeResponse dto = objectMapper.readValue(
                    response.body(), LighterCalculateSizeResponse.class);

            if (!"OK".equals(dto.getStatus())) {
                log.error("[Lighter] Calculate size failed: {}", dto.getStatus());
                return null;
            }

            log.info("[Lighter] Max size for margin ${} @ {}x: {} {} (price: ${})",
                    marginUsd, leverage,
                    String.format("%.4f", dto.getMaxSize()),
                    market,
                    String.format("%.4f", dto.getPrice()));

            return dto.getMaxSize();

        } catch (Exception e) {
            log.error("[Lighter] Exception calculating max size", e);
            return null;
        }
    }

    public int getMaxLeverage(String symbol) {
        String url = baseUrl + "/market/" + symbol + "/max-leverage";

        try {
            log.info("[Lighter] Getting max leverage for {}", symbol);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[Lighter] Max leverage HTTP error: {}, using default 5x", response.statusCode());
                return 5;  // Conservative default
            }

            JsonNode root = objectMapper.readTree(response.body());

            if (!"OK".equals(root.path("status").asText())) {
                log.warn("[Lighter] Max leverage request failed, using default 5x");
                return 5;
            }

            int maxLeverage = root.path("max_leverage").asInt(5);
            String source = root.path("source").asText("unknown");

            log.info("[Lighter] Max leverage for {}: {}x (source: {})", symbol, maxLeverage, source);

            return maxLeverage;

        } catch (IOException | InterruptedException e) {
            log.error("[Lighter] Exception getting max leverage for {}, using default 5x", symbol, e);
            return 5;  // Conservative default
        } catch (Exception e) {
            log.error("[Lighter] Unexpected error getting max leverage for {}, using default 5x", symbol, e);
            return 5;
        }
    }



}
