package ru.mapper.extended;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.dto.exchanges.ExchangeType;
import ru.dto.exchanges.OrderBook;
import ru.dto.exchanges.PriceLevel;
import ru.dto.exchanges.extended.ExtendedOrderBook;
import ru.dto.exchanges.extended.OrderBookLevel;

import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@Component
public class ExtendedOrderBookMapper {

    public static OrderBook toOrderBook(ExtendedOrderBook extBook, String symbol) {
        if (extBook == null) {
            return null;
        }

        try {
            //Get best bid/ask from Extended format
            Double bestBid = null;
            Double bestBidSize = null;
            if (extBook.getBid() != null && !extBook.getBid().isEmpty()) {
                OrderBookLevel topBid = extBook.getBid().getFirst();
                bestBid = Double.parseDouble(topBid.getPrice());
                bestBidSize = Double.parseDouble(topBid.getQty());
            }

            Double bestAsk = null;
            Double bestAskSize = null;
            if (extBook.getAsk() != null && !extBook.getAsk().isEmpty()) {
                OrderBookLevel topAsk = extBook.getAsk().getFirst();
                bestAsk = Double.parseDouble(topAsk.getPrice());
                bestAskSize = Double.parseDouble(topAsk.getQty());
            }

            // Map full depth (optional)
            List<PriceLevel> bids = null;
            if (extBook.getBid() != null) {
                bids = extBook.getBid().stream()
                        .map(b -> new PriceLevel(
                                Double.parseDouble(b.getPrice()),
                                Double.parseDouble(b.getQty())
                        ))
                        .toList();
            }

            List<PriceLevel> asks = null;
            if (extBook.getAsk() != null) {
                asks = extBook.getAsk().stream()
                        .map(a -> new PriceLevel(
                                Double.parseDouble(a.getPrice()),
                                Double.parseDouble(a.getQty())
                        ))
                        .toList();
            }

            OrderBook orderBook = OrderBook.builder()
                    .exchange(ExchangeType.EXTENDED)
                    .symbol(symbol)
                    .timestamp(System.currentTimeMillis())
                    .bestBid(bestBid)
                    .bestAsk(bestAsk)
                    .bestBidSize(bestBidSize)
                    .bestAskSize(bestAskSize)
                    .asks(asks)
                    .bids(bids)
                    .build();

            log.debug("[ExtendedMapper] Mapped order book for {}: bid={}, ask={}, spread={}%",
                    symbol,
                    bestBid != null ? String.format("%.4f", bestBid) : "null",
                    bestAsk != null ? String.format("%.4f", bestAsk) : "null",
                    orderBook.isValid() ? String.format("%.4f", orderBook.getSpreadPercent()) : "N/A");

            return orderBook;

        } catch (Exception e) {
            log.error("[ExtendedMapper] Failed to map order book for {}: {}",
                    symbol, e.getMessage(), e);
            return null;
        }
    }
}
