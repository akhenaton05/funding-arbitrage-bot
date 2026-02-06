package ru.client.aster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.net.URIBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import ru.client.ExchangeClient;
import ru.dto.exchanges.MarginType;
import ru.dto.exchanges.aster.AsterPosition;
import ru.dto.exchanges.aster.OrderResponse;
import ru.dto.exchanges.aster.PremiumIndexResponse;
import ru.dto.exchanges.aster.SymbolFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Setter
@Component
@ConfigurationProperties(prefix = "exchanges.aster")
public class AsterClient implements ExchangeClient {

    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;

    private String publicApiKey;
    private String privateApiKey;
    private String baseUrl;

    //Filters cache(at the start of the program)
    private final Map<String, SymbolFilter> symbolFilters = new HashMap<>();

    public AsterClient(CloseableHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadSymbolFilters();
    }

    private void loadSymbolFilters() {
        try {
            String json = executePublicGet("/fapi/v1/exchangeInfo", null);
            if (json == null) return;

            JsonNode symbols = objectMapper.readTree(json).path("symbols");
            for (JsonNode s : symbols) {
                String sym = s.path("symbol").asText();
                JsonNode filters = s.path("filters");

                SymbolFilter filter = new SymbolFilter();
                for (JsonNode f : filters) {
                    String type = f.path("filterType").asText();
                    if ("LOT_SIZE".equals(type)) {
                        filter.setStepSize(f.path("stepSize").asText());
                        filter.setMinQty(f.path("minQty").asText());
                    } else if ("MIN_NOTIONAL".equals(type)) {
                        filter.setMinNotional(f.path("minNotional").asText());
                    }
                }
                if (filter.getStepSize() != null) {
                    symbolFilters.put(sym, filter);
                    log.debug("[Aster] Filter for {} loaded", sym);
                }
            }
            log.info("[Aster] Loaded {} symbols from exchangeInfo", symbolFilters.size());
        } catch (Exception e) {
            log.error("[Aster] Error loading exchangeInfo", e);
        }
    }

    private String executePublicGet(String endpoint, String queryParams) {
        try {
            URI uri = new URIBuilder(baseUrl + endpoint)
                    .setCustomQuery(queryParams)
                    .build();

            HttpGet get = new HttpGet(uri);
            log.info("[Aster Public GET] {}", uri);

            try (CloseableHttpResponse resp = httpClient.execute(get)) {
                int code = resp.getCode();
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                if (code == 200) return body;
                log.error("[Aster] Public GET error: code={}, body={}", code, body);
                return null;
            }
        } catch (Exception e) {
            log.error("[Aster] Public GET error {}", endpoint, e);
            return null;
        }
    }

    public String setLeverage(String symbol, int leverage) throws Exception {
        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(symbol);
        params.append("&leverage=").append(leverage);

        String response = executeSignedRequest("POST", "/fapi/v1/leverage", params.toString());

        log.info("[Aster] setLeverage response for {}: {}", symbol, response);

        //Checking status response
        if (response != null && !response.isEmpty()) {
            try {
                JsonNode node = objectMapper.readTree(response);
                int code = node.path("code").asInt(200);
                if (code != 200) {
                    log.error("[Aster] setLeverage failed for {} code={} msg={}",
                            symbol, code, node.path("msg").asText());
                    return null;
                }
            } catch (Exception e) {
                log.warn("[Aster] Failed to parse setLeverage response", e);
            }
        }

        return response;
    }


    public void setMarginType(String symbol, MarginType type) throws Exception {
        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(symbol);
        params.append("&marginType=").append(type.toString());

        executeSignedRequest("POST", "/fapi/v1/marginType", params.toString());
    }

    //Generate signature for the query
    private String sign(String queryString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(privateApiKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
            return Hex.encodeHexString(hash).toLowerCase();
        } catch (Exception e) {
            log.error("[Aster] Signature generation error HMAC-SHA256", e);
            throw new RuntimeException("[Aster] Signature generation error", e);
        }
    }

