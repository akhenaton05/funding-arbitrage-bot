package ru.mapper.lighter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.dto.exchanges.ExchangeType;
import ru.dto.exchanges.OrderBook;
import ru.dto.exchanges.PriceLevel;
import ru.dto.exchanges.aster.AsterBookTicker;
import ru.dto.exchanges.lighter.LighterOrderBook;
import ru.dto.exchanges.lighter.LighterOrderBookResponse;

import java.util.List;

@Slf4j
@Component
public class LighterOrderBookMapper {

    public static OrderBook toOrderBook(LighterOrderBookResponse ticker, String symbol) {
        if (ticker == null) {
            return null;
        }
        
        try {
            double bestBid = Double.parseDouble(ticker.getBids().getFirst().getPrice());
            double bestAsk = Double.parseDouble(ticker.getAsks().getFirst().getPrice());
            double bestBidSize = Double.parseDouble(ticker.getBids().getFirst().getSize());
            double bestAskSize = Double.parseDouble(ticker.getAsks().getFirst().getSize());
            
            return OrderBook.builder()
                    .exchange(ExchangeType.LIGHTER)
                    .symbol(symbol)
                    .timestamp(System.currentTimeMillis())
                    .bestBid(bestBid)
                    .bestAsk(bestAsk)
                    .bestBidSize(bestBidSize)
                    .bestAskSize(bestAskSize)
                    .bids(List.of(new PriceLevel(bestBid, bestBidSize)))
                    .asks(List.of(new PriceLevel(bestAsk, bestAskSize)))
                    .build();
                    
        } catch (Exception e) {
            log.error("[Lighter] Failed to map order book: {}", e.getMessage(), e);
            return null;
        }
    }
}
