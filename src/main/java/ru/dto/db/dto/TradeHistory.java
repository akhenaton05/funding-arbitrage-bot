package ru.dto.db.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.dto.db.dto.TickerStats;

import java.time.Duration;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeHistory {

    // Мета
    private int totalTrades;
    private int periodDays;
    private double totalVolume;

    // P&L
    private double totalPnl;
    private double pnlToVolumePercent;   // <-- добавить
    private double avgPnlPerTrade;       // <-- добавить
    private double bestTrade;
    private double worstTrade;

    // Эффективность
    private int wins;
    private int losses;
    private double winRate;
    private int currentStreak;           // <-- добавить

    // Фандинг
    private double totalFunding;         // <-- добавить
    private double fundingToPnlPercent;  // <-- добавить
    private double avgOpenRate;          // <-- добавить
    private double avgCloseRate;         // <-- добавить
    private double avgRateDelta;         // <-- добавить

    // Удержание
    private Duration avgHoldTime;        // <-- добавить
    private Duration maxHoldTime;        // <-- добавить
    private String   maxHoldTicker;      // <-- добавить
    private Duration minHoldTime;        // <-- добавить
    private String   minHoldTicker;      // <-- добавить

    // Тикеры
    private List<TickerStats> tickerStats; // <-- добавить
}
