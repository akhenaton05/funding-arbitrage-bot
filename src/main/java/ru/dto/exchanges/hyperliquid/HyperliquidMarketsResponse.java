package ru.dto.exchanges.hyperliquid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HyperliquidMarketsResponse {
    private String status;
    private int count;
    private List<HyperliquidMarket> data;
}