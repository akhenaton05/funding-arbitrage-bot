package ru.mapper.aster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.dto.exchanges.ExchangeType;
import ru.dto.exchanges.OrderBook;
import ru.dto.exchanges.PriceLevel;
import ru.dto.exchanges.aster.AsterBookTicker;

import java.util.List;

@Slf4j
@Component
public class AsterOrderBookMapper {

    public static OrderBook toOrderBook(AsterBookTicker ticker, String symbol) {
        if (ticker == null) {
            return null;
        }
        
        try {
            double bestBid = Double.parseDouble(ticker.getBidPrice());
            double bestAsk = Double.parseDouble(ticker.getAskPrice());
            double bestBidSize = Double.parseDouble(ticker.getBidQty());
            double bestAskSize = Double.parseDouble(ticker.getAskQty());
            
            return OrderBook.builder()
                    .exchange(ExchangeType.ASTER)
                    .symbol(symbol)
                    .timestamp(ticker.getTime())
                    .bestBid(bestBid)
                    .bestAsk(bestAsk)
                    .bestBidSize(bestBidSize)
                    .bestAskSize(bestAskSize)
                    .bids(List.of(new PriceLevel(bestBid, bestBidSize)))
                    .asks(List.of(new PriceLevel(bestAsk, bestAskSize)))
                    .build();
                    
        } catch (Exception e) {
            log.error("[AsterMapper] Failed to map order book: {}", e.getMessage(), e);
            return null;
        }
    }
}
