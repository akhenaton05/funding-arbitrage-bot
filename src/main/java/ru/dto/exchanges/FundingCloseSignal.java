package ru.dto.exchanges;

import lombok.Builder;
import lombok.Data;
import ru.dto.funding.HoldingMode;

@Data
@Builder
public class FundingCloseSignal {
    private String id;
    private String ticker;
    private double balance;
    private Direction extDirection; //Extended asking for the current direction of the position
    private Direction astDirection;
    private String asterOrderId;
    private String extendedOrderId;
    //Smart Mode
    private String action;
    private HoldingMode mode;
    private long openedAtMs;
    private double openSpread;
    private int badStreak;
}
