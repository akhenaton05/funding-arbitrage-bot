package ru.dto.exchanges;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Position {
    private ExchangeType exchange;
    private String symbol;
    private Direction side;
    private double size;
    private double entryPrice;
    private double markPrice;
    private double unrealizedPnl;
}
