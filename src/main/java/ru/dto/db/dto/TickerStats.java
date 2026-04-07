package ru.dto.db.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.util.List;

@Data
@Builder
public class TickerStats {
    private String ticker;
    private int tradeCount;
    private double totalPnl;
    private double winRate;
    private double totalFunding;
}

