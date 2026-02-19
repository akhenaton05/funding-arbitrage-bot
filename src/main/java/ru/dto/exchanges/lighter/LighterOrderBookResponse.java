package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
public class LighterOrderBookResponse {
    private String status;
    private String market;
    
    @JsonProperty("market_id")
    private Integer marketId;
    
    private List<OrderBookLevel> bids;
    private List<OrderBookLevel> asks;
    private OrderBookSummary summary;
    private String timestamp;
}


