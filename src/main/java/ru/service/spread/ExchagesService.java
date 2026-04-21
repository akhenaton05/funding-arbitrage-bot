package ru.service.spread;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.config.SpreadConfig;
import ru.dto.exchanges.Direction;
import ru.dto.spread.SpreadEvent;
import ru.dto.spread.SpreadPosition;
import ru.exchanges.Exchange;
import ru.exchanges.factory.ExchangeFactory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@EnableScheduling
@AllArgsConstructor
public class ExchagesService {
    private final SpreadArbitrageService spreadArbitrageService;
    private final ExchangeFactory exchangeFactory;
    private final SpreadConfig spreadConfig;
    private final Map<String, SpreadPosition> openPositions = new ConcurrentHashMap<>();


    @Scheduled(fixedDelay = 30_000)
    private void spreadScanner() {
        Set<String> symbols = spreadArbitrageService.getOiFilteredSymbols();
        if (symbols.isEmpty()) return;

        List<Exchange> exchanges = exchangeFactory.getAllExchanges();

        for (String symbol : symbols) {
            scanSymbol(symbol, exchanges);
        }
    }

    @Scheduled(fixedDelay = 30_000)
    private void checkOpenedSpreads() {
        if(openPositions.isEmpty()) return;

        log.info("[SpreadScan] Checking spreads for opened positions: {}", openPositions.size());

        trackOpenPositions(exchangeFactory.getAllExchanges());
    }

    private void scanSymbol(String symbol, List<Exchange> exchanges) {
        Map<Exchange, Double> prices = new HashMap<>();

        for(Exchange exchange : exchanges) {
            prices.put(exchange, exchange.getCurrentPrice(symbol));
        }

        if (prices.size() < 2) return;

        // 2. Перебираем все пары и ищем максимальный спред
        List<Exchange> exchangeList = new ArrayList<>(prices.keySet());
        SpreadEvent bestSpread = null;

        for (int i = 0; i < exchangeList.size(); i++) {
            for (int j = i + 1; j < exchangeList.size(); j++) {
                Exchange firstExchange = exchangeList.get(i);
                Exchange secondExchange = exchangeList.get(j);

                double firstPrice = prices.get(firstExchange);
                double secondPrice = prices.get(secondExchange);
                double mid = (firstPrice + secondPrice) / 2;
                double spreadBps = Math.abs(firstPrice - secondPrice) / mid * 10_000;

                if (spreadBps >= spreadConfig.getEntryThresholdBps() &&
                        (bestSpread == null || spreadBps > bestSpread.getSpread())) {

                    boolean firstIsHigher = firstPrice >= secondPrice;

                    bestSpread = SpreadEvent.builder()
                            .ticker(symbol)
                            .firstExchange(firstIsHigher ? firstExchange : secondExchange)
                            .secondExchange(firstIsHigher ? secondExchange : firstExchange)
                            .firstDirection(Direction.SHORT)
                            .secondDirection(Direction.LONG)
                            .firstPrice(firstIsHigher ? firstPrice : secondPrice)
                            .secondPrice(firstIsHigher ? secondPrice : firstPrice)
                            .spread(spreadBps)
                            .build();
                }
            }
        }

        //Logging
        if (bestSpread != null) {
            log.info("[SpreadScan] {} | {}→{} | {} bps | short @{}, long @{}",
                    symbol,
                    bestSpread.getFirstExchange().getType(),
                    bestSpread.getSecondExchange().getType(),
                    String.format("%.2f", bestSpread.getSpread()) ,
                    bestSpread.getFirstPrice(),
                    bestSpread.getSecondPrice());

            String key = bestSpread.getTicker() + "_"
                    + bestSpread.getFirstExchange().getType() + "_"
                    + bestSpread.getSecondExchange().getType();

            if (!openPositions.containsKey(key)) {
                openPosition(bestSpread);
            }
        }

    }

    private void openPosition(SpreadEvent event) {
        log.info("[SpreadScan] Opening position simulation with {}", event);

        String key = event.getTicker() + "_"
                + event.getFirstExchange().getType() + "_"
                + event.getSecondExchange().getType();

        SpreadPosition position = SpreadPosition.builder()
                .symbol(event.getTicker())
                .shortExchange(event.getFirstExchange())
                .longExchange(event.getSecondExchange())
                .entrySpread(event.getSpread())
                .entryShortPrice(event.getFirstPrice())
                .entryLongPrice(event.getSecondPrice())
                .openedAt(LocalDateTime.now())
                .build();

        openPositions.put(key, position);

        log.info("[SpreadScan] Position opened: {}, {} bps → watching collapse",
                event.getTicker(),
                String.format("%.2f", event.getSpread()));
    }

    private void trackOpenPositions(List<Exchange> exchanges) {
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, SpreadPosition> entry : openPositions.entrySet()) {
            SpreadPosition pos = entry.getValue();
            long heldMinutes = ChronoUnit.MINUTES.between(pos.getOpenedAt(), LocalDateTime.now());

            double priceShort = pos.getShortExchange().getCurrentPrice(pos.getSymbol());
            double priceLong = pos.getLongExchange().getCurrentPrice(pos.getSymbol());
            double currentSpread = Math.abs(priceShort - priceLong)
                    / ((priceShort + priceLong) / 2) * 10_000;
            double pnlBps = pos.getEntrySpread() - currentSpread;

            log.info("[SpreadScan] Track | {} | now: {} bps | pnl: {} bps | held: {}m",
                    pos.getSymbol(),
                    String.format("%.2f", currentSpread),
                    String.format("%.2f", pnlBps),
                    heldMinutes);

            if (currentSpread <= spreadConfig.getExitThresholdBps()) {
                log.info("[SpreadScan] Close (collapsed) | {} | pnl: {} bps | held: {}m",
                        pos.getSymbol(), String.format("%.2f", pnlBps), heldMinutes);
                toRemove.add(entry.getKey());
            }
        }

        toRemove.forEach(openPositions::remove);
    }
}
