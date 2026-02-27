package ru.dto.exchanges.aster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AsterTrade {
    private Long orderId;
    private String symbol;
    private String side;
    private String positionSide;
    private String price;
    private String qty;
    private String quoteQty;
    private String commission;
    private String commissionAsset;
    @JsonProperty("realizedPnl")
    private String realizedPnl;
    private Long time;
    private Boolean maker;
    private Boolean buyer;
}
