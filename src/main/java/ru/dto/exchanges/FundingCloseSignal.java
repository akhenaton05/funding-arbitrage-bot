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
    private Direction extDirection;
    private Direction astDirection;
    private String asterOrderId;
    private String extendedOrderId;
    private double openedFundingRate;
    private double currentFindingRate;
    //Smart Mode
    private String action;
    private HoldingMode mode;
    private long openedAtMs;
    private double openSpread;
    private int badStreak;
}
