package ru.dto.exchanges;

import lombok.Getter;
import lombok.AllArgsConstructor;

import java.util.UUID;

@Getter
@AllArgsConstructor
public class PositionClosedEvent {
    private final UUID positionId;
    private final String ticker;
    private final double pnl;
    private final boolean success;
}