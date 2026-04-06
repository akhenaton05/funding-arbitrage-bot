package ru.dto.funding;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class PositionPnLData {
    private String positionId;
    private String ticker;
    private LocalDateTime openTime;
    private PositionPriceSnapshot firstSnapshot;
    private PositionPriceSnapshot secondSnapshot;
    private double totalOpenFees;
    private double totalCloseFees;
    private double firstFundingNet;
    private double secondFundingNet;
    private double firstUnrealizedPnl;
    private double secondUnrealizedPnl;
    private double totalFundingNet;
    private double grossPnl;
    private double netPnl;

    public void calculateTotals() {
        this.totalFundingNet = firstFundingNet + secondFundingNet;
        this.grossPnl = secondUnrealizedPnl + firstUnrealizedPnl;
        this.netPnl = grossPnl - totalOpenFees - totalCloseFees + totalFundingNet;
    }

    public double getEntrySpreadPct() {
        return calculateSpread(
                firstSnapshot.getEntryPrice(),
                secondSnapshot.getEntryPrice()
        );
    }

    public double getExitSpreadPct() {
        return calculateSpread(
                firstSnapshot.getExitPrice(),
                secondSnapshot.getExitPrice()
        );
    }

    private double calculateSpread(double firstPrice, double secondPrice) {
        if (firstPrice <= 0 || secondPrice <= 0) return 0.0;
        return Math.abs(firstPrice - secondPrice) / Math.min(firstPrice, secondPrice) * 100;
    }
}
