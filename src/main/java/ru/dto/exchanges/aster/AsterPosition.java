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
    @JsonProperty("markPrice")
    private String markPrice;
    @JsonProperty("unRealizedProfit")
    private String unrealizedProfit;
    @JsonProperty("maxNotionalValue")
    private String maxNotionalValue;

    public double getMaxNotionalValue() {
        try {
            return maxNotionalValue != null ? Double.parseDouble(maxNotionalValue) : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}