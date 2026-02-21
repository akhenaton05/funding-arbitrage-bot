package ru.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.client.lighter.LighterClient;
import ru.dto.exchanges.lighter.*;
import ru.exchanges.Lighter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/test/lighter")
@RequiredArgsConstructor
public class LighterTestController {

    private final LighterClient lighterClient;
    private final Lighter lighterExchange;

    /**
     * Health Check
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        log.info("[Test] Health check");

        Map<String, Object> response = new HashMap<>();
        response.put("status", "OK");
        response.put("exchange", "Lighter");
        response.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(response);
    }

    /**
     * Get Markets
     */
    @GetMapping("/markets")
    public ResponseEntity<Map<String, Object>> testMarkets() {
        log.info("[Test] Getting markets");

        Map<String, Object> response = new HashMap<>();

        try {
            List<LighterMarket> markets = lighterClient.getMarkets();

            response.put("status", "success");
            response.put("count", markets.size());
            response.put("markets", markets.stream()
                    .limit(10)
                    .map(m -> Map.of(
                            "symbol", m.getSymbol(),
                            "market_id", m.getMarketId(),
                            "size_decimals", m.getSizeDecimals(),
                            "price_decimals", m.getPriceDecimals()
                    ))
                    .toList()
            );

            log.info("[Test] Markets: {} total", markets.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Test] Markets failed", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Balance
     */
    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> testBalance() {
        log.info("[Test] Getting balance");

        Map<String, Object> response = new HashMap<>();

        try {
            Double availableBalance = lighterClient.getBalance();
            Double equity = lighterClient.getEquity();
            Double marginUsed = lighterClient.getMarginUsed();

            response.put("available_balance", availableBalance);
            response.put("equity", equity);
            response.put("margin_used", marginUsed);
            response.put("status", "success");

            log.info("[Test] Balance: available=${}, equity=${}, margin=${}",
                    availableBalance, equity, marginUsed);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Test] Balance failed", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Get Positions
     */
    @GetMapping("/positions")
    public ResponseEntity<Map<String, Object>> testPositions(
            @RequestParam(value = "market", required = false) String market,
            @RequestParam(value = "side", required = false) String side) {

        log.info("[Test] Getting positions: market={}, side={}", market, side);

        Map<String, Object> response = new HashMap<>();

        try {
            List<LighterPosition> positions = lighterClient.getPositions(market, side);

            response.put("count", positions.size());
            response.put("status", "success");

            if (!positions.isEmpty()) {
                response.put("positions", positions.stream()
                        .map(p -> Map.of(
                                "market", p.getMarket(),
                                "side", p.getSide(),
                                "size", p.getSize(),
                                "open_price", p.getOpenPrice(),
                                "unrealised_pnl", p.getUnrealisedPnl(),
                                "margin", p.getMargin(),
                                "leverage", p.getLeverage()
                        ))
                        .toList()
                );
            }

            log.info("[Test] Positions: {} found", positions.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Test] Positions failed", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Get Funding
     */
    @GetMapping("/funding/{market}")
    public ResponseEntity<Map<String, Object>> testFunding(
            @PathVariable("market") String market) {

        log.info("[Test] Getting funding for {}", market);

        Map<String, Object> response = new HashMap<>();

        try {
            Double accumulatedFunding = lighterClient.getAccumulatedFunding(market);

            response.put("accumulated_funding", accumulatedFunding);
            response.put("status", "success");

            log.info("[Test] Funding: ${}", accumulatedFunding);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Test] Funding failed", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Set Leverage
     */
    @PostMapping("/leverage")
    public ResponseEntity<Map<String, Object>> testSetLeverage(
            @RequestParam("market") String market,
            @RequestParam("leverage") int leverage) {

        log.info("[Test] Setting leverage: market={}, leverage={}x", market, leverage);

        Map<String, Object> response = new HashMap<>();

        try {
            String result = lighterClient.setLeverage(market, leverage);

            response.put("result", result);
            response.put("market", market);
            response.put("leverage", leverage);
            response.put("status", result != null ? "success" : "error");

            log.info("[Test] Leverage set: {}", result);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Test] Set leverage failed", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Open Position (by size)
     */
    @PostMapping("/open")
    public ResponseEntity<Map<String, Object>> testOpenPosition(
            @RequestParam("market") String market,
            @RequestParam("side") String side,
            @RequestParam("size") double size) {

        log.info("[Test] Opening position: market={}, side={}, size={}", market, side, size);

        Map<String, Object> response = new HashMap<>();

        try {
            String txHash = lighterClient.openPositionWithSize(market, size, side);

            if (txHash != null) {
                response.put("status", "success");
                response.put("tx_hash", txHash);
                response.put("market", market);
                response.put("side", side);
                response.put("size", size);

                log.info("[Test] Position opened: tx={}", txHash);
            } else {
                response.put("status", "error");
                response.put("message", "Failed to open position");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Test] Open position failed", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Close Position
     */
    @PostMapping("/close")
    public ResponseEntity<Map<String, Object>> testClosePosition(
            @RequestParam("market") String market,
            @RequestParam("currentSide") String currentSide) {

        log.info("[Test] Closing position: market={}, currentSide={}", market, currentSide);

        Map<String, Object> response = new HashMap<>();

        try {
            LighterClosePositionResponse txHash = lighterClient.closePosition(market, currentSide);

            log.info("Response from closing {}", txHash);

            if (txHash != null) {
                response.put("status", "success");
                response.put("tx_hash", txHash);
                response.put("market", market);
                response.put("current_side", currentSide);

                log.info("[Test] Position closed: tx={}", txHash);
            } else {
                response.put("status", "error");
                response.put("message", "Failed to close position");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Test] Close position failed", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Full Trading Flow Test
     */
    @PostMapping("/full-flow")
    public ResponseEntity<Map<String, Object>> testFullTradingFlow(
            @RequestParam("market") String market,
            @RequestParam("size") double size,
            @RequestParam("leverage") int leverage) {

        log.info("[Test] Full flow: market={}, size={}, leverage={}x", market, size, leverage);

        Map<String, Object> response = new HashMap<>();
        Map<String, Object> steps = new HashMap<>();

        try {
            // Step 1: Check balance
            log.info("[Test] Step 1: Checking balance");
            Double balance = lighterClient.getBalance();
            steps.put("1_balance", Map.of(
                    "status", balance != null && balance > 0 ? "✅" : "❌",
                    "balance", balance != null ? balance : 0.0
            ));

            Thread.sleep(1000);

            // Step 2: Set leverage
            log.info("[Test] Step 2: Setting leverage to {}x", leverage);
            String leverageResult = lighterClient.setLeverage(market, leverage);
            steps.put("2_leverage", Map.of(
                    "status", leverageResult != null ? "✅" : "❌",
                    "result", leverageResult != null ? leverageResult : "failed"
            ));

            Thread.sleep(1000);

            // Step 3: Open LONG position
            log.info("[Test] Step 3: Opening LONG position size={}", size);
            String openTx = lighterClient.openPositionWithSize(market, size, "LONG");
            steps.put("3_open", Map.of(
                    "status", openTx != null ? "✅" : "❌",
                    "tx_hash", openTx != null ? openTx : "failed",
                    "side", "LONG",
                    "size", size
            ));

            if (openTx == null) {
                response.put("status", "error");
                response.put("message", "Failed to open position");
                response.put("steps", steps);
                return ResponseEntity.status(500).body(response);
            }

            Thread.sleep(4000);

            // Step 4: Check position
            log.info("[Test] Step 4: Checking position");
            List<LighterPosition> positions = lighterClient.getPositions(market, "LONG");
            boolean hasPosition = positions != null && !positions.isEmpty();

            steps.put("4_check", Map.of(
                    "status", hasPosition ? "✅" : "❌",
                    "positions_count", positions != null ? positions.size() : 0
            ));

            if (hasPosition) {
                LighterPosition pos = positions.get(0);
                steps.put("4_position_details", Map.of(
                        "market", pos.getMarket(),
                        "side", pos.getSide(),
                        "size", pos.getSize(),
                        "open_price", pos.getOpenPrice(),
                        "unrealised_pnl", pos.getUnrealisedPnl()
                ));
            }

            Thread.sleep(2000);

            // Step 5: Close position
            log.info("[Test] Step 5: Closing position");
            LighterClosePositionResponse closeTx = lighterClient.closePosition(market, "LONG");
            steps.put("5_close", Map.of(
                    "status", closeTx != null ? "✅" : "❌",
                    "tx_hash", closeTx != null ? closeTx : "failed"
            ));

            Thread.sleep(4000);

            // Step 6: Verify closed
            log.info("[Test] Step 6: Verifying closed");
            List<LighterPosition> positionsAfter = lighterClient.getPositions(market, "LONG");
            boolean isClosed = positionsAfter == null || positionsAfter.isEmpty();

            steps.put("6_verify", Map.of(
                    "status", isClosed ? "✅" : "⚠️",
                    "positions_count", positionsAfter != null ? positionsAfter.size() : 0
            ));

            response.put("status", "success");
            response.put("steps", steps);
            response.put("market", market);
            response.put("size", size);
            response.put("leverage", leverage);

            log.info("[Test] Full flow completed");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Test] Full flow failed", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
            response.put("steps", steps);
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Quick Integration Test
     */
    @GetMapping("/quick-test/{market}")
    public ResponseEntity<Map<String, Object>> quickTest(
            @PathVariable("market") String market) {

        log.info("[Test] Quick test for {}", market);

        Map<String, Object> response = new HashMap<>();
        Map<String, Object> results = new HashMap<>();

        // Test 1: Balance
        try {
            Double balance = lighterClient.getBalance();
            results.put("balance", Map.of(
                    "status", balance != null && balance >= 0 ? "✅" : "❌",
                    "value", balance != null ? balance : 0.0
            ));
        } catch (Exception e) {
            results.put("balance", Map.of("status", "❌", "error", e.getMessage()));
        }

        // Test 2: Markets
        try {
            List<LighterMarket> markets = lighterClient.getMarkets();
            results.put("markets", Map.of(
                    "status", markets != null && !markets.isEmpty() ? "✅" : "❌",
                    "count", markets != null ? markets.size() : 0
            ));
        } catch (Exception e) {
            results.put("markets", Map.of("status", "❌", "error", e.getMessage()));
        }

        // Test 3: Positions
        try {
            List<LighterPosition> positions = lighterClient.getPositions(null, null);
            results.put("positions", Map.of(
                    "status", "✅",
                    "count", positions != null ? positions.size() : 0
            ));
        } catch (Exception e) {
            results.put("positions", Map.of("status", "❌", "error", e.getMessage()));
        }

        // Test 4: Funding
        try {
            Double funding = lighterClient.getAccumulatedFunding(market);
            results.put("funding", Map.of(
                    "status", funding != null ? "✅" : "❌",
                    "value", funding != null ? funding : 0.0
            ));
        } catch (Exception e) {
            results.put("funding", Map.of("status", "❌", "error", e.getMessage()));
        }

        response.put("market", market);
        response.put("timestamp", System.currentTimeMillis());
        response.put("results", results);

        long passCount = results.values().stream()
                .filter(r -> r instanceof Map && "✅".equals(((Map<?, ?>) r).get("status")))
                .count();

        response.put("summary", Map.of(
                "total", results.size(),
                "passed", passCount,
                "failed", results.size() - passCount
        ));

        log.info("[Test] Quick test: {}/{} passed", passCount, results.size());
        return ResponseEntity.ok(response);
    }

    /**
     * Test OrderBook
     */
    @GetMapping("/orderbook/{market}")
    public ResponseEntity<Map<String, Object>> testOrderBook(
            @PathVariable("market") String market,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {

        log.info("[Test] Getting orderbook for {}, limit={}", market, limit);

        Map<String, Object> response = new HashMap<>();

        try {
            LighterOrderBookResponse orderbook = lighterClient.getOrderBook(market, limit);

            if (orderbook == null) {
                response.put("status", "error");
                response.put("message", "Failed to get orderbook");
                return ResponseEntity.status(500).body(response);
            }

            response.put("status", "success");
            response.put("market", orderbook.getMarket());
            response.put("summary", orderbook.getSummary());
            response.put("bids", orderbook.getBids().stream().limit(5).toList());
            response.put("asks", orderbook.getAsks().stream().limit(5).toList());

            log.info("[Test] OrderBook: {} bids, {} asks",
                    orderbook.getSummary().getBidsCount(),
                    orderbook.getSummary().getAsksCount());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Test] OrderBook failed", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Test Best Prices
     */
    @GetMapping("/best-prices/{market}")
    public ResponseEntity<Map<String, Object>> testBestPrices(
            @PathVariable("market") String market) {

        log.info("[Test] Getting best prices for {}", market);

        Map<String, Object> response = new HashMap<>();

        try {
            LighterBestPricesResponse prices = lighterClient.getBestPrices(market);

            if (prices == null) {
                response.put("status", "error");
                response.put("message", "Failed to get best prices");
                return ResponseEntity.status(500).body(response);
            }

            response.put("status", "success");
            response.put("market", prices.getMarket());
            response.put("best_bid", prices.getBestBid());
            response.put("best_ask", prices.getBestAsk());
            response.put("mid_price", prices.getMidPrice());
            response.put("spread", prices.getSpread());
            response.put("spread_pct", prices.getSpreadPct());

            log.info("[Test] Best prices: bid=${}, ask=${}",
                    prices.getBestBid(), prices.getBestAsk());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Test] Best prices failed", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Test Market Depth
     */
    @GetMapping("/depth/{market}")
    public ResponseEntity<Map<String, Object>> testMarketDepth(
            @PathVariable("market") String market,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {

        log.info("[Test] Getting market depth for {}, limit={}", market, limit);

        Map<String, Object> response = new HashMap<>();

        try {
            LighterDepthResponse depth = lighterClient.getMarketDepth(market, limit);

            if (depth == null) {
                response.put("status", "error");
                response.put("message", "Failed to get market depth");
                return ResponseEntity.status(500).body(response);
            }

            response.put("status", "success");
            response.put("market", depth.getMarket());
            response.put("mid_price", depth.getMidPrice());
            response.put("bids_total_value", depth.getBids().getTotalValue());
            response.put("asks_total_value", depth.getAsks().getTotalValue());
            response.put("bid_ask_ratio", depth.getImbalance().getBidAskRatio());
            response.put("size_imbalance", depth.getImbalance().getSizeImbalance());

            // Первые 5 уровней bid/ask
            response.put("top_bids", depth.getBids().getLevels().stream().limit(5).toList());
            response.put("top_asks", depth.getAsks().getLevels().stream().limit(5).toList());

            log.info("[Test] Depth: bid=${}, ask=${}, ratio={}",
                    depth.getBids().getTotalValue(),
                    depth.getAsks().getTotalValue(),
                    depth.getImbalance().getBidAskRatio());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Test] Market depth failed", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

}
