package ru.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import ru.dto.funding.PositionPnLData;

@Getter
@AllArgsConstructor
public class PnLThresholdEvent {
    private final String positionId;
    private final String ticker;
    private final PositionPnLData pnlData;
    private final double thresholdPercent;
    private final double marginUsed;
    private final String mode;
}