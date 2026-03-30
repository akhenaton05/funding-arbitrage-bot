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
public class PositionOpenedEvent {
    private String positionId;
    private String ticker;
    private String result;
    private String openInfo;
    private double balanceUsed;
    private String firstDirection;
    private String secondDirection;
    private String mode;
    private boolean success;
    private double rate;
}