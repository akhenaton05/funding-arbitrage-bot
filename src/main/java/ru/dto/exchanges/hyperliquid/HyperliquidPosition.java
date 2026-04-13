package ru.dto.exchanges.hyperliquid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HyperliquidPosition {
    private String market;
    private String side;
    private String size;
    @JsonProperty("open_price")
    private String openPrice;
    @JsonProperty("mark_price")
    private String markPrice;
    @JsonProperty("unrealised_pnl")
    private String unrealisedPnl;
    @JsonProperty("realized_pnl")
    private String realizedPnl;
    private String margin;
    @JsonProperty("liquidation_price")
    private String liquidationPrice;
    private String leverage;
    @JsonProperty("funding_paid")
    private String fundingPaid;
}