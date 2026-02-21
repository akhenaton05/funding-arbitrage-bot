package ru.client.extended;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import ru.dto.exchanges.ExchangeType;
import ru.dto.exchanges.OrderResult;
import ru.dto.exchanges.extended.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Slf4j
@Setter
@Component
@ConfigurationProperties(prefix = "exchanges.extended")
public class ExtendedClient {

    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;

    private final HttpClient localHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String apiKey;
    private String baseUrl;

    public ExtendedClient(CloseableHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Leverage
     */

    public String setLeverage(String market, int leverage) {
        String endpoint = baseUrl + "/user/leverage";

        Map<String, Object> body = new HashMap<>();
        body.put("market", market);
        body.put("leverage", String.valueOf(leverage));

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            log.info("[Extended] Setting leverage: {}", jsonBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("[Extended] Leverage response (code {}): {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                log.info("[Extended] Leverage set to {}x for {}", leverage, market);
                return "OK";
            } else {
                log.error("[Extended] Leverage failed: {}", response.body());
                return null;
            }
        } catch (Exception e) {
            log.error("[Extended] Exception setting leverage", e);
            return null;
        }
    }

    /**
     * Open\close\view position
     */

    public String openPosition(String market, String side, double sizeUsd) {
        String orderUri = baseUrl + "/api/v1/user/order";

        try {
            double markPrice = getMarkPrice(market);
            if (markPrice == 0) {
                log.error("[Extended] Failed to get mark price");
                return null;
            }

            double qty = sizeUsd / markPrice;
            String orderId = "order_" + System.currentTimeMillis();
            double protectionPrice = side.equalsIgnoreCase("BUY")
                    ? markPrice * 1.05
                    : markPrice * 0.95;

            Map<String, Object> body = new HashMap<>();
            body.put("id", orderId);
            body.put("market", market);
            body.put("type", "MARKET");
            body.put("side", side.toUpperCase());
            body.put("qty", String.format(Locale.US, "%.5f", qty));
            body.put("price", String.format(Locale.US, "%.1f", protectionPrice));
            body.put("fee", "0.001");
            body.put("timeInForce", "IOC");
            body.put("expiryEpochMillis", System.currentTimeMillis() + 86400000);
            body.put("nonce", String.valueOf(System.currentTimeMillis()));

            String jsonBody = objectMapper.writeValueAsString(body);
            log.info("[Extended] Sending order: {}", jsonBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(orderUri))
                    .timeout(Duration.ofSeconds(30))
                    .header("X-API-KEY", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("[Extended] Order response (code {}): {}", response.statusCode(), response.body());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                log.info("[Extended] Position opened: {}", orderId);
                return orderId;
            } else {
                log.error("[Extended] Order failed: {}", response.body());
                return null;
            }
        } catch (Exception e) {
            log.error("[Extended] Exception opening position", e);
            return null;
        }
    }

    public String openMarketPosition(String market, String side, double size, double slippagePct) {
        String endpoint = baseUrl + "/order/market";

        double stepSize = getStepSize(market);
        size = roundToStepSize(size, stepSize);

        //Increasing the  slippage for non-BTC markets (for guaranteed opening)
        if (!market.equals("BTC-USD")) {
            slippagePct = Math.max(slippagePct, 2.0);  // Minimum 2% for SOL/ETH
            log.info("[Extended] Adjusted slippage to {}% for {}", slippagePct, market);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("market", market);
        body.put("side", side.toUpperCase());
        body.put("size", String.valueOf(size));
        body.put("price_slippage_pct", String.valueOf(slippagePct));
        body.put("external_id", "market-order-" + System.currentTimeMillis());

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            log.info("[Extended] Sending market open: {}", jsonBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int httpCode = response.statusCode();
            String responseBody = response.body();
            log.info("[Extended] Market open response (HTTP {}): {}", httpCode, responseBody);

            if (httpCode != 202) {
                log.error("[Extended] HTTP error: code={}, body={}", httpCode, responseBody);
                return null;
            }

            AsyncOrderResponse orderResponse;
            try {
                orderResponse = objectMapper.readValue(responseBody, AsyncOrderResponse.class);
            } catch (Exception parseEx) {
                log.error("[Extended] JSON parse failed: {}", responseBody, parseEx);
                return null;
            }

            if (!"accepted".equalsIgnoreCase(orderResponse.getStatus())) {
                log.error("[Extended] Unexpected status: {}, response: {}",
                        orderResponse.getStatus(), responseBody);
                return null;
            }

            log.info("[Extended] Order accepted: external_id={}", orderResponse.getExternalId());
            return orderResponse.getExternalId();

        } catch (Exception e) {
            log.error("[Extended] Exception opening market position", e);
            return null;
        }
    }

    public String openPositionWithSize(String market, double size, String direction) {
        String side = direction.equalsIgnoreCase("LONG") ? "BUY" : "SELL";

        try {
            log.info("[Extended] Opening position by size: market={}, size={}, direction={}",
                    market, String.format("%.4f", size), direction);

            // Validate size
            if (size <= 0) {
                log.error("[Extended] Invalid size: {}", size);
                return null;
            }

            // Get step_size for rounding
            double stepSize = getStepSize(market);
            double roundedSize = roundToStepSize(size, stepSize);

            if (roundedSize < size * 0.95) {
                log.warn("[Extended] Size rounded down significantly: {} → {}",
                        String.format("%.4f", size),
                        String.format("%.4f", roundedSize));
            }

            // Get current price for logging
            double price = getMarkPrice(market);
            if (price <= 0) {
                log.error("[Extended] Failed to get mark price for {}", market);
                return null;
            }

            double notional = roundedSize * price;

            log.info("[Extended] Position params: size={}, price=${}, notional=${}, step_size={}",
                    String.format("%.4f", roundedSize),
                    String.format("%.6f", price),
                    String.format("%.2f", notional),
                    stepSize);

            // Open market position with exact size
            String externalId = openMarketPosition(market, side, roundedSize, 2.0);

            if (externalId == null) {
                log.error("[Extended] Failed to open position with size {}", roundedSize);
                return null;
            }

            log.info("[Extended] Position opened: external_id={}, size={} {}",
                    externalId,
                    String.format("%.4f", roundedSize),
                    market.split("-")[0]);

            return externalId;

        } catch (Exception e) {
            log.error("[Extended] Error opening position by size for {}", market, e);
            return null;
        }
    }

    public String openPositionWithFixedMargin(String symbol, double marginUsd, int leverage, String direction) {
        String side = direction.equalsIgnoreCase("LONG") ? "BUY" : "SELL";

        try {
            //Set leverage
            String leverageResult = setLeverage(symbol, leverage);
            if (leverageResult == null) {
                log.error("[Extended] Failed to set leverage");
                return null;
            }

            //Validate balance
            Double available = getBalance();
            if (available == null || available < marginUsd) {
                log.error("[Extended] Insufficient balance: ${} < ${}", available, marginUsd);
                return null;
            }

            //Get price
            double price = getMarkPrice(symbol);
            if (price <= 0) {
                log.error("[Extended] Failed to get price");
                return null;
            }

            //Get step_size for proper rounding
            double stepSize = getStepSize(symbol);

            //Calculate position size
            double notional = marginUsd * leverage;
            double rawSize = notional / price;

            double size = roundToStepSize(rawSize, stepSize);

            log.info("[Extended] Opening position: margin=${}, leverage={}x, price=${}, raw_size={}, rounded_size={}, step_size={}",
                    marginUsd, leverage, price, rawSize, size, stepSize);

            //Open position
            String externalId = openMarketPosition(symbol, side, size, 2.0); // 2% slippage

            if (externalId == null) {
                log.error("[Extended] Failed to open position");
                return null;
            }

            log.info("[Extended] Position opened: external_id={}", externalId);
            return externalId;

        } catch (Exception e) {
            log.error("[Extended] Error opening position", e);
            return null;
        }
    }

    public OrderResult closePosition(String market, String currentSide) {
        String endpoint = baseUrl + "/positions/close";

        Map<String, Object> body = new HashMap<>();
        body.put("market", market);
        body.put("current_side", currentSide.toUpperCase());
        body.put("external_id", "close-" + System.currentTimeMillis());

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            log.info("[Extended] Sending close position: {}", jsonBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int httpCode = response.statusCode();
            String responseBody = response.body();
            log.info("[Extended] Close response (code {}): {}", httpCode, responseBody);

            if (httpCode != 202) {
                log.error("[Extended] HTTP error: code={}, body={}", httpCode, responseBody);
                return null;
            }

            AsyncOrderResponse orderResponse;
            try {
                orderResponse = objectMapper.readValue(responseBody, AsyncOrderResponse.class);
            } catch (Exception parseEx) {
                log.error("[Extended] JSON parse failed: {}", responseBody, parseEx);
                return null;
            }

            if (!"accepted".equalsIgnoreCase(orderResponse.getStatus())) {
                log.error("[Extended] Unexpected close status: {}", orderResponse.getStatus());
                return null;
            }

            log.info("[Extended] Position closing: external_id={}, close_side={}",
                    orderResponse.getExternalId(), orderResponse.getCloseSide());
            return OrderResult.builder()
                    .exchange(ExchangeType.EXTENDED)
                    .symbol(market)
                    .success(true)
                    .orderId(orderResponse.getExternalId())
                    .message("Position closed")
                    .build();

        } catch (Exception e) {
            log.error("[Extended] Exception closing position", e);
            return OrderResult.builder()
                    .exchange(ExchangeType.EXTENDED)
                    .symbol(market)
                    .success(false)
                    .message(e.getMessage())
                    .errorCode(e.getClass().getSimpleName())
                    .build();
        }
    }

    public List<ExtendedPosition> getPositions(String market, String side) {
        try {
            StringBuilder url = new StringBuilder(baseUrl + "/positions");
            if (market != null || side != null) {
                url.append("?");
                boolean first = true;
                if (market != null) {
                    url.append("market=").append(market);
                    first = false;
                }
                if (side != null) {
                    if (!first) url.append("&");
                    url.append("side=").append(side.toUpperCase());
                }
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                ExtendedPositionsResponse positionsResponse = objectMapper.readValue(response.body(), ExtendedPositionsResponse.class);
                return positionsResponse.getData();
            } else {
                log.error("[Extended] Get positions failed: {} → {}", response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            log.error("[Extended] Exception getting positions", e);
            return null;
        }
    }

    /**
     * Funding
     */

    public ExtendedFundingHistoryResponse getFundingHistory(String market, String side, Long fromTime, Integer limit) {
        try {
            StringBuilder url = new StringBuilder(baseUrl + "/funding/history");
            boolean hasParams = false;

            if (market != null || side != null || fromTime != null || limit != null) {
                url.append("?");

                if (market != null) {
                    url.append("market=").append(market);
                    hasParams = true;
                }

                if (side != null) {
                    if (hasParams) url.append("&");
                    url.append("side=").append(side.toUpperCase());
                    hasParams = true;
                }

                if (fromTime != null) {
                    if (hasParams) url.append("&");
                    url.append("fromTime=").append(fromTime);
                    hasParams = true;
                }

                if (limit != null) {
                    if (hasParams) url.append("&");
                    url.append("limit=").append(limit);
                }
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            log.info("[Extended] GET funding history: {}", url);

            HttpResponse<String> response = localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            String body = response.body();

            if (statusCode != 200) {
                log.error("[Extended] Funding history failed: code={}, body={}", statusCode, body);
                return null;
            }

            ExtendedFundingHistoryResponse historyResponse = objectMapper.readValue(body, ExtendedFundingHistoryResponse.class);

            if (!"OK".equalsIgnoreCase(historyResponse.getStatus())) {
                log.warn("[Extended] Funding history status != OK: {}", historyResponse.getStatus());
                return null;
            }

            log.info("[Extended] Funding history: {} payments, net funding: ${}",
                    historyResponse.getSummary() != null ? historyResponse.getSummary().getPaymentsCount() : 0,
                    historyResponse.getSummary() != null ? String.format("%.4f", historyResponse.getSummary().getNetFunding()) : "0");

            return historyResponse;

        } catch (Exception e) {
            log.error("[Extended] Error getting funding history for {}", market, e);
            return null;
        }
    }

    /**
     * Utils
     */

    public double getMarkPrice(String market) {
        String url = baseUrl + "/market/price/" + market;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[Extended] Mark price failed: {} → {}", response.statusCode(), response.body());
                return 0.0;
            }

            String priceStr = response.body().trim();

            if (priceStr.isEmpty()) {
                log.error("[Extended] Empty price response for {}", market);
                return 0.0;
            }

            try {
                double markPrice = Double.parseDouble(priceStr);
                log.info("[Extended] Mark price for {}: ${}", market, markPrice);
                return markPrice;
            } catch (NumberFormatException e) {
                log.error("[Extended] Failed to parse price: '{}' for {}", priceStr, market, e);
                return 0.0;
            }

        } catch (Exception e) {
            log.error("[Extended] Failed to get mark price for {}", market, e);
            return 0.0;
        }
    }

    public Double getBalance() {
        String balanceUri = baseUrl + "/balance";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(balanceUri))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[Extended] Balance API error: {} → {}", response.statusCode(), response.body());
                return 0.0;
            }

            ExtendedBalanceDto balanceResponse = objectMapper.readValue(response.body(), ExtendedBalanceDto.class);

            if ("OK".equals(balanceResponse.getStatus())) {
                double availableBalance = Double.parseDouble(balanceResponse.getData().getAvailableForTrade());
                log.info("[Extended] Available balance: ${}", availableBalance);
                return availableBalance;
            }

            log.warn("[Extended] Balance status not OK: {}", balanceResponse.getStatus());
            return 0.0;

        } catch (Exception e) {
            log.error("[Extended] Failed to get balance", e);
            return 0.0;
        }
    }

