package ru.dto.exchanges.hyperliquid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HyperliquidClosedPnlResponse {
    private String status;

    @JsonProperty("data")
    private ClosedPnlData data;
}