package ru.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.client.hyperliquid.HyperliquidClient;
import ru.dto.exchanges.*;
import ru.exchanges.Hyperliquid;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/test/hyperliquid")
@RequiredArgsConstructor
public class HyperliquidTestController {

    private final Hyperliquid hyperliquid;
    private final HyperliquidClient hyperliquidClient;

    // ─── Market data ──────────────────────────────────────────────────────────

    /**
     * GET /test/hyperliquid/ping
     * Простой healthcheck — проверяет что прокси отвечает
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            double balance = hyperliquidClient.getBalance();
            result.put("status", "ok");
            result.put("balance", balance);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /test/hyperliquid/balance
     * Возвращает баланс аккаунта
     */
    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> balance() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Balance balance = hyperliquid.getBalance();
            result.put("status", "ok");
            result.put("balance", balance.getBalance());
            result.put("margin", balance.getMargin());
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /test/hyperliquid/orderbook?symbol=BTC
     * Стакан для символа
     */
    @GetMapping("/orderbook")
    public ResponseEntity<Map<String, Object>> orderBook(@RequestParam String symbol) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            OrderBook ob = hyperliquid.getOrderBook(symbol);
            if (ob == null) {
                result.put("status", "error");
                result.put("error", "null response");
                return ResponseEntity.ok(result);
            }
            result.put("status", "ok");
            result.put("symbol", ob.getSymbol());
            result.put("bestBid", ob.getBestBid());
            result.put("bestAsk", ob.getBestAsk());
            result.put("bestBidSize", ob.getBestBidSize());
            result.put("bestAskSize", ob.getBestAskSize());
            result.put("midPrice", ob.getMidPrice());
            result.put("spread", ob.getSpread());
            result.put("spreadPercent", String.format("%.4f%%", ob.getSpreadPercent()));
            result.put("bidsCount", ob.getBids() != null ? ob.getBids().size() : 0);
            result.put("asksCount", ob.getAsks() != null ? ob.getAsks().size() : 0);
            result.put("valid", ob.isValid());
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /test/hyperliquid/mark-price?symbol=BTC
     * Марк-прайс
     */
    @GetMapping("/mark-price")
    public ResponseEntity<Map<String, Object>> markPrice(@RequestParam String symbol) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            double price = hyperliquidClient.getMarkPrice(hyperliquid.formatSymbol(symbol));
            result.put("status", "ok");
            result.put("symbol", symbol);
            result.put("markPrice", price);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /test/hyperliquid/funding-rate?symbol=BTC
     * Текущий фандинг рейт (% за 1 час)
     */
    @GetMapping("/funding-rate")
    public ResponseEntity<Map<String, Object>> fundingRate(@RequestParam String symbol) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            double rate = hyperliquid.getFundingRate(symbol);
            long minutesUntil = hyperliquid.getMinutesUntilFunding(symbol);
            result.put("status", "ok");
            result.put("symbol", symbol);
            result.put("fundingRate", rate);
            result.put("fundingRateFormatted", String.format("%.6f%%", rate));
            result.put("minutesUntilFunding", minutesUntil);
            result.put("annualizedApprox", String.format("%.2f%%", rate * 24 * 365));
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /test/hyperliquid/max-leverage?symbol=BTC
     */
    @GetMapping("/max-leverage")
    public ResponseEntity<Map<String, Object>> maxLeverage(@RequestParam String symbol) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            int maxLev = hyperliquid.getMaxLeverage(symbol, 0);
            result.put("status", "ok");
            result.put("symbol", symbol);
            result.put("maxLeverage", maxLev);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /test/hyperliquid/positions?symbol=BTC&side=LONG
     */
    @GetMapping("/positions")
    public ResponseEntity<Map<String, Object>> positions(
            @RequestParam(name = "symbol") String symbol,
            @RequestParam(name = "side") String side) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Direction direction = Direction.valueOf(side.toUpperCase());
            List<Position> positions = hyperliquid.getPositions(symbol, direction);
            result.put("status", "ok");
            result.put("symbol", symbol);
            result.put("side", side);
            result.put("count", positions.size());
            result.put("positions", positions);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /test/hyperliquid/max-size?symbol=BTC&margin=100&leverage=5&isBuy=true
     * Сколько можно купить на указанный маржин
     */
    @GetMapping("/max-size")
    public ResponseEntity<Map<String, Object>> maxSize(
            @RequestParam String symbol,
            @RequestParam double margin,
            @RequestParam int leverage,
            @RequestParam(defaultValue = "true") boolean isBuy) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Double maxSize = hyperliquid.calculateMaxSizeForMargin(symbol, margin, leverage, isBuy);
            result.put("status", "ok");
            result.put("symbol", symbol);
            result.put("marginUsd", margin);
            result.put("leverage", leverage);
            result.put("isBuy", isBuy);
            result.put("maxSize", maxSize);
            result.put("notional", maxSize != null ? maxSize * hyperliquidClient.getMarkPrice(hyperliquid.formatSymbol(symbol)) : null);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    // ─── Trading (опасные эндпоинты) ─────────────────────────────────────────

