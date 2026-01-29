package ru.dto.exchanges.aster;

import lombok.Data;

@Data
public class MarketOrderRequest {
    private String symbol;
    private String side;
    private String quantity;
    private String positionSide;
}

