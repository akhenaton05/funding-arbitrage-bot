package ru.dto.spread;

import lombok.Builder;
import lombok.Data;
import ru.exchanges.Exchange;

import java.time.LocalDateTime;

@Data
@Builder
public class SpreadPosition {
    private String symbol;
    private Exchange shortExchange;
    private Exchange longExchange;
    private double entrySpread;
    private double entryShortPrice;
    private double entryLongPrice;
    private LocalDateTime openedAt;
}