    private double roundToStepSize(double value, double stepSize) {
        if (stepSize <= 0) {
            return value;
        }
        return Math.floor(value / stepSize) * stepSize;
    }

    public double getStepSize(String market) {
        try {
            String url = baseUrl + "/market/info/" + market;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[Extended] Failed to get step_size for {} (HTTP {}), using default 0.001",
                        market, response.statusCode());
                return 0.001;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

            String stepSizeStr = (String) result.get("step_size");

            if (stepSizeStr != null) {
                double stepSize = Double.parseDouble(stepSizeStr);
                log.info("[Extended] Step size for {}: {}", market, stepSize);
                return stepSize;
            }

            log.warn("[Extended] No step_size field for {}, using default 0.001", market);
            return 0.001;

        } catch (Exception e) {
            log.error("[Extended] Error getting step_size for {}, using default 0.001", market, e);
            return 0.001;
        }
    }

    public ExtendedOrderBook getOrderBook(String market) {
        try {
            String url = baseUrl + "/api/v1/info/markets/" + market + "/orderbook";

            log.debug("[Extended] GET order book: {}", url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[Extended] Order book failed: code={}, body={}",
                        response.statusCode(), response.body());
                return null;
            }

            ExtendedOrderBookResponse bookResponse = objectMapper.readValue(
                    response.body(),
                    ExtendedOrderBookResponse.class
            );

            if (!"OK".equalsIgnoreCase(bookResponse.getStatus())) {
                log.warn("[Extended] Order book status != OK: {}", bookResponse.getStatus());
                return null;
            }

            ExtendedOrderBook book = bookResponse.getData();

            log.info("[Extended] Order book {}: bids={}, asks={}, best_bid={}, best_ask={}",
                    market,
                    book.getBid() != null ? book.getBid().size() : 0,
                    book.getAsk() != null ? book.getAsk().size() : 0,
                    book.getBid() != null && !book.getBid().isEmpty() ? book.getBid().get(0).getPrice() : "N/A",
                    book.getAsk() != null && !book.getAsk().isEmpty() ? book.getAsk().get(0).getPrice() : "N/A"
            );

            return book;

        } catch (Exception e) {
            log.error("[Extended] Error getting order book for {}", market, e);
            return null;
        }
    }

    public ExtendedMarketStats getMarketStats(String market) {
        try {
            String url = baseUrl + "/api/v1/info/markets/" + market + "/stats";

            log.debug("[Extended] GET market stats: {}", url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[Extended] Market stats failed: code={}, body={}",
                        response.statusCode(), response.body());
                return null;
            }

            ExtendedMarketStatsResponse statsResponse = objectMapper.readValue(
                    response.body(),
                    ExtendedMarketStatsResponse.class
            );

            if (!"OK".equalsIgnoreCase(statsResponse.getStatus())) {
                log.warn("[Extended] Market stats status != OK: {}", statsResponse.getStatus());
                return null;
            }

            ExtendedMarketStats stats = statsResponse.getData();

            log.info("[Extended] Market stats {}: mark={}, bid={}, ask={}, last={}, spread={}%",
                    market,
                    stats.getMarkPrice(),
                    stats.getBidPrice(),
                    stats.getAskPrice(),
                    stats.getLastPrice(),
                    calculateSpread(stats)
            );

            return stats;

        } catch (Exception e) {
            log.error("[Extended] Error getting market stats for {}", market, e);
            return null;
        }
    }

    private String calculateSpread(ExtendedMarketStats stats) {
        try {
            double bid = Double.parseDouble(stats.getBidPrice());
            double ask = Double.parseDouble(stats.getAskPrice());
            double spread = ((ask - bid) / ask) * 100;
            return String.format("%.3f", spread);
        } catch (Exception e) {
            return "N/A";
        }
    }

    public Double estimateExecutionPrice(String market, double size, boolean isSell) {
        try {
            ExtendedOrderBook book = getOrderBook(market);

            if (book == null) {
                log.warn("[Extended] Order book unavailable for {}", market);
                return null;
            }

            List<OrderBookLevel> levels = isSell ? book.getBid() : book.getAsk();

            if (levels == null || levels.isEmpty()) {
                log.warn("[Extended] No liquidity in order book for {} {}",
                        market, isSell ? "BIDS" : "ASKS");
                return null;
            }

            double remainingSize = size;
            double totalCost = 0.0;
            double filledSize = 0.0;
            int levelsUsed = 0;

            for (OrderBookLevel level : levels) {
                if (remainingSize <= 0.0001) break;

                double levelQty = Double.parseDouble(level.getQty());
                double levelPrice = Double.parseDouble(level.getPrice());

                double fillQty = Math.min(remainingSize, levelQty);

                totalCost += fillQty * levelPrice;
                filledSize += fillQty;
                remainingSize -= fillQty;
                levelsUsed++;
            }

            if (filledSize < size * 0.5) {
                log.warn("[Extended] Insufficient liquidity: filled={}/{} for {} {}",
                        String.format("%.4f", filledSize),
                        String.format("%.4f", size),
                        market,
                        isSell ? "SELL" : "BUY");
                return null;
            }

            double avgPrice = totalCost / filledSize;

            log.info("[Extended] Execution estimate {}: size={}, filled={}, avg_price={}, levels_used={}, insufficient={}",
                    market,
                    String.format("%.4f", size),
                    String.format("%.4f", filledSize),
                    String.format("%.6f", avgPrice),
                    levelsUsed,
                    remainingSize > 0.0001
            );

            return avgPrice;

        } catch (Exception e) {
            log.error("[Extended] Error estimating execution price for {}", market, e);
            return null;
        }
    }

    public Double calculateMaxSizeForMargin(String market, double marginUsd, int leverage, boolean isBuy) {
        try {
            double notional = marginUsd * leverage;

            // Get execution price from order book
            Double executionPrice = estimateExecutionPrice(market, notional / 1000.0, !isBuy);

            if (executionPrice == null) {
                // Fallback to mark price
                executionPrice = getMarkPrice(market);
                if (executionPrice <= 0) {
                    log.error("[Extended] Failed to get price for {}", market);
                    return null;
                }
                log.warn("[Extended] Using mark price as fallback: ${}",
                        String.format("%.6f", executionPrice));
            }

            double maxSize = notional / executionPrice;

            log.info("[Extended] Max size for margin ${} @ {}x: {} {} (price: ${})",
                    String.format("%.2f", marginUsd),
                    leverage,
                    String.format("%.4f", maxSize),
                    market.split("-")[0],
                    String.format("%.6f", executionPrice));

            return maxSize;

        } catch (Exception e) {
            log.error("[Extended] Error calculating max size for {}", market, e);
            return null;
        }
    }

    public ExtendedPositionHistory getLastClosedPosition(String market, String side) {
        try {
            String url = baseUrl + "/positions/history?market=" + market + "&side=" + side.toUpperCase();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[Extended] getLastClosedPosition failed code={}, body={}", response.statusCode(), response.body());
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            Object realisedPnl = result.get("realised_pnl");

            // Парсим data[] напрямую
            List<ExtendedPositionHistory> positions = objectMapper.convertValue(
                    result.get("data"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ExtendedPositionHistory.class)
            );

            if (positions == null || positions.isEmpty()) {
                log.info("[Extended] getLastClosedPosition: no history for market={}, side={}", market, side);
                return null;
            }

            // Берём с максимальным closedTime
            ExtendedPositionHistory latest = positions.stream()
                    .filter(p -> p.getClosedTime() != null)
                    .max(Comparator.comparingLong(ExtendedPositionHistory::getClosedTime))
                    .orElse(positions.getFirst());

            log.info("[Extended] Last closed position: market={}, side={}, realisedPnl={}, tradePnl={}, openFees={}, closeFees={}, fundingFees={}, openPrice={}, exitPrice={}, size={}, closedTime={}",
                    latest.getMarket(),
                    latest.getSide(),
                    latest.getRealisedPnl(),
                    latest.getRealisedPnlBreakdown() != null ? latest.getRealisedPnlBreakdown().getTradePnl() : "n/a",
                    latest.getRealisedPnlBreakdown() != null ? latest.getRealisedPnlBreakdown().getOpenFees() : "n/a",
                    latest.getRealisedPnlBreakdown() != null ? latest.getRealisedPnlBreakdown().getCloseFees() : "n/a",
                    latest.getRealisedPnlBreakdown() != null ? latest.getRealisedPnlBreakdown().getFundingFees() : "n/a",
                    latest.getOpenPrice(),
                    latest.getExitPrice(),
                    latest.getSize(),
                    latest.getClosedTime()
            );

            return latest;

        } catch (Exception e) {
            log.error("[Extended] Error getting last position for market={}, side={}", market, side, e);
            return null;
        }
    }


}