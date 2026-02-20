package ru.dto.funding;

import lombok.Builder;
import lombok.Data;
import ru.dto.exchanges.Direction;
import ru.dto.exchanges.ExchangePosition;
import ru.dto.exchanges.ExchangeType;

@Data
@Builder
public class FundingOpenSignal {
    private String ticker;
    private ExchangePosition firstPosition;
    private ExchangePosition secondPosition;
    private String action;
    private HoldingMode mode;
    private int leverage;
    private double rate;
}
