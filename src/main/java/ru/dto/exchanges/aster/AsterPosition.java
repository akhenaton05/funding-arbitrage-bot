package ru.dto.exchanges.aster;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class AsterPosition {
    private String symbol;
    @JsonProperty("positionSide")
    private String positionSide;
    @JsonProperty("positionAmt")
    private String positionAmt;
    @JsonProperty("entryPrice")
    private String entryPrice;
    @JsonProperty("unRealizedProfit")
    private String unrealizedProfit;
}