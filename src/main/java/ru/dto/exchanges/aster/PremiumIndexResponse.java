package ru.dto.exchanges.aster;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PremiumIndexResponse {
    private String symbol;
    
    @JsonProperty("markPrice")
    private String markPrice;
    
    @JsonProperty("indexPrice")
    private String indexPrice;
    
    @JsonProperty("lastFundingRate")
    private String lastFundingRate;
    
    @JsonProperty("nextFundingTime")
    private long nextFundingTime;
    
    @JsonProperty("interestRate")
    private String interestRate;
    
    private long time;

    public double getLastFundingRateAsDouble() {
        try {
            return Double.parseDouble(lastFundingRate);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
    
    public double getMarkPriceAsDouble() {
        try {
            return Double.parseDouble(markPrice);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
    
    public long getMinutesUntilFunding() {
        long now = System.currentTimeMillis();
        return (nextFundingTime - now) / (1000 * 60);
    }
}