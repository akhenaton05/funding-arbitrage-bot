package ru.dto.exchanges.hyperliquid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;


@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HyperliquidBalanceDto {
    private String status;
    private BalanceData data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BalanceData {
        private String total;
        @JsonProperty("available_for_trade")
        private String availableForTrade;
        @JsonProperty("margin_used")
        private String marginUsed;
        private String equity;
    }
}