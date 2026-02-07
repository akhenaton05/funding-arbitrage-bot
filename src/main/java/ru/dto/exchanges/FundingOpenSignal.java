package ru.dto.exchanges;

import lombok.Data;
import ru.dto.funding.HoldingMode;

@Data
public class FundingOpenSignal {
    private String ticker;
    private Direction asterDirection;
    private Direction extendedDirection;
    private String action;
    private HoldingMode mode;
    private int leverage;
    private double rate;
}
