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
    private double astOpenPrice;
    private double extOpenPrice;
    private double totalOpenFees;
    private double totalCloseFees;
    private double extendedFundingNet;
    private double asterFundingNet;
    private double extUnrealizedPnl;
    private double asterUnrealizedPnl;
    private double totalFundingNet;
    private double grossPnl;
    private double netPnl;

    public void calculateTotals() {
        this.totalFundingNet = extendedFundingNet + asterFundingNet;
        this.grossPnl = extUnrealizedPnl + asterUnrealizedPnl;
        this.netPnl = grossPnl - totalOpenFees - totalCloseFees;
    }
}
