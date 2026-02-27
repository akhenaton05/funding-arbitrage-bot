package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class LighterPosition {
    private String market;
    private String side;
    private String size;

    @JsonProperty("open_price")
    private String openPrice;

    @JsonProperty("mark_price")
    private String markPrice;

    @JsonProperty("unrealised_pnl")
    private String unrealisedPnl;

    @JsonProperty("realized_pnl")
    private String realizedPnl;

    private String margin;
    private String leverage;

    @JsonProperty("liquidation_price")
    private String liquidationPrice;

    @JsonProperty("position_value")
    private String positionValue;

    @JsonProperty("funding_paid")
    private String fundingPaid;

    public String getOpenPrice() {
        return openPrice != null ? openPrice : "0";
    }

    public String getMarkPrice() {
        return markPrice != null ? markPrice : "0";
    }

    public String getUnrealisedPnl() {
        return unrealisedPnl != null ? unrealisedPnl : "0";
    }

    public String getRealizedPnl() {
        return realizedPnl != null ? realizedPnl : "0";
    }

    public String getMargin() {
        return margin != null ? margin : "0";
    }

    public String getLiquidationPrice() {
        return liquidationPrice != null ? liquidationPrice : "0";
    }

    public String getPositionValue() {
        return positionValue != null ? positionValue : "0";
    }

    public String getFundingPaid() {
        return fundingPaid != null ? fundingPaid : "0";
    }
}
