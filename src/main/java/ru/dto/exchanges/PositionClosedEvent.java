package ru.dto.exchanges;

import lombok.Getter;
import lombok.AllArgsConstructor;

import java.util.UUID;

@Getter
@AllArgsConstructor
public class PositionClosedEvent {
    private final String positionId;
    private final String ticker;
    private final double pnl;
    private final double percent;
    private final boolean success;
    private final String mode;
}