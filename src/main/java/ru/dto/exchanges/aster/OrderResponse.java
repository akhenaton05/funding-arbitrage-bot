package ru.dto.exchanges.aster;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderResponse {
    private Long orderId;
    private String clientOrderId;
    private String symbol;
    private String status;
    private String side;
    private String type;
    private String positionSide;

    private String executedQty;
    private String cumQty;
    private String cumQuote;
    private String avgPrice;

    private String origQty;
    private String price;

    private Long updateTime;
    private Long transactTime;

    private String stopPrice;
    private String workingType;
    private String timeInForce;
    private Boolean reduceOnly;
}
