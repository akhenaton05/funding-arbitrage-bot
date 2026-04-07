package ru.dto.exchanges;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PositionRiskControl {
    private double entryPrice;
    private double markPrice;
    private double liquidationPrice;
}
