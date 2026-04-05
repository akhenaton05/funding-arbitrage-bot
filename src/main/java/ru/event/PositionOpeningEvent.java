package ru.event;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PositionOpeningEvent {
    private final String positionId;
    private final String ticker;
    private final String mode;
    private final double fundingRate;
}