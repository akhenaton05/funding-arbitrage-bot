package ru.dto.exchanges.extended;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class ExtendedFundingHistoryResponse {
    private String status;
    private List<ExtendedFundingPayment> data;
    private Pagination pagination;
    private FundingSummary summary;
    
    @Data
    public static class Pagination {
        private long cursor;
        private int count;
    }
    
    @Data
    public static class FundingSummary {
        @JsonProperty("total_received")
        private double totalReceived;
        
        @JsonProperty("total_paid")
        private double totalPaid;
        
        @JsonProperty("net_funding")
        private double netFunding;
        
        @JsonProperty("payments_count")
        private int paymentsCount;
    }
}
