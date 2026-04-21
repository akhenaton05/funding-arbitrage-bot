package ru.dto.exchanges.hyperliquid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HyperliquidLeverageResponse {
    private String status;
    private String message;
    private String market;
    private Integer leverage;
    @JsonProperty("is_cross") private Boolean isCross;
}