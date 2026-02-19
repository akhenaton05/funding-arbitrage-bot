package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class DepthImbalance {
    @JsonProperty("bid_ask_ratio")
    private Double bidAskRatio;

    @JsonProperty("size_imbalance")
    private Double sizeImbalance;
}