    //Adding signature to the request and executing, returns plain body
    private String executeSignedRequest(String method, String endpoint, String queryParams) {
        try {
            long timestamp = System.currentTimeMillis();
            String query = (queryParams != null && !queryParams.isEmpty())
                    ? queryParams + "&recvWindow=5000&timestamp=" + timestamp
                    : "recvWindow=5000&timestamp=" + timestamp;

            String signature = sign(query);
            String fullQuery = query + "&signature=" + signature;

            URI uri = new URIBuilder(baseUrl + endpoint).setCustomQuery(fullQuery).build();

            if ("GET".equalsIgnoreCase(method)) {
                HttpGet req = new HttpGet(uri);
                req.setHeader("X-MBX-APIKEY", publicApiKey);
                log.info("[Aster] {}", uri);
                try (CloseableHttpResponse resp = httpClient.execute(req)) {
                    return handleResponse(resp);
                }
            } else if ("POST".equalsIgnoreCase(method)) {
                HttpPost req = new HttpPost(uri);
                req.setHeader("X-MBX-APIKEY", publicApiKey);
                req.setHeader("Content-Type", "application/x-www-form-urlencoded");
                log.info("[Aster] {}", uri);
                try (CloseableHttpResponse resp = httpClient.execute(req)) {
                    return handleResponse(resp);
                }
            }
            throw new IllegalArgumentException("Unsupported method: " + method);
        } catch (Exception e) {
            log.error("[Aster] Error {} request {}", method, endpoint, e);
            return null;
        }
    }

