package ru.dto.exchanges;

import lombok.Builder;
import lombok.Getter;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PositionClosedEvent {
    private String positionId;
    private String ticker;
    private double pnl;
    private double apiPnl;
    private double percent;
    private boolean success;
    private String mode;
    private String closeInfo;
    private double rate;
    private String closureReason;
}