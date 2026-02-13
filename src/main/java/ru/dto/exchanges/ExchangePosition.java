package ru.dto.exchanges;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangePosition {
    private ExchangeType exchange;
    private Direction direction;
    private String orderId;
}