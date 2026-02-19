package ru.dto.exchanges.lighter;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
public class LighterBestPricesResponse {
    private String status;
    private String market;

    @JsonProperty("best_bid")
    private Double bestBid;

    @JsonProperty("best_ask")
    private Double bestAsk;

    @JsonProperty("mid_price")
    private Double midPrice;

    private Double spread;

    @JsonProperty("spread_pct")
    private Double spreadPct;
}

