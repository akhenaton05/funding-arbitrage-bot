package ru.dto.exchanges.extended;

import lombok.Data;

@Data
public class ExtendedPosition {
    private long id;
    private String market;
    private String side;
    private String size;
    private String leverage;
    private String openPrice;
    private String markPrice;
    private String unrealisedPnl;
    private String liquidationPrice;
}
