package ru.dto.exchanges.hyperliquid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HyperliquidClosePositionResponse {
    private String status;
    @JsonProperty("order_id")
    private String orderId;
    private String message;
    private String market;
    private String size;
    private String side;
    @JsonProperty("entry_price")
    private Double entryPrice;
    @JsonProperty("exit_price")
    private Double exitPrice;
}