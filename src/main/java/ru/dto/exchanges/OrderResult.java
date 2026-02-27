package ru.dto.exchanges;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderResult {
    private ExchangeType exchange;
    private String symbol;
    private boolean success;
    private String orderId;
    private String message;
    private String errorCode;
    private Long timestamp;
    private Double realizedPnl;
}
