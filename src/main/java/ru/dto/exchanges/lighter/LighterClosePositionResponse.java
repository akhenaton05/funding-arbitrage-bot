package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LighterClosePositionResponse {
    private String status;
    private String message;
    
    private String market;
    private String size;
    private String side;
    
    @JsonProperty("tx_hash")
    private String txHash;
    
    private String note;

    @JsonProperty("trade_pnl")
    private String tradePnl;
}
