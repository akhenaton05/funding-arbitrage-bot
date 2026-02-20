package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LighterMarketOrderResponse {
    private String status;
    
    @JsonProperty("client_order_index")
    private Long clientOrderIndex;
    
    @JsonProperty("tx_hash")
    private String txHash;
    
    private Integer code;
    private String message;
    
    private String market;
    @JsonProperty("market_id")
    private Integer marketId;
    
    private String side;
    private String size;
    private String price;
}