    @Override
    public Double getBalance() {
        try {
            String queryParams = "recvWindow=5000"; //Reply attack defense
            String json = executeSignedRequest("GET", "/fapi/v2/balance", queryParams);
            if (Objects.isNull(json)) return 0.0;

            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray() || root.isEmpty()) {
                log.warn("[Aster] Balance is empty or not an array");
                return 0.0;
            }

            double usdtAvailable = 0.0;
            for (JsonNode node : root) {
                if ("USDT".equals(node.path("asset").asText())) {
                    usdtAvailable = node.path("availableBalance").asDouble(0.0);
                    log.info("[Aster] Balance available USDT: {}", usdtAvailable);
                    break;
                }
            }
            return usdtAvailable;
        } catch (Exception e) {
            log.error("[Aster] Error getting balance", e);
            return 0.0;
        }
    }

    public String openMarketOrder(String symbol, String side, double quantity, String positionSide) {
        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(symbol);
        params.append("&side=").append(side.toUpperCase());
        params.append("&type=MARKET");
        params.append("&quantity=").append(quantity);
        params.append("&positionSide=").append(positionSide.toUpperCase());
        params.append("&newOrderRespType=RESULT");

        log.info("[Aster] Market params appended: {}", params);

        try {
            String responseBody = executeSignedRequest("POST", "/fapi/v1/order", params.toString());

            if (responseBody == null || responseBody.isBlank()) {
                log.warn("[Aster] Empty response body (order might be filled instantly)");
                return "aster-" + System.currentTimeMillis();
            }

            OrderResponse response = objectMapper.readValue(responseBody, OrderResponse.class);

            //Getting orderId or clientOrderId
            String orderId = null;
            if (response.getOrderId() != null) {
                orderId = String.valueOf(response.getOrderId());
            } else if (response.getClientOrderId() != null && !response.getClientOrderId().isBlank()) {
                orderId = response.getClientOrderId();
            }

            if (orderId == null) {
                log.warn("[Aster] No orderId in response: {}", responseBody);
                return "aster-" + System.currentTimeMillis();
            }

            log.info("[Aster] Market order: orderId={}, status={}, executed={}/{}, avgPrice={}",
                    orderId,
                    response.getStatus(),
                    response.getExecutedQty(),
                    response.getOrigQty(),
                    response.getAvgPrice());

            //Status checking
            if ("FILLED".equals(response.getStatus()) || "PARTIALLY_FILLED".equals(response.getStatus())) {
                log.info("[Aster] Order execution confirmed");
            } else if ("NEW".equals(response.getStatus())) {
                log.warn("[Aster] Order placed but not filled yet (status=NEW)");
            } else {
                log.warn("[Aster] Unexpected order status: {}", response.getStatus());
            }

            return orderId;

        } catch (RuntimeException e) {
            log.error("[Aster] Market order API error", e);
            return null;

        } catch (Exception e) {
            log.error("[Aster] Failed to parse order response", e);
            return null;
        }
    }

    // Main method - opening with fixed margin and chosen leverage + side
    public String openPositionWithFixedMargin(String symbol, double usdMargin, int leverage, String direction) {
        final String side;
        final String positionSide;

        if (direction.equalsIgnoreCase("LONG")) {
            side = "BUY";
            positionSide = "LONG";
        } else if (direction.equalsIgnoreCase("SHORT")) {
            side = "SELL";
            positionSide = "SHORT";
        } else {
            log.error("[Aster] Invalid direction: {}", direction);
            return null;
        }

        try {
            //Balance validation
            double available = getBalance();
            if (available < usdMargin) {
                log.warn("[Aster] Insufficient balance: ${} < ${}", available, usdMargin);
                return null;
            }

            //Get ticker filters (stepSize, minQty, minNotional)
            SymbolFilter filter = symbolFilters.get(symbol);
            if (filter == null || filter.getStepSize() == null || filter.getStepSize().isEmpty()) {
                log.warn("[Aster] Empty stepSize for {}", symbol);
                return null;
            }

            double step;
            try {
                step = Double.parseDouble(filter.getStepSize());
            } catch (NumberFormatException e) {
                log.error("[Aster] Invalid stepSize '{}' for {}", filter.getStepSize(), symbol);
                return null;
            }

            double minQty = 0.0;
            if (filter.getMinQty() != null && !filter.getMinQty().isEmpty()) {
                minQty = Double.parseDouble(filter.getMinQty());
            }

            double minNotional = 5.0;
            if (filter.getMinNotional() != null && !filter.getMinNotional().isEmpty()) {
                minNotional = Double.parseDouble(filter.getMinNotional());
            }

            //Get mark price
            double price = getMarkPrice(symbol);
            if (price <= 0) {
                log.error("[Aster] Failed to get mark price for {}", symbol);
                return null;
            }

            //Calculate notional (margin * leverage)
            double notional = usdMargin * leverage;

            if (notional < minNotional) {
                log.warn("[Aster] Notional ${} < minNotional ${}, increase margin or leverage",
                        notional, minNotional);
                return null;
            }

            //Calculate quantity
            double qty = notional / price;
            double qtyRounded = Math.floor(qty / step) * step;

            if (qtyRounded < minQty) {
                if (qty >= minQty) {
                    qtyRounded = minQty;
                } else {
                    log.warn("[Aster] Rounded quantity {} < minQty {}", qtyRounded, minQty);
                    return null;
                }
            }

            String quantityStr = String.format(Locale.US, "%." + getPrecision(step) + "f", qtyRounded);

            log.info("[Aster] Opening {}: margin ${}, ×{}, qty={}, notional ${}, price ~{}",
                    direction, usdMargin, leverage, quantityStr, notional, price);

            //Set leverage
            setLeverage(symbol, leverage);

            //Open market order
            String orderId = openMarketOrder(symbol, side, Double.parseDouble(quantityStr), positionSide);

            //Checking result
            if (orderId == null) {
                log.error("[Aster] Failed to open market order");
                return null;
            }

            if (orderId.startsWith("aster-")) {
                log.warn("[Aster] Using fallback orderId: {}", orderId);
            }

            log.info("[Aster] Position opened: orderId={}", orderId);
            return orderId; //Returning orderId

        } catch (Exception e) {
            log.error("[Aster] Error opening position for ${} with ×{}", usdMargin, leverage, e);
            return null;
        }
    }

    public double getMarkPrice(String symbol) {
        try {
            // Public endpoint with no signature
            URIBuilder builder = new URIBuilder(baseUrl + "/fapi/v1/ticker/price");
            builder.addParameter("symbol", symbol);
            URI uri = builder.build();

            HttpGet get = new HttpGet(uri);
            log.info("[Aster] GET mark price для {}", symbol);

            try (CloseableHttpResponse resp = httpClient.execute(get)) {
                int code = resp.getCode();
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

                if (code != 200) {
                    log.error("[Aster] Mark price failed: code={}, body={}", code, body);
                    return 0.0;
                }

                JsonNode root = objectMapper.readTree(body);
                double price = root.path("price").asDouble(0.0);
                log.debug("[Aster] Mark price {}: {}", symbol, price);
                return price;
            }
        } catch (Exception e) {
            log.error("[Aster] Ошибка получения mark price для {}", symbol, e);
            return 0.0;
        }
    }

    private int getPrecision(double step) {
        String s = String.valueOf(step);
        return s.length() - s.indexOf('.') - 1;
    }

    public List<AsterPosition> getPositions(String symbol) {
        String query = symbol != null ? "symbol=" + symbol : null;
        String json = executeSignedRequest("GET", "/fapi/v1/positionRisk", query);
        if (json == null) return List.of();

        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, AsterPosition.class));
        } catch (Exception e) {
            log.error("[Aster] Error parsing positions", e);
            return List.of();
        }
    }

    public String cancelAllOrders(String symbol) {
        String query = "symbol=" + symbol;
        log.info("[Aster] Cancelling all {} orders", symbol);

        try {
            return executeSignedRequest("POST", "/fapi/v1/allOpenOrders", query);
        } catch (Exception e) {
            log.error("[Aster] Cancel all failed", e);
            return null;
        }
    }

    //Optional
    public String getOpenOrders(String symbol) {
        String query = symbol != null ? "symbol=" + symbol : null;
        try {
            return executeSignedRequest("GET", "/fapi/v1/openOrders", query);
        } catch (Exception e) {
            log.error("[Aster] Get open orders failed", e);
            return null;
        }
    }

    public String closePosition(String symbol) throws InterruptedException {
        //Setting thread sleep for both Extended and Aster positions close at the same time
        Thread.sleep(3000);
        try {
            //Getting positions
            List<AsterPosition> positions = getPositions(symbol);

            if (positions == null || positions.isEmpty()) {
                log.info("[Aster] No positions for {}", symbol);
                return "Position already closed";
            }

            //Choosing active position
            AsterPosition targetPos = null;
            for (AsterPosition pos : positions) {
                double amt = Double.parseDouble(pos.getPositionAmt());
                if (Math.abs(amt) > 1e-8) {
                    targetPos = pos;
                    break;
                }
            }

            if (targetPos == null) {
                log.info("[Aster] All positions for {} empty", symbol);
                return "Position already closed";
            }

            String positionSide = targetPos.getPositionSide();
            double amt = Double.parseDouble(targetPos.getPositionAmt());
            String closeSide = amt > 0 ? "SELL" : "BUY";
            String qty = String.format(Locale.US, "%.3f", Math.abs(amt));

            //Sending marker close for the position
            StringBuilder params = new StringBuilder();
            params.append("symbol=").append(symbol);
            params.append("&side=").append(closeSide);
            params.append("&positionSide=").append(positionSide);
            params.append("&type=MARKET");
            params.append("&quantity=").append(qty);

            log.info("[Aster] Closing in Hedge Mode: symbol={}, side={}, positionSide={}, qty={}",
                    symbol, closeSide, positionSide, qty);

            String result = executeSignedRequest("POST", "/fapi/v1/order", params.toString());

            //Waiting till closing
            log.info("[Aster] Close order sent, validating position closure...");

            boolean closed = waitPositionClosed(symbol, positionSide, 10.0);

            if (closed) {
                log.info("[Aster] Position closed: {} {}", symbol, positionSide);
            } else {
                log.warn("[Aster] Position close Timeout");
            }

            return result;

        } catch (Exception e) {
            log.error("[Aster] Error closing position for {}", symbol, e);
            return null;
        }
    }

    private boolean waitPositionClosed(String symbol, String positionSide, double timeoutSec) {
        long start = System.currentTimeMillis();
        long pollInterval = 300; // 300ms

        while ((System.currentTimeMillis() - start) / 1000.0 < timeoutSec) {
            try {
                List<AsterPosition> positions = getPositions(symbol);

                if (positions == null || positions.isEmpty()) {
                    log.info("[Aster] waitPositionClosed: {} disappeared → CLOSED", symbol);
                    return true;
                }

                boolean found = false;
                for (AsterPosition p : positions) {
                    if (p.getPositionSide().equals(positionSide)) {
                        double amt = Math.abs(Double.parseDouble(p.getPositionAmt()));

                        if (amt < 0.001) {
                            log.info("[Aster] waitPositionClosed: {} {} size={} → CLOSED",
                                    symbol, positionSide, amt);
                            return true;
                        }

                        log.debug("[Aster] waitPositionClosed: {} {} size={} still open",
                                symbol, positionSide, amt);
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    log.info("[Aster] waitPositionClosed: {} {} not found → CLOSED", symbol, positionSide);
                    return true;
                }

                Thread.sleep(pollInterval);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.warn("[Aster] waitPositionClosed exception: {}", e.getMessage());
                try {
                    Thread.sleep(pollInterval);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        log.warn("[Aster] waitPositionClosed TIMEOUT after {}s", timeoutSec);
        return false;
    }

    private String handleResponse(CloseableHttpResponse resp) throws Exception {
        int code = resp.getCode();
        String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
        if (code >= 200 && code < 300) {
            return body;
        }
        log.error("[Aster] API Error: code={}, body={}", code, body);
        throw new RuntimeException("[Aster] API error: " + code + " → " + body);
    }

    public int getMaxLeverage(String symbol) {
        try {
            log.info("[Aster] Getting max leverage for {}", symbol);

            String queryParams = "symbol=" + symbol;
            String response = executeSignedRequest("GET", "/fapi/v1/leverageBracket", queryParams);

            if (response == null || response.isEmpty()) {
                log.warn("[Aster] Empty response for leverage info, using default mode parameter or 10");
                return 10;
            }

            log.debug("[Aster] Leverage response: {}", response);

            Map<String, Object> data = objectMapper.readValue(response, Map.class);
            List<Map<String, Object>> brackets = (List<Map<String, Object>>) data.get("brackets");

            if (brackets != null && !brackets.isEmpty()) {
                Map<String, Object> firstBracket = brackets.get(0);
                Integer maxLeverage = (Integer) firstBracket.get("initialLeverage");

                log.info("[Aster] Max leverage for {}: {}x", symbol, maxLeverage);
                return maxLeverage;
            }

            log.warn("[Aster] No brackets found for {}, using using default mode parameter or 10", symbol);
            return 10;

        } catch (Exception e) {
            log.error("[Aster] Failed to get max leverage for {}, using default mode parameter or 10", symbol, e);
            return 10;
        }
    }

//    //Funding rate info for ticker
//    public long getMinutesUntilFunding(String symbol) {
//        try {
//            URIBuilder builder = new URIBuilder(baseUrl + "/fapi/v1/premiumIndex");
//            builder.addParameter("symbol", symbol);
//            URI uri = builder.build();
//
//            HttpGet get = new HttpGet(uri);
//            log.info("[Aster] GET next funding time for {}", symbol);
//
//            try (CloseableHttpResponse resp = httpClient.execute(get)) {
//                int code = resp.getCode();
//                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
//
//                if (code != 200) {
//                    log.error("[Aster] Premium index failed: code={}, body={}", code, body);
//                    return -1;
//                }
//
//                JsonNode root = objectMapper.readTree(body);
//                long nextFundingTime = root.path("nextFundingTime").asLong(0L);
//
//                if (nextFundingTime == 0) {
//                    log.error("[Aster] nextFundingTime is 0 for {}", symbol);
//                    return -1;
//                }
//
//                long now = System.currentTimeMillis();
//                long minutesUntil = (nextFundingTime - now) / (1000 * 60);
//
//                log.info("[Aster] Next funding for {} in {} minutes (at {})",
//                        symbol,
//                        minutesUntil,
//                        new java.util.Date(nextFundingTime));
//
//                return minutesUntil;
//            }
//        } catch (Exception e) {
//            log.error("[Aster] Error getting funding time for {}", symbol, e);
//            return -1;
//        }
//    }

    public PremiumIndexResponse getPremiumIndexInfo(String symbol) {
        try {
            URIBuilder builder = new URIBuilder(baseUrl + "/fapi/v1/premiumIndex");
            builder.addParameter("symbol", symbol);
            URI uri = builder.build();

            HttpGet get = new HttpGet(uri);
            log.info("[Aster] GET premium index info for {}", symbol);

            try (CloseableHttpResponse resp = httpClient.execute(get)) {
                int code = resp.getCode();
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

                if (code != 200) {
                    log.error("[Aster] Premium index failed: code={}, body={}", code, body);
                    return null;
                }

                PremiumIndexResponse response = objectMapper.readValue(body, PremiumIndexResponse.class);

                log.info("[Aster] Premium index for {}: fundingRate={}%, nextFunding in {} min, markPrice={}",
                        symbol,
                        response.getLastFundingRateAsDouble() * 100,
                        response.getMinutesUntilFunding(),
                        response.getMarkPrice());

                return response;
            }
        } catch (Exception e) {
            log.error("[Aster] Error getting premium index for {}", symbol, e);
            return null;
        }
    }

    public long getMinutesUntilFunding(String symbol) {
        PremiumIndexResponse info = getPremiumIndexInfo(symbol);
        if (info == null) {
            log.error("[Aster] Failed to get premium index for {}", symbol);
            return -1;
        }
        return info.getMinutesUntilFunding();
    }

    public double getCurrentFundingRate(String symbol) {
        PremiumIndexResponse info = getPremiumIndexInfo(symbol);
        if (info == null) {
            log.error("[Aster] Failed to get premium index for {}", symbol);
            return 0.0;
        }
        return info.getLastFundingRateAsDouble();
    }
}