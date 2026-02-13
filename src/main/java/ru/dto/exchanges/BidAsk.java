package ru.dto.exchanges;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BidAsk {
    private ExchangeType exchange;
    private String symbol;
    private double bid;
    private double ask;
    
    public double getSpread() {
        return ask - bid;
    }
    
    public double getSpreadPercent() {
        return bid > 0 ? ((ask - bid) / bid) * 100 : 0.0;
    }
    
    public double getMidPrice() {
        return (bid + ask) / 2.0;
    }
}
