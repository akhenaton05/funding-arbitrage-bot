package ru.dto.exchanges.extended;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class AsyncOrderResponse {
    private String status;
    
    @JsonProperty("external_id")
    private String externalId;
    
    private String market;
    private String side;
    private String size;
    private String type;
    
    @JsonProperty("slippage_pct")
    private String slippagePct;
    
    @JsonProperty("close_side")
    private String closeSide;
    
    @JsonProperty("price_offset_pct")
    private String priceOffsetPct;
}
