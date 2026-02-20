package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LighterClosePositionResponse {
    private String status;           // "success" / "submitted"
    private String message;
    
    private String market;
    private String size;
    private String side;             // Close side: SELL for LONG
    
    @JsonProperty("tx_hash")
    private String txHash;
    
    private String note;             // Optional guidance
}
