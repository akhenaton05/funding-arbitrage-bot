package ru.client.extended;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import ru.client.ExchangeClient;
import ru.dto.exchanges.extended.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Setter
@Component
@ConfigurationProperties(prefix = "exchanges.extended")
public class ExtendedClient implements ExchangeClient {

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

    @Override
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


    public String closePosition(String market, String currentSide) {
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
            return orderResponse.getExternalId();

        } catch (Exception e) {
            log.error("[Extended] Exception closing position", e);
            return null;
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

    public List<ExtendedOrderHistoryItem> getOrdersHistory(String market, Integer limit, Long cursor) {
        try {
            StringBuilder path = new StringBuilder("/orders/history");
            boolean hasParams = false;

            if (market != null || limit != null || cursor != null) {
                path.append("?");
                if (market != null) {
                    path.append("market=").append(market).append("&");
                    hasParams = true;
                }
                if (limit != null) {
                    path.append("limit=").append(limit).append("&");
                    hasParams = true;
                }
                if (cursor != null) {
                    path.append("cursor=").append(cursor).append("&");
                    hasParams = true;
                }
                if (hasParams) {
                    path.setLength(path.length() - 1);
                }
            }

            String fullUrl = baseUrl + path;
            log.info("[Extended] JDK HttpClient → requesting: {}", fullUrl);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            String body = response.body();

            log.info("[Extended] JDK getOrdersHistory → code={}, body length={}", statusCode, body.length());

            if (statusCode == 200) {
                ExtendedOrdersHistoryResponse resp = objectMapper.readValue(body, ExtendedOrdersHistoryResponse.class);

                if ("OK".equalsIgnoreCase(resp.getStatus())) {
                    log.info("[Extended] Успешно получено {} ордеров", resp.getData().size());
                    return resp.getData();
                } else {
                    log.warn("[Extended] API вернул status != OK: {}", resp.getStatus());
                    return null;
                }
            } else {
                log.error("[Extended] Неудачный ответ сервера: {} → {}", statusCode, body);
                return null;
            }

        } catch (Exception e) {
            log.error("[Extended] Ошибка при получении истории ордеров (JDK client)", e);
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

    public Double getEquity() {
        String balanceUri = baseUrl + "/balance";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(balanceUri))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return 0.0;

            ExtendedBalanceDto dto = objectMapper.readValue(response.body(), ExtendedBalanceDto.class);
            if (!"OK".equalsIgnoreCase(dto.getStatus())) return 0.0;

            double equity = Double.parseDouble(dto.getData().getEquity());
            log.info("[Extended] Equity: ${}", equity);
            return equity;

        } catch (Exception e) {
            log.error("[Extended] Failed to get equity", e);
            return 0.0;
        }
    }
}