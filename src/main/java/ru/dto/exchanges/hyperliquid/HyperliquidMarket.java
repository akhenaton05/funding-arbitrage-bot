package ru.dto.exchanges.hyperliquid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HyperliquidMarket {
    private String symbol;
    @JsonProperty("assetIndex")
    private int assetIndex;
    @JsonProperty("szDecimals")
    private int szDecimals;
    @JsonProperty("maxLeverage")
    private int maxLeverage;
}