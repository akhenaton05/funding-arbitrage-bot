package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FundingPayment {
    private String market;
    private String side;
    private String size;
    
    @JsonProperty("accumulated_funding")
    private String accumulatedFunding;
    
    @JsonProperty("last_funding_time")
    private Long lastFundingTime;
}
