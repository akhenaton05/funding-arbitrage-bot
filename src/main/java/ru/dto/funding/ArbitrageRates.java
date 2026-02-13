package ru.dto.funding;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import ru.dto.exchanges.Direction;
import ru.dto.exchanges.ExchangeType;

@Data
@Builder
@AllArgsConstructor
public class ArbitrageRates {
    private String symbol;
    private double arbitrageRate;
    private ExchangeType firstExchange;
    private ExchangeType secondExchange;
    private double firstRate;
    private double secondRate;
    private String action;  //"SHORT Extended, LONG Binance"

    //Lower rate → LONG (receive funding)
    //Higher rate → SHORT (pay funding)
    public Direction getFirstDirection() {
        return firstRate < secondRate ? Direction.LONG : Direction.SHORT;
    }

    public Direction getSecondDirection() {
        return firstRate < secondRate ? Direction.SHORT : Direction.LONG;
    }
}