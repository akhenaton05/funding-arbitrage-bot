package ru.dto.exchanges.hyperliquid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HyperliquidPositionsResponse {
    private String status;
    private List<HyperliquidPosition> data;
}