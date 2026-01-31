package ru.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.client.aster.AsterClient;
import ru.client.extended.ExtendedClient;
import ru.config.FundingConfig;
import ru.dto.exchanges.*;
import ru.dto.exchanges.aster.AsterPosition;
import ru.dto.exchanges.extended.ExtendedPosition;
import ru.dto.funding.ArbitrageRates;
import ru.dto.funding.HoldingMode;
import ru.event.NewArbitrageEvent;
import ru.exceptions.BalanceException;
import ru.exceptions.ClosingPositionException;
import ru.exceptions.OpeningPositionException;
import ru.utils.GlobalModeResolver;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@EnableScheduling
@AllArgsConstructor
public class ExchangesService {

    private final AsterClient asterClient;
    private final ExtendedClient extendedClient;
    private final ApplicationEventPublisher eventPublisher;
    private final FundingArbitrageService fundingArbitrageService;
    private final FundingConfig fundingConfig;

    private final Map<String, FundingCloseSignal> openedPositions = new ConcurrentHashMap<>();
    private final Map<String, PositionBalance> balanceMap = new ConcurrentHashMap<>();
    private final AtomicLong positionIdCounter = new AtomicLong(1);

    @EventListener
    @Async
    public void handleArbitrageSignal(NewArbitrageEvent event) {
        log.info("[FundingBot] Received arbitrage signal for {}", event.getSignal().getTicker());

        String result = openPosition(event.getSignal());

        log.info("[FundingBot] Result: {}", result);
    }

    @Scheduled(cron = "10 01 * * * *", zone = "UTC")
    public void closeOnFundingTime() {
        if (openedPositions.isEmpty()) {
            log.debug("[FundingBot] Funding check: no positions to close");
            return;
        }

        log.info("[FundingBot] Funding time! Checking Fast mode positions");

        List<String> toClose = new ArrayList<>();

        //Filtering only fast mode positions
        for (FundingCloseSignal signal : openedPositions.values()) {
            if (signal.getMode() == HoldingMode.FAST_MODE) {
                toClose.add(signal.getId());
            }
        }

        if (toClose.isEmpty()) {
            log.debug("[FundingBot] No Fast mode positions found");
            return;
        }

        log.info("[FundingBot] Closing {} Fast mode positions", toClose.size());

        StringBuilder finalList = new StringBuilder();
        for (String id : toClose) {
            FundingCloseSignal signal = openedPositions.get(id);
            finalList.append(closePositions(signal));
            openedPositions.remove(id);
            balanceMap.remove(id);
        }

        log.info("[FundingBot] Positions closed:\n{}", finalList);
    }

    //Smart Mode funding tracking
    @Scheduled(fixedDelayString = "${funding.smart.checkDelayMs}")
    public void smartHoldTick() {
        if (openedPositions.isEmpty()) {
            return;
        }

        log.debug("[FundingBot] Smart mode - checking {} positions", openedPositions.size());

        List<String> toClose = new ArrayList<>();

        for (FundingCloseSignal pos : openedPositions.values()) {
            if (pos.getMode() != HoldingMode.SMART_MODE) {
                continue;
            }

            ArbitrageRates currentRate = getCurrentSpread(pos.getTicker());

            if (Objects.isNull(currentRate)) {
                log.warn("[FundingBot] Current rate is null for {}, skipping", pos.getTicker());
                continue;
            }

            double currentSpread = currentRate.getArbitrageRate();

            //Checking directions flip
            if (!pos.getAction().equalsIgnoreCase(currentRate.getAction())) {
                log.info("[FundingBot] Funding rate flipped! Closing {}: spread={}, held={}min",
                        pos.getTicker(), currentSpread, getHeldMinutes(pos));
                toClose.add(pos.getId());
                continue;
            }

            if (shouldCloseSmart(pos, currentSpread)) {
                log.info("[FundingBot] Closing {}: spread={}, held={}min",
                        pos.getTicker(), currentSpread, getHeldMinutes(pos));
                toClose.add(pos.getId());
            }
        }

        //Closing selected positions
        if (!toClose.isEmpty()) {
            log.info("[FundingBot] Closing {} positions in SmartMode", toClose.size());

            for (String id : toClose) {
                FundingCloseSignal signal = openedPositions.get(id);
                closePositions(signal);
                openedPositions.remove(id);
                balanceMap.remove(id);
            }
        }
    }

