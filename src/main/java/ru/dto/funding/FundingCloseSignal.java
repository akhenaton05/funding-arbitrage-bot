package ru.dto.funding;

import lombok.Builder;
import lombok.Data;
import ru.dto.exchanges.Direction;
import ru.dto.exchanges.ExchangePosition;
import ru.exchanges.Exchange;

@Data
@Builder
public class FundingCloseSignal {
    private String id;
    private String ticker;
    private double balance;
    private ExchangePosition firstPosition;
    private ExchangePosition secondPosition;
    private Exchange firstExchange;
    private Exchange secondExchange;
    private double openedFundingRate;
    private double currentFindingRate;
    //Smart Mode
    private String action;
    private HoldingMode mode;
    private long openedAtMs;
    private double openSpread;
    private int badStreak;
}
