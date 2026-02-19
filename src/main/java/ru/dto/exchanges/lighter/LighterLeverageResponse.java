package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LighterLeverageResponse {
    private String status;              // "success"
    private String message;
    
    private String market;
    @JsonProperty("market_id")
    private Integer marketId;
    
    private Integer leverage;           // 20
    
    @JsonProperty("initial_margin_fraction")
    private String initialMarginFraction;  // "5.00%"
    
    @JsonProperty("tx_hash")
    private String txHash;
}
