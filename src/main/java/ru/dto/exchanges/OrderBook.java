package ru.dto.exchanges;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OrderBook {
    private ExchangeType exchange;
    private String symbol;
    private Long timestamp;
    private Double bestBid;
    private Double bestAsk;
    private Double bestBidSize;
    private Double bestAskSize;
    private List<PriceLevel> bids;
    private List<PriceLevel> asks;
    
    /**
     * Calculate spread in absolute terms
     */
    public double getSpread() {
        if (bestAsk == null || bestBid == null) {
            return 0.0;
        }
        return bestAsk - bestBid;
    }
    
    /**
     * Calculate spread in percentage
     */
    public double getSpreadPercent() {
        if (bestAsk == null || bestBid == null || bestBid == 0) {
            return 0.0;
        }
        return ((bestAsk - bestBid) / bestBid) * 100;
    }
    
    /**
     * Get mid price
     */
    public double getMidPrice() {
        if (bestAsk == null || bestBid == null) {
            return 0.0;
        }
        return (bestAsk + bestBid) / 2.0;
    }
    
    /**
     * Check if order book is valid
     */
    public boolean isValid() {
        return bestBid != null && bestAsk != null && bestBid > 0 && bestAsk > 0;
    }
}
