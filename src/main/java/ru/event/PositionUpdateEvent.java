package ru.event;

import lombok.Builder;
import lombok.Data;
import ru.dto.funding.PositionPnLData;

@Data
@Builder
public class PositionUpdateEvent {
    private final String positionId;
    private final String ticker;
    private final PositionPnLData pnlData;
    private final String message;
    private final String mode;
}
