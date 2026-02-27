package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LighterMarket {
    private String symbol;
    
    @JsonProperty("market_id")
    private Integer marketId;
    
    @JsonProperty("size_decimals")
    private Integer sizeDecimals;
    
    @JsonProperty("price_decimals")
    private Integer priceDecimals;
    
    @JsonProperty("min_size")
    private String minSize;
    
    @JsonProperty("tick_size")
    private String tickSize;
}
