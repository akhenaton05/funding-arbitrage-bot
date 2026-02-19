package ru.exchanges;

import ru.dto.exchanges.*;
import ru.dto.funding.FundingCloseSignal;
import ru.dto.funding.PositionPnLData;

import java.util.List;

public interface Exchange {

    ExchangeType getType();

    String getName();

    String formatSymbol(String ticker);

    Balance getBalance();

//    double getMarkPrice(String symbol);

    OrderBook getOrderBook(String symbol);
//
//    BidAsk getBidAsk(String symbol);
//
//    MarketInfo getMarketInfo(String symbol);

    List<Position> getPositions(String symbol, Direction side);

    boolean hasPosition(String symbol, Direction side);
//    /**
//     * Open position with fixed margin
//     * @param symbol - market symbol
//     * @param marginUsd - margin in USD
//     * @param leverage - leverage
//     * @param direction - LONG or SHORT
//     * @return OrderResult with order ID
//     */
//    OrderResult openPosition(String symbol, double marginUsd, int leverage, Direction direction);

//    /**
//     * Open position with fixed size
//     * @param symbol - market symbol
//     * @param size - size in base asset
//     * @param direction - LONG or SHORT
//     * @return OrderResult with order ID
//     */
//    OrderResult openPositionWithSize(String symbol, double size, Direction direction);

    OrderResult closePosition(String symbol, Direction currentSide);

    /**
     * Set leverage for market
     */
    String setLeverage(String symbol, int leverage);

    /**
     * Get maximum leverage for symbol
     */
    int getMaxLeverage(String symbol);

    // ============================================
    // FUNDING
    // ============================================

    /**
     * Get funding rate
     */
    double getFundingRate(String symbol);

    /**
     * Get minutes until next funding
     */
    long getMinutesUntilFunding(String symbol);

    /**
     * Get funding history
     */
    FundingHistory getFundingHistory(String symbol, Direction side, long fromTimeMs, int limit);

    // ============================================
    // FEES
    // ============================================

    /**
     * Get maker fee
     */
    double getMakerFee();

    /**
     * Get taker fee
     */
    double getTakerFee();

    Double calculateMaxSizeForMargin(String market, double marginUsd, int leverage, boolean isBuy);

    String openPositionWithSize(String market, double size, String direction);

    double calculateFunding(String ticker, Direction direction, FundingCloseSignal signal, Double prevFunding);

    default int getOpenDelay(ExchangeType pairedWith) {
        return 0;
    }

    default int getCloseDelay(ExchangeType pairedWith) {
        return 0;
    }

    void setPairedExchange(ExchangeType pairedWith);

    String placeStopLoss(String symbol, Direction direction, double stopPrice);

    String placeTakeProfit(String symbol, Direction direction, double tpPrice);

    void cancelAllOrders(String symbol);

    boolean supportsSlTp();
}