    public String openPosition(FundingOpenSignal signal) {
        double marginBalance = validateBalance();
        double balanceBefore = asterClient.getBalance() + extendedClient.getBalance();
        String positionId = generatePositionId();
        HoldingMode mode = signal.getMode();
        PositionBalance positionBalance = new PositionBalance();
        positionBalance.setBalanceBefore(balanceBefore);

        balanceMap.put(positionId, positionBalance);

        String extendedOrderId;
        String asterOrderId;
        String extSymbol;
        String astSymbol;

        try {
            extSymbol = signal.getTicker() + "-USD";
            extendedOrderId = extendedClient.openPositionWithFixedMargin(
                    extSymbol,
                    marginBalance,
                    signal.getLeverage(),
                    signal.getExtendedDirection().toString()
            );

            if (extendedOrderId == null) {
                throw new OpeningPositionException("[Extended] Failed to open position - returned null");
            }

            log.info("[Extended] Successfully created order with ID {}", extendedOrderId);

        } catch (Exception e) {
            String errorMsg = "[Extended] Error creating position: " + e.getMessage();
            log.error(errorMsg, e);

            publishFailureEvent(positionId, signal, errorMsg, marginBalance);

            return errorMsg;
        }

        //Aster opening position
        try {
            astSymbol = signal.getTicker() + "USDT";
            asterOrderId = asterClient.openPositionWithFixedMargin(
                    astSymbol,
                    marginBalance,
                    signal.getLeverage(),
                    signal.getAsterDirection().toString()
            );

            if (asterOrderId == null) {
                throw new OpeningPositionException("[Aster] Failed to open position - returned null");
            }

            log.info("[Aster] Successfully created order with ID {}", asterOrderId);
        } catch (Exception e) {
            log.error("[Aster] Failed to open position, rolling back Extended...", e);

            //Rollback Extended opened position
            try {
                extendedClient.closePosition(extSymbol, signal.getExtendedDirection().toString());
                log.info("[Extended] Position closed (rollback)");
            } catch (Exception closeEx) {
                log.error("[Extended] Failed to close position during rollback! Close manually!", closeEx);
            }

            //P&L calculation
            double balanceAfter = asterClient.getBalance() + extendedClient.getBalance();
            double balanceLoss = marginBalance - balanceAfter;

            String errorMsg = "[Aster] Error creating position: " + e.getMessage() +
                    "\n[FundingBot] Failed opening Aster position, loss = $" + String.format("%.4f", balanceLoss);

            publishFailureEvent(positionId, signal, errorMsg, marginBalance);

            return errorMsg;
        }

        //Waiting 3 sec before validation opened positions
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[FundingBot] Interrupted during sleep", e);
        }

        // Validation for opened positions
        try {
            if (!validateOpenedPositions(extSymbol, astSymbol, signal)) {
                log.error("[FundingBot] Position validation FAILED - positions were closed");

                //Calculations losses/profits
                double balanceAfter = asterClient.getBalance() + extendedClient.getBalance();
                double balanceLoss = balanceBefore - balanceAfter;

                String errorMsg = "[FundingBot] Position validation failed - all positions closed with delta " + balanceLoss;

                publishFailureEvent(positionId, signal, errorMsg, balanceLoss);
                return errorMsg;
            }
        } catch (ClosingPositionException e) {
            log.error("[FundingBot] Failed to close positions during validation! Manual check required!", e);

            String errorMsg = "[FundingBot] Failed to close positions during validation! Manual check required!\n"
                    + e.getMessage();

            publishFailureEvent(positionId, signal, errorMsg, marginBalance);
            return errorMsg;
        }

        //Creating opened position and putting into the open positions map
        FundingCloseSignal positionToClose = FundingCloseSignal.builder()
                .id(positionId)
                .ticker(signal.getTicker())
                .balance(marginBalance)
                .extDirection(signal.getExtendedDirection())
                .astDirection(signal.getAsterDirection())
                .asterOrderId(asterOrderId)
                .extendedOrderId(extendedOrderId)
                .action(signal.getAction())
                .mode(mode)
                .openedAtMs(System.currentTimeMillis())
                .openSpread(0.0)
                .badStreak(0)
                .build();

        openedPositions.put(positionToClose.getId(), positionToClose);

        String successMsg = "[FundingBot] Successfully opened positions | Extended: " + extendedOrderId +
                " | Aster: " + asterOrderId + " | Mode: " + mode + ". Waiting for funding fees.";

