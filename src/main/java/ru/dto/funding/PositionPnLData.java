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
    private double firstOpenPrice;
    private double secondOpenPrice;
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
}
