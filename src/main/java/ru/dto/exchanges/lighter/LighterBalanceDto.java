package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class LighterBalanceDto {
    private String status;
    private BalanceData data;

    @Data
    public static class BalanceData {
        private String total;

        @JsonProperty("available_for_trade")
        private String availableForTrade;

        @JsonProperty("margin_used")
        private String marginUsed;

        private String equity;

        public String getAvailableForTrade() {
            return availableForTrade != null ? availableForTrade : "0";
        }

        public String getMarginUsed() {
            return marginUsed != null ? marginUsed : "0";
        }

        public String getEquity() {
            return equity != null ? equity : "0";
        }
    }
}
