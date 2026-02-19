package ru.dto.exchanges.lighter;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;


@Data
public class LighterDepthResponse {
    private String status;
    private String market;

    @JsonProperty("mid_price")
    private Double midPrice;

    private DepthSide bids;
    private DepthSide asks;
    private DepthImbalance imbalance;
}








