package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class LighterCalculateSizeResponse {
    private String status;
    private String market;
    
    @JsonProperty("margin_usd")
    private Double marginUsd;
    
    private Integer leverage;
    private Double price;
    
    @JsonProperty("max_size")
    private Double maxSize;
    
    @JsonProperty("position_value")
    private Double positionValue;
    
    private String side;
}
