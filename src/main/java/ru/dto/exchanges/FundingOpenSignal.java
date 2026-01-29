package ru.dto.exchanges;

import lombok.Data;

@Data
public class FundingOpenSignal {
    private String ticker;
    private Direction AsterDirection;
    private Direction ExtendedDirection;
}
