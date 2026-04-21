package ru.dto.spread;

import lombok.Builder;
import lombok.Data;
import ru.dto.exchanges.Direction;
import ru.exchanges.Exchange;

@Data
@Builder
public class SpreadEvent {
    private String ticker;
    private Exchange firstExchange;
    private Exchange secondExchange;
    private Direction firstDirection;
    private Direction secondDirection;
    private double firstPrice;
    private double secondPrice;
    private double spread;
}
