package ru.dto.exchanges;

import lombok.Getter;
import lombok.AllArgsConstructor;

@Getter
@AllArgsConstructor
public class PositionOpenedEvent {
    private final String ticker;
    private final String result;
    private final double balanceUsed;
    private final String extDirection;
    private final String astDirection;
}