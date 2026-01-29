package ru.dto.exchanges.extended;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ExtendedBalanceDto {
    private String status;
    private BalanceData data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BalanceData {
        @JsonProperty("collateralName")
        private String collateralName;

        @JsonProperty("balance")
        private String balance;

        @JsonProperty("status")
        private String status;

        @JsonProperty("equity")
        private String equity;

        @JsonProperty("spotEquity")
        private String spotEquity;

        @JsonProperty("spotEquityForAvailableForTrade")
        private String spotEquityForAvailableForTrade;

        @JsonProperty("availableForTrade")
        private String availableForTrade;

        @JsonProperty("availableForWithdrawal")
        private String availableForWithdrawal;

        @JsonProperty("unrealisedPnl")
        private String unrealisedPnl;

        @JsonProperty("initialMargin")
        private String initialMargin;

        @JsonProperty("marginRatio")
        private String marginRatio;

        @JsonProperty("exposure")
        private String exposure;

        @JsonProperty("leverage")
        private String leverage;

        @JsonProperty("updatedTime")
        private Long updatedTime;
    }
}
