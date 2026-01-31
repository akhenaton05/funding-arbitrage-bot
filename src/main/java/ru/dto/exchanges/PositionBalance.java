package ru.dto.exchanges;

import lombok.Data;

@Data
public class PositionBalance {
    private double balanceBefore;
    private double balanceAfter;

    public double getProfit() {
        return balanceAfter - balanceBefore;
    }

    public double getProfitPercent(double usedMargin) {
        return (getProfit() / usedMargin) * 100;
    }

    public boolean isClosed() {
        return balanceAfter > 0;
    }
}
