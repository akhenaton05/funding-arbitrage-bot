package ru.event;

import lombok.Builder;
import lombok.Data;
import ru.dto.funding.PositionPnLData;

@Data
@Builder
public class PositionNotificationEvent {
    private final String positionId;
    private final String ticker;
    private final String message;
}
