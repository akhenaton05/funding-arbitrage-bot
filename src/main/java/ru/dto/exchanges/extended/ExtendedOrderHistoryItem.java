package ru.dto.exchanges.extended;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ExtendedOrderHistoryItem {
    @JsonProperty("accountId")
    private long accountId;

    @JsonProperty("averagePrice")
    private String averagePrice;

    @JsonProperty("cancelledQty")
    private String cancelledQty;

    @JsonProperty("createdTime")
    private long createdTime;

    @JsonProperty("expireTime")
    private long expireTime;

    @JsonProperty("externalId")
    private String externalId;

    @JsonProperty("filledQty")
    private String filledQty;

    @JsonProperty("id")
    private long id;

    @JsonProperty("market")
    private String market;

    @JsonProperty("payedFee")
    private String payedFee;

    @JsonProperty("postOnly")
    private boolean postOnly;

    @JsonProperty("price")
    private String price;

    @JsonProperty("qty")
    private String qty;

    @JsonProperty("reduceOnly")
    private boolean reduceOnly;

    @JsonProperty("side")
    private String side;

    @JsonProperty("status")
    private String status;

    @JsonProperty("timeInForce")
    private String timeInForce;

    private Twap twap;

    @JsonProperty("type")
    private String type;

    @JsonProperty("updatedTime")
    private long updatedTime;
}

