package ru.dto.exchanges;

import lombok.Getter;
import lombok.AllArgsConstructor;

@Getter
@AllArgsConstructor
public class PositionClosedEvent {
    private final String ticker;
    private final double pnl;
    private final boolean success;
}