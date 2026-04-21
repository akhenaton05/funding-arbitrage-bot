package ru.mapper.hyperliquid;

import ru.dto.exchanges.*;
import ru.dto.exchanges.hyperliquid.HyperliquidOrderBookResponse;
import ru.dto.exchanges.hyperliquid.HyperliquidPosition;

import java.util.ArrayList;
import java.util.List;

public class HyperliquidOrderBookMapper {
    public static OrderBook toOrderBook(HyperliquidOrderBookResponse dto, String symbol) {
        if (dto == null) return null;

        HyperliquidOrderBookResponse.Summary s = dto.getSummary();

        List<PriceLevel> bids = new ArrayList<>();
        List<PriceLevel> asks = new ArrayList<>();

        if (dto.getBids() != null) {
            for (HyperliquidOrderBookResponse.PriceLevel b : dto.getBids()) {
                bids.add(PriceLevel.builder()
                        .price(parseDouble(b.getPrice()))
                        .size(parseDouble(b.getSize()))
                        .build());
            }
        }
        if (dto.getAsks() != null) {
            for (HyperliquidOrderBookResponse.PriceLevel a : dto.getAsks()) {
                asks.add(PriceLevel.builder()
                        .price(parseDouble(a.getPrice()))
                        .size(parseDouble(a.getSize()))
                        .build());
            }
        }

        OrderBook.OrderBookBuilder builder = OrderBook.builder()
                .exchange(ExchangeType.HYPERLIQUID)
                .symbol(symbol)
                .bids(bids)
                .asks(asks);

        if (s != null) {
            builder
                    .bestBid(s.getBestBid())
                    .bestAsk(s.getBestAsk())
                    .bestBidSize(s.getBestBidSize())
                    .bestAskSize(s.getBestAskSize())
                    .timestamp(s.getTimestamp());
        } else {
            if (!bids.isEmpty()) builder.bestBid(bids.getFirst().getPrice());
            if (!asks.isEmpty()) builder.bestAsk(asks.getFirst().getPrice());
            if (!bids.isEmpty()) builder.bestBidSize(bids.getFirst().getSize());
            if (!asks.isEmpty()) builder.bestAskSize(asks.getFirst().getSize());
            builder.timestamp(System.currentTimeMillis());
        }

        return builder.build();
    }

    private static double parseDouble(String val) {
        try { return val != null ? Double.parseDouble(val) : 0.0; }
        catch (NumberFormatException e) { return 0.0; }
    }
}