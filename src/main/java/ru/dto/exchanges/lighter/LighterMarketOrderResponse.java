package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LighterMarketOrderResponse {
    private String status;           // "success"
    
    @JsonProperty("client_order_index")
    private Long clientOrderIndex;
    
    @JsonProperty("tx_hash")
    private String txHash;
    
    private Integer code;            // 200
    private String message;          // {"ratelimit": "..."}
    
    private String market;
    @JsonProperty("market_id")
    private Integer marketId;
    
    private String side;             // "BUY" / "SELL"
    private String size;             // "0.001"
    private String price;            // "$2700.00"
}
