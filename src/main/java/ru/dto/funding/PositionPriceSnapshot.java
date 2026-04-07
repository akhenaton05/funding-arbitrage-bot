package ru.dto.funding;

import lombok.Builder;
import lombok.Data;
import ru.dto.exchanges.ExchangeType;

@Data
@Builder
public class PositionPriceSnapshot {
    private ExchangeType exchangeType;
    private double entryPrice;
    private double markPrice;
    private double liquidationPrice;
    private double exitPrice;
}
