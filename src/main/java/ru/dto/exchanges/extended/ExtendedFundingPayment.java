package ru.dto.exchanges.extended;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ExtendedFundingPayment {
    private long id;
    
    @JsonProperty("accountId")
    private long accountId;
    
    private String market;
    
    @JsonProperty("positionId")
    private long positionId;
    
    private String side;
    
    private String size;
    
    private String value;
    
    @JsonProperty("markPrice")
    private String markPrice;
    
    @JsonProperty("fundingFee")
    private String fundingFee;  //Positive = got, negative = paid
    
    @JsonProperty("fundingRate")
    private String fundingRate;
    
    @JsonProperty("paidTime")
    private long paidTime;

    public double getFundingFeeAsDouble() {
        try {
            return Double.parseDouble(fundingFee);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
    
    public double getFundingRateAsDouble() {
        try {
            return Double.parseDouble(fundingRate);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}