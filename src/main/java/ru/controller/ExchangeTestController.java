package ru.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.client.aster.AsterClient;
import ru.client.extended.ExtendedClient;
import ru.dto.exchanges.aster.AsterTrade;
import ru.dto.exchanges.extended.ExtendedPositionHistory;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class ExchangeTestController {

    private final AsterClient asterClient;
    private final ExtendedClient extendedClient;

    // GET /test/aster/pnl?symbol=BTCUSDT&orderId=123456789
    @GetMapping("/aster/pnl")
    public AsterTrade testAsterPnl(
            @RequestParam(value = "symbol") String symbol,
            @RequestParam(value = "orderId", required = false) Long orderId
    ) {
        log.info("Test Aster getRealizedPnl: symbol={}, orderId={}", symbol, orderId);

        AsterTrade pnl = asterClient.getTradeResultByOrderId(symbol, orderId);

        System.out.println(pnl);

        return pnl;
    }

    // GET /test/extended/position/history?market=4-USD&side=LONG
    @GetMapping("/extended/position/history")
    public ResponseEntity<?> testExtendedPositionHistory(
            @RequestParam(value = "market") String market,
            @RequestParam(value = "side", defaultValue = "LONG") String side
    ) {
        log.info("Test Extended getLastClosedPosition: market={}, side={}", market, side);

        ExtendedPositionHistory position = extendedClient.getLastClosedPosition(market, side);

        if (position == null) {
            return ResponseEntity.ok(Map.of(
                    "status", "NOT_FOUND",
                    "market", market,
                    "side", side
            ));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "OK");
        response.put("market", position.getMarket());
        response.put("side", position.getSide());
        response.put("realisedPnl", position.getRealisedPnl());
        response.put("realisedPnlBreakdown", position.getRealisedPnlBreakdown());
        response.put("openPrice", position.getOpenPrice());
        response.put("exitPrice", position.getExitPrice());
        response.put("size", position.getSize());
        response.put("leverage", position.getLeverage());
        response.put("closedTime", position.getClosedTime());
        response.put("createdTime", position.getCreatedTime());

        return ResponseEntity.ok(response);
    }
//
//    @GetMapping("/test/aster/notional")
//    public ResponseEntity<?> testAsterNotional(
//            @RequestParam(value = "market") String symbol,
//            @RequestParam(value = "leverage", defaultValue = "3") int leverage) {
//
//        log.info("[Test] Checking Aster notional limits: symbol={}, leverage={}", symbol, leverage);
//
//        Map<String, Object> result = new LinkedHashMap<>();
//        result.put("symbol", symbol);
//        result.put("timestamp", Instant.now().toString());
//
//        // Проверяем несколько leverage для сравнения
//        for (int lev : new int[]{1, 2, 3, 5, 10, 20}) {
//            try {
//                Map<String, Object> limits = asterClient.checkNotionalLimits(symbol, lev);
//                result.put("leverage_" + lev + "x", limits);
//                Thread.sleep(200); // чтобы не спамить API
//            } catch (Exception e) {
//                result.put("leverage_" + lev + "x_error", e.getMessage());
//            }
//        }
//
//        return ResponseEntity.ok(result);
//    }
//
//    @GetMapping("/test/aster/position-risk")
//    public ResponseEntity<String> testAsterPositionRisk(@RequestParam(value = "market") String symbol) {
//        log.info("[Test] Raw positionRisk for symbol={}", symbol);
//
//        String json = asterClient.getRawPositionRisk(symbol);
//
//        if (json == null) {
//            return ResponseEntity.ok("{\"error\": \"No response\"}");
//        }
//
//        return ResponseEntity.ok()
//                .header("Content-Type", "application/json")
//                .body(json);
//    }

    @PostMapping("/test/aster/leverage")
    public ResponseEntity<String> testAsterSetLeverage(
            @RequestParam("symbol") String symbol,
            @RequestParam("leverage") int leverage) {

        log.info("[Test] Setting Aster leverage: symbol={}, leverage={}", symbol, leverage);

        try {
            asterClient.setLeverage(symbol, leverage);
            return ResponseEntity.ok("{\"status\": \"ok\", \"symbol\": \"" + symbol + "\", \"leverage\": " + leverage + "}");
        } catch (Exception e) {
            return ResponseEntity.ok("{\"status\": \"error\", \"message\": \"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }




}
