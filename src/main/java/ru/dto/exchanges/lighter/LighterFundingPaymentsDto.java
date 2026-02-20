package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LighterFundingPaymentsDto {
    private String status;
    private FundingSummary summary;
    private List<FundingPayment> data;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FundingSummary {
        @JsonProperty("positions_count")
        private int positionsCount;
        
        @JsonProperty("total_funding")
        private String totalFunding;
    }
}

