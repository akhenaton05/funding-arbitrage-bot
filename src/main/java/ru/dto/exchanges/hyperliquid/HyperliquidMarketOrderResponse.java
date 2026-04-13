package ru.dto.exchanges.hyperliquid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HyperliquidMarketOrderResponse {
    private String status;
    private String market;
    private String side;
    private String size;
    private String message;

    @JsonProperty("avg_px")
    private Double avgPx;

    @JsonProperty("order_id")
    private String orderId;

    @JsonProperty("total_sz")
    private Double totalSz;
}