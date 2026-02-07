package ru.dto.exchanges;

import lombok.Getter;
import lombok.AllArgsConstructor;

import java.util.UUID;

@Getter
@AllArgsConstructor
public class PositionOpenedEvent {
    private final String positionId;
    private final String ticker;
    private final String result;
    private final double balanceUsed;
    private final String extDirection;
    private final String astDirection;
    private final String mode;
    private boolean success;
    private double rate;
}