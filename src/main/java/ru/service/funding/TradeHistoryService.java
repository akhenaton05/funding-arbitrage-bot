package ru.service.funding;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.dto.db.dto.TickerStats;
import ru.dto.db.dto.TradeHistory;
import ru.dto.db.model.Period;
import ru.dto.db.model.Trade;
import ru.repository.TradeRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TradeHistoryService {

    private final TradeRepository tradeRepository;

    private List<Trade> getTradesByPeriod(Period period) {
        LocalDateTime from = switch (period) {
            case DAY   -> LocalDateTime.now().minusDays(1);
            case WEEK  -> LocalDateTime.now().minusWeeks(1);
            case MONTH -> LocalDateTime.now().minusMonths(1);
            case ALL   -> LocalDateTime.of(2000, 1, 1, 0, 0);
        };
        return tradeRepository.findByClosedAtAfter(from);
    }

    public TradeHistory getStats(Period period) {
        List<Trade> trades = getTradesByPeriod(period);

        if (trades.isEmpty()) {
            return TradeHistory.builder()
                    .totalTrades(0)
                    .periodDays(getPeriodDays(period))
                    .build();
        }

        double totalPnl     = trades.stream().mapToDouble(Trade::getPnl).sum();
        double totalFunding = trades.stream().mapToDouble(Trade::getFunding).sum();
        double totalVolume  = trades.stream()
                .mapToDouble(t -> t.getVolume() != null ? t.getVolume() : 0).sum();

        int wins   = (int) trades.stream().filter(t -> t.getPnl() > 0).count();
        int losses = trades.size() - wins;

        double avgOpenRate  = trades.stream().mapToDouble(Trade::getOpenedFundingRate).average().orElse(0);
        double avgCloseRate = trades.stream().mapToDouble(Trade::getClosedFindingRate).average().orElse(0);

        Trade maxHoldTrade = trades.stream()
                .max(Comparator.comparingLong(t ->
                        Duration.between(t.getOpenedAt(), t.getClosedAt()).getSeconds()))
                .orElseThrow();
        Trade minHoldTrade = trades.stream()
                .min(Comparator.comparingLong(t ->
                        Duration.between(t.getOpenedAt(), t.getClosedAt()).getSeconds()))
                .orElseThrow();
        Duration avgHold = Duration.ofSeconds((long)
                trades.stream()
                        .mapToLong(t -> Duration.between(t.getOpenedAt(), t.getClosedAt()).getSeconds())
                        .average().orElse(0));

        List<TickerStats> tickerStats = trades.stream()
                .collect(Collectors.groupingBy(Trade::getTicker))
                .entrySet().stream()
                .map(e -> {
                    List<Trade> ts = e.getValue();
                    double pnl     = ts.stream().mapToDouble(Trade::getPnl).sum();
                    double funding = ts.stream().mapToDouble(Trade::getFunding).sum();
                    long   w       = ts.stream().filter(t -> t.getPnl() > 0).count();
                    return TickerStats.builder()
                            .ticker(e.getKey())
                            .tradeCount(ts.size())
                            .totalPnl(pnl)
                            .totalFunding(funding)
                            .winRate((double) w / ts.size() * 100)
                            .build();
                })
                .sorted(Comparator.comparingDouble(TickerStats::getTotalPnl).reversed())
                .toList();

        return TradeHistory.builder()
                .totalTrades(trades.size())
                .periodDays(getPeriodDays(period))
                .totalVolume(totalVolume)
                .totalPnl(totalPnl)
                .pnlToVolumePercent(totalVolume > 0 ? totalPnl / totalVolume * 100 : 0)
                .avgPnlPerTrade(totalPnl / trades.size())
                .bestTrade(trades.stream().mapToDouble(Trade::getPnl).max().orElse(0))
                .worstTrade(trades.stream().mapToDouble(Trade::getPnl).min().orElse(0))
                .wins(wins)
                .losses(losses)
                .winRate((double) wins / trades.size() * 100)
                .currentStreak(calcStreak(trades))
                .totalFunding(totalFunding)
                .fundingToPnlPercent(totalPnl != 0 ? totalFunding / totalPnl * 100 : 0)
                .avgOpenRate(avgOpenRate)
                .avgCloseRate(avgCloseRate)
                .avgRateDelta(avgCloseRate - avgOpenRate)
                .avgHoldTime(avgHold)
                .maxHoldTime(Duration.between(maxHoldTrade.getOpenedAt(), maxHoldTrade.getClosedAt()))
                .maxHoldTicker(maxHoldTrade.getTicker())
                .minHoldTime(Duration.between(minHoldTrade.getOpenedAt(), minHoldTrade.getClosedAt()))
                .minHoldTicker(minHoldTrade.getTicker())
                .tickerStats(tickerStats)
                .build();
    }

    private int calcStreak(List<Trade> trades) {
        List<Trade> sorted = trades.stream()
                .sorted(Comparator.comparing(Trade::getClosedAt).reversed())
                .toList();
        boolean win = sorted.get(0).getPnl() > 0;
        int streak = 0;
        for (Trade t : sorted) {
            if ((t.getPnl() > 0) == win) streak++;
            else break;
        }
        return win ? streak : -streak;
    }

    private int getPeriodDays(Period period) {
        return switch (period) {
            case DAY   -> 1;
            case WEEK  -> 7;
            case MONTH -> 30;
            case ALL   -> 0;
        };
    }
}
