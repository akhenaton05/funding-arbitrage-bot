package ru.event;

import lombok.Builder;
import lombok.Getter;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import ru.dto.funding.PositionPnLData;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PositionClosedEvent {
    private String positionId;
    private String ticker;
    private PositionPnLData data;
    private double pnl;
    private double apiPnl;
    private double percent;
    private boolean success;
    private String mode;
    private double rate;
    private String closureReason;
}