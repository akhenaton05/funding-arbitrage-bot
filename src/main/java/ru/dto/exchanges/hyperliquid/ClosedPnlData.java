package ru.dto.exchanges.hyperliquid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClosedPnlData {
    private String coin;
    private String side;

    @JsonProperty("close_price")
    private Double closePrice;

    private Double size;

    @JsonProperty("closed_pnl")
    private Double closedPnl;

    private Double fee;

    @JsonProperty("net_pnl")
    private Double netPnl;

    @JsonProperty("time_ms")
    private Long timeMs;

    private String time;
}