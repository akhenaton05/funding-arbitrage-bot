package ru.dto.exchanges.hyperliquid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HyperliquidOrderBookResponse {
    private String status;
    private String market;
    private List<PriceLevel> bids;
    private List<PriceLevel> asks;
    private Summary summary;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PriceLevel {
        private String price;
        private String size;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Summary {
        @JsonProperty("best_bid")
        private Double bestBid;
        @JsonProperty("best_ask")
        private Double bestAsk;
        @JsonProperty("mid_price")
        private Double midPrice;
        private Double spread;
        @JsonProperty("spread_bps")
        private Double spreadBps;
        @JsonProperty("best_bid_size")
        private Double bestBidSize;
        @JsonProperty("best_ask_size")
        private Double bestAskSize;
        private Long timestamp;
        @JsonProperty("bids_count")
        private Integer bidsCount;
        @JsonProperty("asks_count")
        private Integer asksCount;
    }
}