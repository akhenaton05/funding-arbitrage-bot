package ru.exchanges;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;
import ru.client.hyperliquid.HyperliquidClient;
import ru.dto.exchanges.*;
import ru.dto.exchanges.hyperliquid.ClosedPnlData;
import ru.dto.exchanges.hyperliquid.HyperliquidOrderBookResponse;
import ru.dto.exchanges.hyperliquid.HyperliquidPosition;
import ru.dto.funding.FundingCloseSignal;
import ru.exceptions.ClosingPositionException;
import ru.mapper.hyperliquid.HyperliquidOrderBookMapper;
import ru.mapper.hyperliquid.HyperliquidPositionMapper;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class Hyperliquid implements Exchange {

    private final HyperliquidClient hyperliquidClient;

    private static final double HL_TAKER_FEE = 0.00035; // 0.035% для обычных аккаунтов
    private static final double HL_MAKER_FEE = 0.0;

    private ExchangeType currentPairedExchange = null;

    @Override
    public ExchangeType getType() {
        return ExchangeType.HYPERLIQUID;
    }

    @Override
    public String getName() {
        return "Hyperliquid";
    }

    @Override
    public String formatSymbol(String ticker) {
        return ticker.toUpperCase();
    }

    @Override
    public void setPairedExchange(ExchangeType pairedWith) {
        this.currentPairedExchange = pairedWith;
    }

    @Override
    public int getOpenDelay(ExchangeType pairedWith) {
        return switch (pairedWith) {
            case ASTER    -> 0;   // Aster
            case EXTENDED -> 0;   // Extended
            case LIGHTER  -> 0;   // Lighter
            default       -> 0;
        };
    }

    @Override
    public int getCloseDelay(ExchangeType pairedWith) {
        return switch (pairedWith) {
            case ASTER    -> 0;
            case EXTENDED -> 0;
            case LIGHTER  -> 0;
            default       -> 0;
        };
    }

    @Override
    public Balance getBalance() {
        double balance = hyperliquidClient.getBalance();
        return Balance.builder()
                .balance(balance)
                .margin(balance * 0.85)
                .build();
    }

    @Override
    public OrderBook getOrderBook(String symbol) {
        try {
            HyperliquidOrderBookResponse orderBook = hyperliquidClient.getOrderBook(formatSymbol(symbol));
            return HyperliquidOrderBookMapper.toOrderBook(orderBook, symbol);
        } catch (Exception e) {
            log.error("[Hyperliquid] Error getting OrderBook for {}", symbol, e);
            return null;
        }
    }

    @Override
    public List<Position> getPositions(String symbol, Direction side) {
        List<HyperliquidPosition> raw = hyperliquidClient.getPositions(
                formatSymbol(symbol), side.toString()
        );
        List<Position> result = new ArrayList<>();
        for (HyperliquidPosition pos : raw) {
            result.add(HyperliquidPositionMapper.toPosition(pos));
        }
        return result;
    }

    @Override
    public String openPositionWithSize(String market, double size, String direction) {
        try {
            if (currentPairedExchange != null) {
                int delay = getOpenDelay(currentPairedExchange);
                if (delay > 0) {
                    log.info("[Hyperliquid] Waiting {}ms before opening (paired with {})", delay, currentPairedExchange);
                    Thread.sleep(delay);
                }
            }
            return hyperliquidClient.openPositionWithSize(formatSymbol(market), size, direction);
        } catch (InterruptedException e) {
            log.error("[Hyperliquid] Interrupted during open delay", e);
            Thread.currentThread().interrupt();
            throw new ClosingPositionException("[Hyperliquid] Interrupted during open");
        } catch (Exception e) {
            throw new ClosingPositionException("[Hyperliquid] Error opening position - " + e.getMessage());
        }
    }

    @Override
    public OrderResult closePosition(String symbol, Direction currentSide) {
        try {
            if (currentPairedExchange != null) {
                int delay = getCloseDelay(currentPairedExchange);
                if (delay > 0) {
                    log.info("[Hyperliquid] Waiting {}ms before closing (paired with {})", delay, currentPairedExchange);
                    Thread.sleep(delay);
                }
            }

            long closedAtMs = System.currentTimeMillis();
            OrderResult result = hyperliquidClient.closePositionWithResult(symbol, String.valueOf(currentSide));

            if (result.isSuccess()) {
                Thread.sleep(1500);
                ClosedPnlData closeData = hyperliquidClient.getClosedPnl(formatSymbol(symbol), closedAtMs);
                log.info("[Hyper] Closed pos data: {}", closeData);
                Double pnl = closeData.getClosedPnl();
                if (pnl != null) result.setRealizedPnl(pnl);
            }

            return result;
        } catch (InterruptedException e) {
            log.error("Hyperliquid Interrupted during close delay", e);
            Thread.currentThread().interrupt();
            throw new ClosingPositionException("Hyperliquid: Interrupted during close");
        } catch (ClosingPositionException e) {
            throw e;
        } catch (Exception e) {
            throw new ClosingPositionException("Hyperliquid: Error closing position - manual check required");
        }
    }

//    /** Оценочный PnL на основе entry/exit price (знак зависит от стороны). */
//    private Double estimatePnl(Direction side, double entryPrice, double size, Double exitPrice) {
//        if (exitPrice == null || exitPrice == 0 || entryPrice == 0) return null;
//        double raw = side == Direction.LONG
//                ? (exitPrice - entryPrice) * size
//                : (entryPrice - exitPrice) * size;
//        double fees = (entryPrice + exitPrice) * size * HL_TAKER_FEE;
//        return raw - fees;
//    }

    @Override
    public String setLeverage(String symbol, int leverage) {
        return hyperliquidClient.setLeverage(formatSymbol(symbol), leverage);
    }

    @Override
    public int getMaxLeverage(String symbol, int leverage) {
        return hyperliquidClient.getMaxLeverage(formatSymbol(symbol));
    }

    @Override
    public double getFundingRate(String symbol) {
        return hyperliquidClient.getFundingRate(formatSymbol(symbol));
    }

    @Override
    public long getMinutesUntilFunding(String symbol) {
        return 0;
    }

    @Override
    public FundingHistory getFundingHistory(String symbol, Direction side, long fromTimeMs, int limit) {
        return null;
    }

    @Override
    public double calculateFunding(String ticker, Direction direction, FundingCloseSignal signal, Double prevFunding) {
        try {
            Thread.sleep(15000);
        } catch (InterruptedException e) {
            throw new IllegalArgumentException(e);
        }

        String formattedSymbol = formatSymbol(ticker);

        // 1. Реальные выплаты из истории (самый точный способ)
        double accumulated = hyperliquidClient.getAccumulatedFunding(formattedSymbol, signal.getOpenedAtMs());
        if (accumulated != 0.0) {
            log.info("Hyperliquid Funding for {}/{}: real accumulated={} since openedAt={}",
                    ticker, direction, String.format("%.6f", accumulated), signal.getOpenedAtMs());
            return accumulated + (prevFunding != null ? prevFunding : 0.0);
        }

        // 2. Fallback: rate × positionValue (если история пустая — позиция только открылась)
        try {
            List<Position> positions = getPositions(ticker, direction);
            if (!positions.isEmpty()) {
                double positionValue = positions.getFirst().getSize();
                double fundingRate   = hyperliquidClient.getFundingRate(formattedSymbol) / 100.0; // % → decimal
                boolean isLong       = direction == Direction.LONG;
                // При лонге и положительном rate — платим фандинг (negative pnl)
                double fundingPnl    = isLong ? -positionValue * fundingRate : positionValue * fundingRate;
                log.info("Hyperliquid Funding for {}/{}: rate={} posValue={} estimatedPnl={}",
                        ticker, direction,
                        String.format("%.6f", fundingRate),
                        String.format("%.2f", positionValue),
                        String.format("%.4f", fundingPnl));
                return fundingPnl + (prevFunding != null ? prevFunding : 0.0);
            }
        } catch (Exception e) {
            log.warn("Hyperliquid Fallback funding calc failed for {}: {}", ticker, e.getMessage());
        }

        log.warn("Hyperliquid No funding data for {}", ticker);
        return prevFunding != null ? prevFunding : 0.0;
    }

    @Override
    public double getMakerFee() {
        return HL_MAKER_FEE;
    }

    @Override
    public double getTakerFee() {
        return HL_TAKER_FEE;
    }

    @Override
    public Double calculateMaxSizeForMargin(String market, double marginUsd, int leverage, boolean isBuy) {
        return hyperliquidClient.calculateMaxSizeForMargin(formatSymbol(market), marginUsd, leverage, isBuy);
    }

    @Override
    public String placeStopLoss(String symbol, Direction direction, double stopPrice) {
        return "Hyperliquid SL not yet implemented";
    }

    @Override
    public String placeTakeProfit(String symbol, Direction direction, double tpPrice) {
        return "Hyperliquid TP not yet implemented";
    }

    @Override
    public boolean supportsSlTp() {
        return false; // TODO: реализовать через HL trigger orders
    }

    @Override
    public boolean isFundingTimeValid(String ticker) {
        return true;
    }

    // ─── Risk ─────────────────────────────────────────────────────────────────

//    @Override
//    public PositionRiskControl validatePositionRisk(String symbol, Direction direction) {
//        // HL возвращает liquidationPx в позиции, mark price берём из стакана
//        List<Position> positions = getPositions(symbol, direction);
//        log.info("Hyperliquid PositionRisk position: {}", positions);
//        double markPrice = hyperliquidClient.getMarkPrice(formatSymbol(symbol));
//        log.info("Hyperliquid Got liquidation price: {} and mark price: {}",
//                positions.getFirst().getLiquidationPrice(), markPrice);
//        return PositionRiskControl.builder()
//                .entryPrice(positions.getFirst().getEntryPrice())
//                .liquidationPrice(positions.getFirst().getLiquidationPrice())
//                .markPrice(markPrice)
//                .build();
//    }
}