        //Sending Telegram notification with observer
        eventPublisher.publishEvent(new PositionOpenedEvent(
                positionId,
                signal.getTicker(),
                successMsg,
                marginBalance,
                signal.getExtendedDirection().toString(),
                signal.getAsterDirection().toString()
        ));

        log.info(successMsg);
        return successMsg;
    }

    public String closePositions(FundingCloseSignal signal) {
        String extSymbol = signal.getTicker() + "-USD";
        String astSymbol = signal.getTicker() + "USDT";

        PositionBalance posBalance = balanceMap.get(signal.getId());

        double balanceBefore = posBalance.getBalanceBefore();
        log.info("[FundingBot] Balance before closing positions: {}", balanceBefore);

        CompletableFuture<String> extFuture = CompletableFuture.supplyAsync(() ->
                extendedClient.closePosition(extSymbol, signal.getExtDirection().toString())
        );

        CompletableFuture<String> astFuture = CompletableFuture.supplyAsync(() ->
                {
                    try {
                        return asterClient.closePosition(astSymbol);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
        );

        try {
            //Closing both positions at the same time
            CompletableFuture.allOf(extFuture, astFuture).get(30, TimeUnit.SECONDS);

            //Waiting 20 sec for data to load up
            Thread.sleep(20000);

            double balanceAfter = asterClient.getBalance() + extendedClient.getBalance();
            log.info("[FundingBot] Balance after closing positions: {}", balanceAfter);
            double profit = balanceAfter - balanceBefore;
            posBalance.setBalanceAfter(balanceAfter);

            //Percent calculation
            double usedMargin = signal.getBalance();
            double profitPercent = (profit / usedMargin) * 100;

            log.info("[FundingBot] P&L: ${} ({}%)",
                    String.format("%.4f", profit),
                    String.format("%.2f", profitPercent));

            eventPublisher.publishEvent(new PositionClosedEvent(
                    signal.getId(),
                    signal.getTicker(),
                    profit,
                    profitPercent,
                    true
            ));

            return String.format("[FundingBot] Positions closed. P&L: %.4f USD (%.2f%%)", profit, profitPercent);

        } catch (Exception e) {
            log.error("[FundingBot] Error closing positions for {}", signal.getTicker(), e);

            eventPublisher.publishEvent(new PositionClosedEvent(
                    signal.getId(),
                    signal.getTicker(),
                    0,
                    0,
                    false
            ));

            return String.format("[FundingBot] %s Partial close - Manual check Needed!\n", signal.getTicker());
        }
    }

    private double validateBalance() {
        double asterBalance = asterClient.getBalance();
        if (asterBalance == 0) throw new BalanceException("[Aster] Balance is 0");

        double extendedBalance = extendedClient.getBalance();
        if (extendedBalance == 0) throw new BalanceException("[Extended] Balance is 0");

        //Adjusting balance for fees and price jumps
        double minBalance = Math.min(asterBalance, extendedBalance);
        double safeBalance = minBalance * 0.9; // 90% of balance

        log.info("[FundingBot] Balances: Aster=${}, Extended=${}, using ${}",
                asterBalance, extendedBalance, safeBalance);

        return safeBalance;
    }

    //Checking opened positions for both exchanges
    private boolean validateOpenedPositions(String extSymbol, String asterSymbol, FundingOpenSignal signal) {
        log.info("[FundingBot] Validating positions: Extended={}, Aster={}", extSymbol, asterSymbol);

        //Checking Extended position
        List<ExtendedPosition> extPositions = extendedClient.getPositions(extSymbol, signal.getExtendedDirection().toString());
        boolean extHasPosition = false;

        if (Objects.nonNull(extPositions) && !extPositions.isEmpty()) {
            extHasPosition = true;
            log.info("[FundingBot] Extended position FOUND: size={}, side={}",
                    extPositions.getFirst().getSize(), extPositions.getFirst().getSide());
        } else {
            log.warn("[FundingBot] Extended position NOT FOUND");
        }

        //Checking Aster(2 position returns in Hedge Mode for both directions)
        List<AsterPosition> asterPositions = asterClient.getPositions(asterSymbol);
        boolean asterHasPosition = false;

        if (Objects.nonNull(asterPositions) && !asterPositions.isEmpty()) {
            String asterPositionSide = signal.getAsterDirection().toString().toUpperCase();

            for (AsterPosition pos : asterPositions) {
                if (pos.getPositionSide().equalsIgnoreCase(asterPositionSide)) {
                    double amt = Math.abs(Double.parseDouble(pos.getPositionAmt()));

                    if (amt > 0.001) {
                        asterHasPosition = true;
                        log.info("[FundingBot] Aster position found: positionSide={}, amt={}",
                                pos.getPositionSide(), amt);
                    } else {
                        log.warn("[FundingBot] Aster position empty: positionSide={}, amt={}",
                                pos.getPositionSide(), amt);
                    }
                    break;
                }
            }

            if (!asterHasPosition) {
                log.warn("[FundingBot] Aster position with side {} not found in list", asterPositionSide);
            }
        } else {
            log.warn("[FundingBot] Aster positions list is NULL or empty");
        }

        //Checking the results
        if (extHasPosition && asterHasPosition) {
            log.info("[FundingBot] Both positions validated successfully");
            return true;
        }

        log.error("[FundingBot] Position validation failed! Extended={}, Aster={}",
                extHasPosition, asterHasPosition);

        if (extHasPosition) {
            log.warn("[FundingBot] Closing Extended position...");
            try {
                extendedClient.closePosition(extSymbol, signal.getExtendedDirection().toString());
                log.info("[FundingBot] Extended position closed successfully");
            } catch (Exception e) {
                log.error("[FundingBot] Failed to close Extended position", e);
                throw new ClosingPositionException("[FundingBot] Error closing Extended! Close manually: " + e.getMessage());
            }
        }

        if (asterHasPosition) {
            log.warn("[FundingBot] Closing Aster position...");
            try {
                asterClient.closePosition(asterSymbol);
                log.info("[FundingBot] Aster position closed successfully");
            } catch (InterruptedException e) {
                log.error("[FundingBot] Failed to close Aster position", e);
                throw new ClosingPositionException("[FundingBot] Error closing Aster! Close manually: " + e.getMessage());
            }
        }

        return false;
    }

    private String generatePositionId() {
        long id = positionIdCounter.getAndIncrement();
        return String.format("P-%04d", id);
    }

    private void publishFailureEvent(String positionId, FundingOpenSignal signal, String errorMsg, double balance) {
        eventPublisher.publishEvent(new PositionOpenedEvent(
                positionId,
                signal.getTicker(),
                errorMsg,
                balance,
                signal.getExtendedDirection().toString(),
                signal.getAsterDirection().toString()
        ));
    }

    public Map<String, FundingCloseSignal> getOpenedPositions() {
        return Collections.unmodifiableMap(openedPositions);
    }

    public Map<String, PositionBalance> getTrades() {
        return Collections.unmodifiableMap(balanceMap);
    }

    private ArbitrageRates getCurrentSpread(String ticker) {
        try {
            List<ArbitrageRates> rates = fundingArbitrageService.calculateArbitrageRates();
            for (ArbitrageRates rate : rates) {
                if (rate.getSymbol().equals(ticker)) {
                    return rate;
                }
            }
            log.warn("[FundingBot] Ticker {} not found in current rates", ticker);
            return null;
        } catch (Exception e) {
            log.error("[FundingBot] Failed to get spread for {}", ticker, e);
            return null;
        }
    }

    private boolean shouldCloseSmart(FundingCloseSignal pos, double currentSpread) {
        int maxHoldMinutes = fundingConfig.getSmart().getMaxHoldMinutes();

        long heldMinutes = getHeldMinutes(pos);
        if (heldMinutes >= maxHoldMinutes) {
            log.info("[FundingBot] Max hold time reached: {}min (max {})",
                    heldMinutes, maxHoldMinutes);
            return true;
        }

        //Min rate allowed
        double threshold = fundingConfig.getSmart().getCloseThreshold();

        if (currentSpread <= threshold) {
            pos.setBadStreak(pos.getBadStreak() + 1);
            log.debug("[FundingBot] Bad spread: {} <= {}, streak={}",
                    currentSpread, threshold, pos.getBadStreak());
        } else {
            pos.setBadStreak(0);
        }

        int badStreakThreshold = fundingConfig.getSmart().getBadStreakThreshold();

        if (pos.getBadStreak() >= badStreakThreshold) {
            log.info("[SmartHold] Bad streak {} >= {}",
                    pos.getBadStreak(), badStreakThreshold);
            return true;
        }

        return false;
    }

    private long getHeldMinutes(FundingCloseSignal pos) {
        long heldMs = System.currentTimeMillis() - pos.getOpenedAtMs();
        return TimeUnit.MILLISECONDS.toMinutes(heldMs);
    }
}
