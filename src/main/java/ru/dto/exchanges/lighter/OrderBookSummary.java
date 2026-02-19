package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class OrderBookSummary {
    @JsonProperty("best_bid")
    private Double bestBid;

    @JsonProperty("best_ask")
    private Double bestAsk;

    @JsonProperty("mid_price")
    private Double midPrice;

    private Double spread;

    @JsonProperty("spread_bps")
    private Double spreadBps;

    @JsonProperty("bids_count")
    private Integer bidsCount;

    @JsonProperty("asks_count")
    private Integer asksCount;
}