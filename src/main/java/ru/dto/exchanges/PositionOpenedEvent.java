package ru.dto.exchanges;

import lombok.Builder;
import lombok.Getter;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import ru.dto.funding.PositionPnLData;
import ru.dto.funding.PositionPriceSnapshot;

import java.util.UUID;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PositionOpenedEvent {
    private String positionId;
    private String ticker;
    private String result;
    private PositionPnLData data;
    private double balanceUsed;
    private String firstDirection;
    private String secondDirection;
    private String mode;
    private boolean success;
    private double rate;
}