    /**
     * POST /test/hyperliquid/set-leverage
     * Body: { "symbol": "BTC", "leverage": 5 }
     */
    @PostMapping("/set-leverage")
    public ResponseEntity<Map<String, Object>> setLeverage(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String symbol   = (String) body.get("symbol");
            int leverage    = ((Number) body.get("leverage")).intValue();
            String response = hyperliquid.setLeverage(symbol, leverage);
            result.put("status", "ok");
            result.put("symbol", symbol);
            result.put("leverage", leverage);
            result.put("response", response);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * POST /test/hyperliquid/open
     * Body: { "symbol": "BTC", "size": 0.001, "direction": "LONG" }
     *
     * ⚠️ РЕАЛЬНЫЙ ОРДЕР — использовать осторожно!
     */
    @PostMapping("/open")
    public ResponseEntity<Map<String, Object>> openPosition(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String symbol    = (String) body.get("symbol");
            double size      = ((Number) body.get("size")).doubleValue();
            String direction = (String) body.get("direction");
            log.warn("⚠️ HyperliquidTestController OPEN {} {} {}", direction, size, symbol);
            String orderId = hyperliquid.openPositionWithSize(symbol, size, direction);
            result.put("status", "ok");
            result.put("orderId", orderId);
            result.put("symbol", symbol);
            result.put("size", size);
            result.put("direction", direction);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * POST /test/hyperliquid/close
     * Body: { "symbol": "BTC", "side": "LONG" }
     *
     * ⚠️ РЕАЛЬНЫЙ ОРДЕР — закрывает позицию!
     */
    @PostMapping("/close")
    public ResponseEntity<Map<String, Object>> closePosition(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String symbol = (String) body.get("symbol");
            String side   = (String) body.get("side");
            log.warn("⚠️ HyperliquidTestController CLOSE {} {}", side, symbol);
            Direction direction = Direction.valueOf(side.toUpperCase());
            OrderResult orderResult = hyperliquid.closePosition(symbol, direction);
            result.put("status", "ok");
            result.put("success", orderResult.isSuccess());
            result.put("orderId", orderResult.getOrderId());
            result.put("realizedPnl", orderResult.getRealizedPnl());
            result.put("exitPrice", orderResult.getExitPrice());
            result.put("message", orderResult.getMessage());
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

//    /**
//     * GET /test/hyperliquid/risk?symbol=BTC&side=LONG
//     * Проверяет ликвидационную цену и mark price
//     */
//    @GetMapping("/risk")
//    public ResponseEntity<Map<String, Object>> positionRisk(
//            @RequestParam String symbol,
//            @RequestParam(defaultValue = "LONG") String side) {
//        Map<String, Object> result = new LinkedHashMap<>();
//        try {
//            Direction direction = Direction.valueOf(side.toUpperCase());
//            PositionRiskControl risk = hyperliquid.validatePositionRisk(symbol, direction);
//            result.put("status", "ok");
//            result.put("entryPrice", risk.getEntryPrice());
//            result.put("markPrice", risk.getMarkPrice());
//            result.put("liquidationPrice", risk.getLiquidationPrice());
//            // Расстояние до ликвидации в %
//            if (risk.getMarkPrice() != null && risk.getMarkPrice() > 0 && risk.getLiquidationPrice() != null) {
//                double distPct = Math.abs(risk.getMarkPrice() - risk.getLiquidationPrice())
//                        / risk.getMarkPrice() * 100;
//                result.put("liquidationDistancePct", String.format("%.2f%%", distPct));
//            }
//        } catch (Exception e) {
//            result.put("status", "error");
//            result.put("error", e.getMessage());
//        }
//        return ResponseEntity.ok(result);
//    }
}