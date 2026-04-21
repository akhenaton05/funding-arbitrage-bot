package ru.dto.exchanges.hyperliquid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;


@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HyperliquidCalculateSizeResponse {
    private String status;
    private String market;
    @JsonProperty("max_size")
    private Double maxSize;
    private Double price;
    @JsonProperty("position_value")
    private Double positionValue;
    private Integer leverage;
}