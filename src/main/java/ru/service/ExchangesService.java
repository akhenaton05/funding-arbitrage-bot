package ru.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.config.FundingConfig;
import ru.dto.exchanges.*;
import ru.dto.funding.*;
import ru.event.NewArbitrageEvent;
import ru.event.PnLThresholdEvent;
import ru.exceptions.ClosingPositionException;
import ru.exceptions.OpeningPositionException;
import ru.exchanges.Exchange;
import ru.exchanges.factory.ExchangeFactory;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    private final ExchangeFactory exchangeFactory;
    private final ApplicationEventPublisher eventPublisher;
    private final FundingArbitrageService fundingArbitrageService;
    private final FundingConfig fundingConfig;

    private final Map<String, FundingCloseSignal> openedPositions = new ConcurrentHashMap<>();
    private final Map<String, PositionBalance> balanceMap = new ConcurrentHashMap<>();
    private final Map<String, PositionPnLData> positionDataMap = new ConcurrentHashMap<>();
    private final Set<String> notifiedPositions = ConcurrentHashMap.newKeySet();
    private final AtomicLong positionIdCounter = new AtomicLong(1);


    /**
     * Listeners & Scheduled tasks
     */

    @EventListener
    @Async
    public void handleArbitrageSignal(NewArbitrageEvent event) {
        log.info("[FundingBot] Received arbitrage signal for {}", event.getSignal().getTicker());

        String result = openPositionWithEqualSize(event.getSignal());

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
        }

        log.info("[FundingBot] Positions closed:\n{}", finalList);
    }

    //Smart Mode funding tracking
    @Scheduled(fixedDelayString = "${funding.smart.checkDelayMs}")
    public void smartHoldTick() {
        if (openedPositions.isEmpty()) {
            return;
        }

        log.info("[FundingBot] Smart mode - checking {} positions", openedPositions.size());

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

            if (shouldCloseSmart(pos, currentSpread, currentRate)) {
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
            }
        } else {
            log.info("[FundingBot] SmartMode - no position to close, position hold continued");
        }
    }

    // Calculating funding for exchanges
    @Scheduled(cron = "50 59 * * * *", zone = "UTC")
    private void predictFunding() {
        if (openedPositions.isEmpty()) {
            log.debug("[FundingBot] No open positions");
            return;
        }

        log.info("[FundingBot] Predicting Aster funding for {} positions", openedPositions.size());

        for (FundingCloseSignal signal : openedPositions.values()) {
            try {
                updateFunding(signal);
            } catch (Exception e) {
                log.error("[FundingBot] Failed to predict Aster funding for {}: {}",
                        signal.getId(), e.getMessage());
            }
        }

        log.info("[FundingBot] Aster funding prediction completed");
    }

    @Scheduled(fixedDelay = 300000) //Every 5 min
    public void updateOpenPositionsPnL() {
        if (openedPositions.isEmpty()) {
            return;
        }

        log.debug("[FundingBot] Updating P&L for {} open positions", openedPositions.size());

        for (FundingCloseSignal signal : openedPositions.values()) {
            try {
                PositionPnLData pnlData = calculateCurrentPnL(signal);

                if (pnlData == null) {
                    continue;
                }

                checkPnLThreshold(signal, pnlData);

            } catch (Exception e) {
                log.error("[FundingBot] Failed to update P&L for {}: {}",
                        signal.getId(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 600000) // Каждые 10 минут
    public void checkPositions() {
        if (openedPositions.isEmpty()) {
            return;
        }

        log.debug("[FundingBot] Checking {} opened positions for liquidation/closure",
                openedPositions.size());

        List<String> toRemove = new ArrayList<>();

        for (FundingCloseSignal signal : openedPositions.values()) {
            try {
                boolean wasClosed = checkOpenedPositions(signal);

                if (wasClosed) {
                    toRemove.add(signal.getId());
                }

            } catch (Exception e) {
                log.error("[FundingBot] Failed to check position {}: {}",
                        signal.getId(), e.getMessage());
            }
        }

        for (String positionId : toRemove) {
            log.info("[FundingBot] Removing closed position {}", positionId);
            openedPositions.remove(positionId);
        }
    }

    /**
     * Main logic
     */

    public String openPositionWithEqualSize(FundingOpenSignal signal) {
        //parsing signal and creating exchanges
        Exchange exchangeOne = exchangeFactory.getExchange(signal.getFirstPosition().getExchange());
        Exchange exchangeTwo = exchangeFactory.getExchange(signal.getSecondPosition().getExchange());

        Balance exchangesBalances = validateBalance(exchangeOne, exchangeTwo);
        double marginBalance = exchangesBalances.getMargin();

        //Validating data for opening position(for Aster only)
        if (exchangeOne.getType().equals(ExchangeType.ASTER) || exchangeTwo.getType().equals(ExchangeType.ASTER)) {
            Exchange ast = exchangeOne.getType().equals(ExchangeType.ASTER) ? exchangeOne : exchangeTwo;
            if (!validateFundingTime(signal, ast)) {
                String errorMsg = "[FundingBot] More than an hour until funding, position not opened";
                log.info("[FundingBot] More than an hour until funding, position not opened");

                publishFailureEvent("E-0000", signal, errorMsg, marginBalance, false);
                return errorMsg;
            }
        }

        if (marginBalance <= 5) {
            String errorMsg = "[FundingBot] No balance available to open position: " + marginBalance;
            log.info("[FundingBot] No balance available to open position: {}", marginBalance);

            publishFailureEvent("E-0000", signal, errorMsg, marginBalance, false);
            return errorMsg;
        }

        String positionId = generatePositionId();
        log.info("[FundingBot] Validations passed. Allocated position ID: {} for {}",
                positionId, signal.getTicker());

        double balanceBefore = exchangesBalances.getBalance();
        HoldingMode mode = signal.getMode();

        PositionBalance positionBalance = new PositionBalance();
        positionBalance.setBalanceBefore(balanceBefore);

        int leverage = signal.getLeverage();

        //Validation for Aster leverage
        if (exchangeOne.getType().equals(ExchangeType.ASTER) || exchangeTwo.getType().equals(ExchangeType.ASTER)) {
            Exchange ast = exchangeOne.getType().equals(ExchangeType.ASTER) ? exchangeOne : exchangeTwo;
            leverage = Math.min(signal.getLeverage(), validateLeverage(signal.getTicker(), ast));
        }

        //Validation for Lighter leverage
        if (exchangeOne.getType().equals(ExchangeType.LIGHTER) || exchangeTwo.getType().equals(ExchangeType.LIGHTER)) {
            Exchange lighter = exchangeOne.getType().equals(ExchangeType.LIGHTER) ? exchangeOne : exchangeTwo;
            int lighterMaxLeverage = lighter.getMaxLeverage(signal.getTicker());
            leverage = Math.min(leverage, lighterMaxLeverage);
            log.info("[FundingBot] Lighter max leverage for {}: {}x", signal.getTicker(), lighterMaxLeverage);
        }

        balanceMap.put(positionId, positionBalance);

        //Setting pairs for exchanges for equal open/close time
        exchangeOne.setPairedExchange(exchangeTwo.getType());
        exchangeTwo.setPairedExchange(exchangeOne.getType());

        String ticker = signal.getTicker();

        log.info("[FundingBot] Calculating delta-neutral position size");

        //Choosing direction for execution price
        boolean isFirstBuy = signal.getFirstPosition().getDirection() == Direction.LONG;
        boolean isSecondBuy = signal.getSecondPosition().getDirection() == Direction.LONG;

        //Calculating max sizes for both exchanges
        Double firstExchangeSize = exchangeOne.calculateMaxSizeForMargin(
                ticker,
                marginBalance,
                leverage,
                isFirstBuy
        );

        Double secondExchangeSize = exchangeTwo.calculateMaxSizeForMargin(
                ticker,
                marginBalance,
                leverage,
                isSecondBuy
        );

        if (Objects.isNull(firstExchangeSize) || Objects.isNull(secondExchangeSize)) {
            String errorMsg = "[FundingBot] Failed to calculate position sizes";
            log.error(errorMsg);
            balanceMap.remove(positionId);
            publishFailureEvent(positionId, signal, errorMsg, marginBalance, false);
            rollbackPositionId();
            return errorMsg;
        }

        //Using minimal size
        double targetSize = Math.min(firstExchangeSize, secondExchangeSize);

//        //Rounding
        targetSize = Math.floor(targetSize * 100) / 100.0;

        log.info("[FundingBot] Delta-neutral sizing: {} max: {}, {} max: {}, Target: {} (using minimum)",
                exchangeOne.getName(), String.format("%.4f", firstExchangeSize),
                exchangeTwo.getName(), String.format("%.4f", secondExchangeSize),
                String.format("%.4f", targetSize));

        final int finalLeverage = leverage;
        final double finalTargetSize = targetSize;

        String firstOrderId;
        String secondOrderId;

        //Parallel opening CompletableFuture
        CompletableFuture<String> firstFuture = CompletableFuture.supplyAsync(() -> {
            try {
                log.info("[FundingBot] {} opening {} {} @ {}x with size {}",
                        exchangeOne.getName(),
                        signal.getFirstPosition().getDirection(),
                        ticker,
                        finalLeverage,
                        String.format("%.4f", finalTargetSize));

                exchangeOne.setLeverage(signal.getTicker(), finalLeverage);

                String orderId = exchangeOne.openPositionWithSize(
                        signal.getTicker(),
                        finalTargetSize,
                        signal.getFirstPosition().getDirection().toString()
                );

                if (orderId == null) {
                    throw new OpeningPositionException("[" + exchangeOne.getName() + "] Failed to open - returned null");
                }

                log.info("[FundingBot] {} position opened: external_id={}", exchangeOne.getName(), orderId);
                return orderId;

            } catch (Exception e) {
                log.error("[{}] Opening failed: {}", exchangeOne.getName(), e.getMessage(), e);
                throw new OpeningPositionException("[" + exchangeOne.getName() + "] " + e.getMessage());
            }
        });

        CompletableFuture<String> secondFuture = CompletableFuture.supplyAsync(() -> {
            try {
                log.info("[FundingBot] {} opening {} {} @ {}x with size {}",
                        exchangeTwo.getName(),
                        signal.getSecondPosition().getDirection(),
                        ticker,
                        finalLeverage,
                        String.format("%.4f", finalTargetSize));

                exchangeTwo.setLeverage(signal.getTicker(), finalLeverage);

                String orderId = exchangeTwo.openPositionWithSize(
                        signal.getTicker(),
                        finalTargetSize,
                        signal.getSecondPosition().getDirection().toString()
                );

                if (orderId == null) {
                    throw new OpeningPositionException("[" + exchangeTwo.getName() + "] Failed to open - returned null");
                }

                log.info("[FundingBot] {} position opened: orderId={}", exchangeTwo.getName(), orderId);
                return orderId;

            } catch (Exception e) {
                log.error("[{}] Opening failed: {}", exchangeTwo.getName(), e.getMessage(), e);
                throw new OpeningPositionException("[" + exchangeTwo.getName() + "] " + e.getMessage());
            }
        });

        try {
            log.info("[FundingBot] Opening both positions");

            //Wait for both to complete (20 sec timeout)
            CompletableFuture.allOf(firstFuture, secondFuture).get(20, TimeUnit.SECONDS);

            // Get results
            firstOrderId = firstFuture.get();
            secondOrderId = secondFuture.get();

            log.info("[FundingBot] Both positions opened successfully: {}={}, {}={}",
                    exchangeOne.getName(), firstOrderId,
                    exchangeTwo.getName(), secondOrderId);

        } catch (Exception e) {
            log.error("[FundingBot] Failed to open positions", e);

            //Rollback
            String rollbackFirstId = null;
            String rollbackSecondId = null;
            boolean firstSuccess = firstFuture.isDone() && !firstFuture.isCompletedExceptionally();
            boolean secondSuccess = secondFuture.isDone() && !secondFuture.isCompletedExceptionally();

            // Get successful order IDs
            try {
                if (firstSuccess) rollbackFirstId = firstFuture.get();
            } catch (Exception ignored) {
            }

            try {
                if (secondSuccess) rollbackSecondId = secondFuture.get();
            } catch (Exception ignored) {
            }

            log.warn("[FundingBot] Rollback status: {} = {}, {} = {}",
                    exchangeOne.getName(), firstSuccess ? "✅" : "❌",
                    exchangeTwo.getName(), secondSuccess ? "✅" : "❌");

            //Rollback opened positions
            if (firstSuccess && rollbackFirstId != null) {
                try {
                    log.info("[FundingBot] Rolling back {} position...", exchangeOne.getName());
                    OrderResult result = exchangeOne.closePosition(signal.getTicker(), signal.getFirstPosition().getDirection());
                    log.info("[FundingBot] {} position closed: {}", exchangeOne.getName(), result.getOrderId());
                } catch (Exception closeEx) {
                    log.error("[FundingBot] Failed to close {} - manual check required!",
                            exchangeOne.getName(), closeEx);
                }
            }

            if (secondSuccess && rollbackSecondId != null) {
                try {
                    log.info("[FundingBot] Rolling back {} position", exchangeTwo.getName());
                    OrderResult result = exchangeTwo.closePosition(signal.getTicker(), signal.getSecondPosition().getDirection());
                    log.info("[FundingBot] {} position closed: {}", exchangeTwo.getName(), result.getOrderId());
                } catch (Exception closeEx) {
                    log.error("[FundingBot] Failed to close {} - manual check required!",
                            exchangeTwo.getName(), closeEx);
                }
            }

            // Calculate loss
            double balanceAfter = exchangeOne.getBalance().getBalance() + exchangeTwo.getBalance().getBalance();
            double rollbackLoss = balanceBefore - balanceAfter;

            // Build error message
            String errorMsg;
            if (!firstSuccess && !secondSuccess) {
                errorMsg = String.format("[FundingBot] Both exchanges failed to open | Loss: $%.4f", rollbackLoss);
            } else if (!firstSuccess) {
                errorMsg = String.format("[%s] Failed to open | Loss: $%.4f", exchangeOne.getName(), rollbackLoss);
            } else {
                errorMsg = String.format("[%s] Failed to open | Loss: $%.4f", exchangeTwo.getName(), rollbackLoss);
            }

            balanceMap.remove(positionId);
            publishFailureEvent(positionId, signal, errorMsg, rollbackLoss, false);
            rollbackPositionId();
            log.warn("[FundingBot] Position ID {} rolled back", positionId);

            return errorMsg;
        }

        // Waiting for positions to be visible
        try {
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[FundingBot] Interrupted during sleep", e);
        }

        // Validate positions
        try {
            if (!validateOpenedPositions(exchangeOne, exchangeTwo, signal, positionId)) {
                log.error("[FundingBot] Position validation failed - positions were closed");

                double balanceAfter = (exchangeOne.getBalance().getBalance() + exchangeTwo.getBalance().getBalance());
                double balanceLoss = balanceBefore - balanceAfter;

                String errorMsg = "[FundingBot] Position validation failed - all positions closed with delta $" +
                        String.format("%.4f", balanceLoss);

                balanceMap.remove(positionId);
                positionDataMap.remove(positionId);
                publishFailureEvent(positionId, signal, errorMsg, balanceLoss, false);
                rollbackPositionId();
                log.warn("[FundingBot] Position ID {} rolled back due to validation failure", positionId);

                return errorMsg;
            }
        } catch (ClosingPositionException e) {
            log.error("[FundingBot] Failed to close positions during validation! Manual check required!", e);

            String errorMsg = "[FundingBot] Failed to close positions during validation! Manual check required!\n"
                    + e.getMessage();

            balanceMap.remove(positionId);
            positionDataMap.remove(positionId);
            publishFailureEvent(positionId, signal, errorMsg, marginBalance, false);
            rollbackPositionId();
            log.warn("[FundingBot] Position ID {} rolled back due to closing exception", positionId);

            return errorMsg;
        }

        signal.getFirstPosition().setOrderId(firstOrderId);
        signal.getSecondPosition().setOrderId(secondOrderId);

        // Verify delta-neutrality
        validateDeltaNeutrality(exchangeOne, exchangeTwo, signal, positionId, finalTargetSize);

        FundingCloseSignal positionToClose = FundingCloseSignal.builder()
                .id(positionId)
                .ticker(signal.getTicker())
                .balance(marginBalance)
                .firstPosition(signal.getFirstPosition())
                .secondPosition(signal.getSecondPosition())
                .firstExchange(exchangeOne)
                .secondExchange(exchangeTwo)
                .openedFundingRate(signal.getRate())
                .action(signal.getAction())
                .mode(mode)
                .openedAtMs(System.currentTimeMillis())
                .openSpread(0.0)
                .badStreak(0)
                .build();

        openedPositions.put(positionToClose.getId(), positionToClose);

        //Adding SL/TP orders
        if (fundingConfig.getSltp().isEnabled()) {
            setupSlTpOrders(positionToClose);
        }

        String successMsg = "[FundingBot] Delta-neutral position opened | " +
                exchangeOne.getName() + ": " + firstOrderId + " | " +
                exchangeTwo.getName() + ": " + secondOrderId + " | " +
                "Size: " + String.format("%.4f", finalTargetSize) + " " + ticker + " | " +
                "Mode: " + mode;

        eventPublisher.publishEvent(new PositionOpenedEvent(
                positionId,
                signal.getTicker(),
                successMsg,
                marginBalance,
                signal.getFirstPosition().getDirection().toString(),
                signal.getSecondPosition().getDirection().toString(),
                mode.equals(HoldingMode.FAST_MODE) ? "Fast mode" : "Smart mode",
                true,
                signal.getRate()
        ));

        log.info(successMsg);
        return successMsg;
    }


    public String closePositions(FundingCloseSignal signal) {

        PositionBalance posBalance = balanceMap.get(signal.getId());

        double balanceBefore = posBalance.getBalanceBefore();
        log.info("[FundingBot] Balance before closing positions: {}", balanceBefore);

        CompletableFuture<OrderResult> firstFuture = CompletableFuture.supplyAsync(() ->
                signal.getFirstExchange().closePosition(signal.getTicker(), signal.getFirstPosition().getDirection())
        );

        CompletableFuture<OrderResult> secondFuture = CompletableFuture.supplyAsync(() ->
                signal.getSecondExchange().closePosition(signal.getTicker(), signal.getSecondPosition().getDirection())
        );

        double currentSpread = 0.0;

        try {
            //Calculating closing pnl to compare
            PositionPnLData pnlDataBefore = calculateCurrentPnL(signal);

            //Closing both positions at the same time
            CompletableFuture.allOf(firstFuture, secondFuture).get(30, TimeUnit.SECONDS);

            //Calculating closing pnl
            if (pnlDataBefore != null) {
                log.info("[FundingBot] Expected P&L before closing: ${}",
                        String.format("%.4f", pnlDataBefore.getNetPnl()));
            }

            //Waiting 20 sec for data to load up
            Thread.sleep(20000);

            double balanceAfter = signal.getFirstExchange().getBalance().getBalance() + signal.getSecondExchange().getBalance().getBalance();
            log.info("[FundingBot] Balance after closing positions: {}", balanceAfter);
            double profit = balanceAfter - balanceBefore;
            posBalance.setBalanceAfter(balanceAfter);

            //Percent calculation
            double usedMargin = signal.getBalance();
            double profitPercent = (profit / usedMargin) * 100;

            if (pnlDataBefore != null) {
                double difference = profit - pnlDataBefore.getNetPnl();
                log.info("[FundingBot] Calculated P&L: ${} | Actual: ${} | Difference: ${} ({}%)",
                        String.format("%.4f", pnlDataBefore.getNetPnl()),
                        String.format("%.4f", profit),
                        String.format("%.4f", difference),
                        String.format("%.2f", Math.abs(difference / profit) * 100));
            }

            log.info("[FundingBot] P&L: ${} ({}%)",
                    String.format("%.4f", profit),
                    String.format("%.2f", profitPercent));

            //Collecting rate at closing
            ArbitrageRates currentRate = getCurrentSpread(signal.getTicker());
            currentSpread = currentRate.getArbitrageRate();

            eventPublisher.publishEvent(new PositionClosedEvent(
                    signal.getId(),
                    signal.getTicker(),
                    profit,
                    profitPercent,
                    true,
                    signal.getMode().equals(HoldingMode.FAST_MODE) ? "Fast mode" : "Smart mode",
                    currentSpread,
                    signal.getClosureReason()
            ));

            notifiedPositions.remove(signal.getId());

            return String.format("[FundingBot] Positions closed. P&L: %.4f USD (%.2f%%)", profit, profitPercent);

        } catch (Exception e) {
            log.error("[FundingBot] Error closing positions for {}", signal.getTicker(), e);

            eventPublisher.publishEvent(new PositionClosedEvent(
                    signal.getId(),
                    signal.getTicker(),
                    0,
                    0,
                    false,
                    signal.getMode().equals(HoldingMode.FAST_MODE) ? "Fast mode" : "Smart mode",
                    signal.getCurrentFindingRate(),
                    signal.getClosureReason()
            ));

            return String.format("[FundingBot] %s Partial close - Manual check Needed!\n", signal.getTicker());
        }
    }

    /**
     * Validation
     */

    private Balance validateBalance(Exchange ex1, Exchange ex2) {
        Balance balanceOne = ex1.getBalance();
        log.info("[{}] Balance is {}, Margin is {}", ex1.getName(), balanceOne.getBalance(), balanceOne.getMargin());

        Balance balanceTwo = ex2.getBalance();
        log.info("[{}] Balance is {}, Margin is {}", ex2.getName(), balanceTwo.getBalance(), balanceTwo.getMargin());

        double safeBalance = Math.min(balanceOne.getMargin(), balanceTwo.getMargin());

        log.info("[FundingBot] Margin chosen to open an order: {}", safeBalance);

        return Balance.builder()
                .balance(balanceOne.getBalance() + balanceTwo.getBalance())
                .margin(safeBalance)
                .build();
    }

    private boolean validateFundingTime(FundingOpenSignal signal, Exchange ex) {
        long minutesUntilFunding = ex.getMinutesUntilFunding(signal.getTicker());
        //If Funding is more than 60 min - not opening position
        if (minutesUntilFunding > 60) {
            log.info("[FundingBot] Funding too far: {} minutes, skipping signal", minutesUntilFunding);
            return false;
        }

        log.info("[FundingBot] Funding validated correctly, in less than {} min.", minutesUntilFunding);
        return true;
    }

    public int validateLeverage(String symbol, Exchange ex) {

        int asterLeverage = ex.getMaxLeverage(symbol);

        log.info("[FundingBot] Aster leverage for {}: {}",
                symbol, asterLeverage);

        return asterLeverage;
    }

    //Checking opened positions for both exchanges
    private boolean validateOpenedPositions(Exchange ex1, Exchange ex2, FundingOpenSignal signal, String positionId) {
        log.info("[FundingBot] Validating positions: {}={}, {}={}", ex1.getName(), ex1.formatSymbol(signal.getTicker()),
                ex2.getName(), ex2.formatSymbol(signal.getTicker()));

        //Checking First exchange position
        List<Position> firstPositions = ex1.getPositions(signal.getTicker(), signal.getFirstPosition().getDirection());
        boolean firstHasPosition = false;

        if (Objects.nonNull(firstPositions) && !firstPositions.isEmpty()) {
            firstHasPosition = true;
            log.info("[FundingBot] {} position found: size={}, side={}",
                    ex1.getName(), firstPositions.getFirst().getSize(), firstPositions.getFirst().getSide());
        } else {
            log.warn("[FundingBot] {} position not found", ex1.getName());
        }

        //Checking Second position
        List<Position> secondPositions = ex2.getPositions(signal.getTicker(), signal.getSecondPosition().getDirection());
        boolean secondHasPosition = false;

        if (Objects.nonNull(secondPositions) && !secondPositions.isEmpty()) {
            secondHasPosition = true;
            log.info("[FundingBot] {} position found: size={}, side={}",
                    ex2.getName(), secondPositions.getFirst().getSize(), secondPositions.getFirst().getSide());
        } else {
            log.warn("[FundingBot] {} position not found", ex2.getName());
        }

        //Checking the results
        if (firstHasPosition && secondHasPosition) {
            PositionNotionalData firstData = calculateNotional(
                    firstPositions.getFirst(),
                    ex1,
                    false
            );
            log.info("[FundingBot] {} notional for position: {}", ex1.getName(), firstData);

            PositionNotionalData secondData = calculateNotional(
                    secondPositions.getFirst(),
                    ex2,
                    false
            );
            log.info("[FundingBot] {} notional for position: {}", ex2.getName(), secondData);

            double totalOpenFees = firstData.getFee() + secondData.getFee();

            log.info("[FundingBot] Opening fees: Extended=${}, Aster=${}, Total=${}",
                    String.format("%.4f", firstData.getFee()),
                    String.format("%.4f", secondData.getFee()),
                    String.format("%.4f", totalOpenFees));

            // Saving data
            PositionPnLData pnlData = PositionPnLData.builder()
                    .positionId(positionId)
                    .ticker(signal.getTicker())
                    .openTime(LocalDateTime.now(ZoneOffset.UTC))
                    .totalOpenFees(totalOpenFees)
                    .totalCloseFees(0.0)
                    .firstFundingNet(0.0)
                    .secondFundingNet(0.0)
                    .firstUnrealizedPnl(0.0)
                    .secondUnrealizedPnl(0.0)
                    .build();

            pnlData.calculateTotals();
            positionDataMap.put(positionId, pnlData);

            log.info("[FundingBot] P&L initialized: positionId={}, openFees=${}, netPnl=${}",
                    positionId,
                    String.format("%.4f", totalOpenFees),
                    String.format("%.4f", pnlData.getNetPnl()));

            return true;
        }

        log.error("[FundingBot] Position validation failed! {}={}, {}={}",
                ex1.getName(), firstHasPosition, ex2.getName(), secondHasPosition);

        if (firstHasPosition) {
            log.warn("[FundingBot] Closing {} position", ex1.getName());
            try {
                ex1.closePosition(signal.getTicker(), signal.getFirstPosition().getDirection());
                log.info("[FundingBot] {} position closed successfully", ex1.getName());
            } catch (Exception e) {
                log.error("[FundingBot] Failed to close {} position", ex1.getName(), e);
                throw new ClosingPositionException("[FundingBot] Error closing " + ex1.getName() + e.getMessage());
            }
        }

        if (secondHasPosition) {
            log.warn("[FundingBot] Closing {} position", ex2.getName());
            try {
                ex2.closePosition(signal.getTicker(), signal.getSecondPosition().getDirection());
                log.info("[FundingBot] {} position closed successfully", ex2.getName());
            } catch (Exception e) {
                log.error("[FundingBot] Failed to close {} position", ex2.getName(), e);
                throw new ClosingPositionException("[FundingBot] Error closing " + ex2.getName() + e.getMessage());
            }
        }

        return false;
    }

    //Logging delta neutrality for open orders
    private void validateDeltaNeutrality(Exchange ex1, Exchange ex2,
                                         FundingOpenSignal signal, String positionId,
                                         double expectedSize) {
        try {
            log.info("[FundingBot] Verifying delta-neutrality for {}", positionId);

            //Checking First exchange position
            List<Position> firstPositions = ex1.getPositions(signal.getTicker(), signal.getFirstPosition().getDirection());
            boolean firstHasPosition = false;

            if (Objects.nonNull(firstPositions) && !firstPositions.isEmpty()) {
                firstHasPosition = true;
                log.info("[FundingBot] {} position found: size={}, side={}",
                        ex1.getName(), firstPositions.getFirst().getSize(), firstPositions.getFirst().getSide());
            } else {
                log.warn("[FundingBot] {} position not found", ex1.getName());
            }

            //Checking Second position
            List<Position> secondPositions = ex2.getPositions(signal.getTicker(), signal.getSecondPosition().getDirection());
            boolean secondHasPosition = false;

            if (Objects.nonNull(secondPositions) && !secondPositions.isEmpty()) {
                secondHasPosition = true;
                log.info("[FundingBot] {} position found: size={}, side={}",
                        ex2.getName(), secondPositions.getFirst().getSize(), secondPositions.getFirst().getSide());
            } else {
                log.warn("[FundingBot] {} position not found", ex2.getName());
            }

            PositionNotionalData firstPos = calculateNotional(firstPositions.getFirst(), ex1, false);
            PositionNotionalData secondPos = calculateNotional(secondPositions.getFirst(), ex2, false);

            double firstNotional = firstPos.getNotional();
            double secondNotional = secondPos.getNotional();

            // Calculate delta
            double sizeDelta = Math.abs(firstPos.getSize() - secondPos.getSize());
            double sizeDeltaPercent = (sizeDelta / expectedSize) * 100;

            double notionalDiff = Math.abs(firstNotional - secondNotional);
            double notionalDiffPercent = (notionalDiff / secondNotional) * 100;

            log.info("[FundingBot] Delta-Neutral Verification");
            log.info("{}: {} {} @ ${} = ${} notional",
                    ex1.getName(),
                    String.format("%.4f", firstPos.getSize()),
                    signal.getTicker(),
                    String.format("%.6f", firstPos.getPrice()),
                    String.format("%.2f", firstPos.getNotional()));
            log.info("{}: {} {} @ ${} = ${} notional",
                    ex2.getName(),
                    String.format("%.4f", secondPos.getSize()),
                    signal.getTicker(),
                    String.format("%.6f", secondPos.getPrice()),
                    String.format("%.2f", secondPos.getNotional()));
            log.info("Size delta: {} {} ({}%)",
                    String.format("%.4f", sizeDelta),
                    signal.getTicker(),
                    String.format("%.2f", sizeDeltaPercent));
            log.info("Notional diff: ${} ({}%)",
                    String.format("%.2f", notionalDiff),
                    String.format("%.2f", notionalDiffPercent));

            if (sizeDeltaPercent < 1.0) {
                log.info("Great delta-neutrality (<1% difference)");
            } else if (sizeDeltaPercent < 5.0) {
                log.info("Good delta-neutrality (<5% difference)");
            } else if (sizeDeltaPercent < 10.0) {
                log.warn("Acceptable delta-neutrality (5-10% difference)");
            } else {
                log.warn("Bad delta-neutrality (>10% difference) - check prices!");
            }

        } catch (Exception e) {
            log.error("[FundingBot] Error verifying delta-neutrality", e);
        }
    }

    /**
     * Calculations
     */

    //Notional and fee calculation
    private PositionNotionalData calculateNotional(Position position, Exchange ex, boolean isClosing) {
        double size = position.getSize();
        double price = isClosing
                ? (position.getMarkPrice())
                : (position.getEntryPrice());

        double notional = size * price;
        double fee = notional * ex.getTakerFee();

        return new PositionNotionalData(notional, fee, price, size);
    }

    //P&L calculations
    public PositionPnLData calculateCurrentPnL(FundingCloseSignal signal) {

        PositionPnLData pnlData = positionDataMap.get(signal.getId());
        if (pnlData == null) {
            log.warn("[FundingBot] No P&L data for position {}", signal.getId());
            return null;
        }

        Exchange ex1 = signal.getFirstExchange();
        Exchange ex2 = signal.getSecondExchange();

        try {
            //Checking First exchange position
            List<Position> firstPositions = ex1.getPositions(signal.getTicker(), signal.getFirstPosition().getDirection());

            if (Objects.isNull(firstPositions) || firstPositions.isEmpty()) {
                log.warn("[FundingBot] {} position not found", ex1.getName());
            }

            //Getting data from OrderBook for better calculation
            double firstSize = firstPositions.getFirst().getSize();
            double firstEntryPrice = firstPositions.getFirst().getEntryPrice();
            double firstMarkPrice = firstPositions.getFirst().getMarkPrice();
            boolean isFirstLong = signal.getFirstPosition().getDirection() == Direction.LONG;

            double firstEffectivePrice;
            String firstPriceSource;

            //Order Book request
            OrderBook firstBook = ex1.getOrderBook(signal.getTicker());

            if (Objects.nonNull(firstBook) && Objects.nonNull(firstBook.getBids()) && !firstBook.getBids().isEmpty()
                    && Objects.nonNull(firstBook.getAsks()) && !firstBook.getAsks().isEmpty()) {

                //Getting Bid and Ask prices
                double firstBidPrice = firstBook.getBids().getFirst().getPrice();
                double firstAskPrice = firstBook.getAsks().getFirst().getPrice();

                // LONG closing SELL → using BID
                // SHORT closing BUY → using ASK
                firstEffectivePrice = isFirstLong ? firstBidPrice : firstAskPrice;
                firstPriceSource = "OrderBook";

                double ex1Spread = ((firstAskPrice - firstBidPrice) / firstMarkPrice) * 100;

                log.info("[{}] Order Book: mark={}, bid={}, ask={}, spread={}%, effective={}",
                        ex1.getName(),
                        String.format("%.6f", firstMarkPrice),
                        String.format("%.6f", firstBidPrice),
                        String.format("%.6f", firstAskPrice),
                        String.format("%.3f", ex1Spread),
                        String.format("%.6f", firstEffectivePrice));

            } else {
                //If Order Book unavailable → using slippage instead
                log.warn("[{}] Order Book unavailable for {}, using fixed slippage", ex1.getName(), signal.getTicker());

                double firstSlippage = 0.004; // 0.4% fallback slippage
                firstEffectivePrice = isFirstLong
                        ? firstMarkPrice * (1 - firstSlippage)  // BID estimate
                        : firstMarkPrice * (1 + firstSlippage);  // ASK estimate
                firstPriceSource = "MarkPrice+Slippage";

                log.info("[{}] Fallback: mark={}, slippage={}%, effective={}",
                        ex1.getName(),
                        String.format("%.6f", firstMarkPrice),
                        String.format("%.1f", firstSlippage * 100),
                        String.format("%.6f", firstEffectivePrice));
            }

            //Calculating PnL
            double firstCalculatedPnl = isFirstLong
                    ? firstSize * (firstEffectivePrice - firstEntryPrice)
                    : firstSize * (firstEntryPrice - firstEffectivePrice);

            double firstApiPnl = firstPositions.getFirst().getUnrealizedPnl();
            double firstSlippageImpact = firstCalculatedPnl - firstApiPnl;

            pnlData.setFirstUnrealizedPnl(firstCalculatedPnl);

            log.info("[{}] P&L: size={}, entry={}, effective={} ({})",
                    ex1.getName(),
                    String.format("%.4f", firstSize),
                    String.format("%.6f", firstEntryPrice),
                    String.format("%.6f", firstEffectivePrice),
                    firstPriceSource);
            log.info("[{}] P&L: Calculated=${} (realistic), API=${}, Slippage Impact=${}",
                    ex1.getName(),
                    String.format("%.4f", firstCalculatedPnl),
                    String.format("%.4f", firstApiPnl),
                    String.format("%.4f", firstSlippageImpact));

            //Second exchange calculations
            List<Position> secondPositions = ex2.getPositions(signal.getTicker(), signal.getSecondPosition().getDirection());

            if (Objects.isNull(secondPositions) || secondPositions.isEmpty()) {
                log.warn("[FundingBot] {} position not found", ex2.getName());
            }

            log.info("[FundingBot] {} position data: {}", ex2.getName(), secondPositions);

            //Getting data from book ticker
            double secondSize = secondPositions.getFirst().getSize();
            double secondEntryPrice = secondPositions.getFirst().getEntryPrice();
            double secondMarkPrice = secondPositions.getFirst().getMarkPrice();
            boolean isSecondLong = signal.getSecondPosition().getDirection() == Direction.LONG;

            double secondEffectivePrice;
            String secondPriceSource;

            //Sending book ticker request
            OrderBook secondOrderBook = ex2.getOrderBook(signal.getTicker());

            if (Objects.nonNull(secondOrderBook) && Objects.nonNull(secondOrderBook.getBids())
                    && Objects.nonNull(secondOrderBook.getAsks())) {

                //Using real bid\ask
                double secondBidPrice = secondOrderBook.getBids().getFirst().getPrice();
                double secondAskPrice = secondOrderBook.getAsks().getFirst().getPrice();

                // LONG closing SELL → using BID
                // SHORT closing BUY → using ASK
                secondEffectivePrice = isSecondLong ? secondBidPrice : secondAskPrice;
                secondPriceSource = "OrderBook";

                double secondSpread = ((secondAskPrice - secondBidPrice) / secondMarkPrice) * 100;

                log.info("[{}] Book Ticker: mark={}, bid={}, ask={}, spread={}%, effective={}",
                        ex2.getName(),
                        String.format("%.6f", secondMarkPrice),
                        String.format("%.6f", secondBidPrice),
                        String.format("%.6f", secondAskPrice),
                        String.format("%.3f", secondSpread),
                        String.format("%.6f", secondEffectivePrice));

            } else {
                //Using slippage if book ticker unavailable
                log.warn("[{}] Book Ticker unavailable for {}, using fixed slippage", ex2.getName(), signal.getTicker());

                double secondSlippage = 0.004; // 0.4% fallback slippage
                secondEffectivePrice = isSecondLong
                        ? secondMarkPrice * (1 - secondSlippage)  // BID estimate
                        : secondMarkPrice * (1 + secondSlippage);  // ASK estimate
                secondPriceSource = "MarkPrice+Slippage";

                log.info("[{}] Fallback: mark={}, slippage={}%, effective={}",
                        ex2.getName(),
                        String.format("%.6f", secondMarkPrice),
                        String.format("%.1f", secondSlippage * 100),
                        String.format("%.6f", secondEffectivePrice));
            }

            //PnL calculation
            double secondCalculatedPnl = isSecondLong
                    ? secondSize * (secondEffectivePrice - secondEntryPrice)
                    : secondSize * (secondEntryPrice - secondEffectivePrice);

            double secondApiPnl = secondPositions.getFirst().getUnrealizedPnl();
            double secondSlippageImpact = secondCalculatedPnl - secondApiPnl;

            pnlData.setSecondUnrealizedPnl(secondCalculatedPnl);

            log.info("[{}] P&L: size={}, entry={}, effective={} ({})",
                    ex2.getName(),
                    String.format("%.4f", secondSize),
                    String.format("%.6f", secondEntryPrice),
                    String.format("%.6f", secondEffectivePrice),
                    secondPriceSource);
            log.info("[{}] P&L: Calculated=${} (realistic), API=${}, Slippage Impact=${}",
                    ex2.getName(),
                    String.format("%.4f", secondCalculatedPnl),
                    String.format("%.4f", secondApiPnl),
                    String.format("%.4f", secondSlippageImpact));

            //Closing fees
            PositionNotionalData firstCloseData = calculateNotional(
                    firstPositions.getFirst(),
                    ex1,
                    true
            );

            PositionNotionalData secondCloseData = calculateNotional(
                    secondPositions.getFirst(),
                    ex2,
                    true
            );

            double totalCloseFees = firstCloseData.getFee() + secondCloseData.getFee();
            pnlData.setTotalCloseFees(totalCloseFees);

            //Totals
            pnlData.calculateTotals();

            log.info("[FundingBot] {} P&L Summary:", signal.getId());
            log.info("  {}:      ${} ({})",
                    ex1.getName(),
                    String.format("%.4f", pnlData.getFirstUnrealizedPnl()),
                    firstPriceSource);
            log.info("  {}:         ${} ({})",
                    ex2.getName(),
                    String.format("%.4f", pnlData.getSecondUnrealizedPnl()),
                    secondPriceSource);
            log.info("  Gross P&L:     ${}", String.format("%.4f", pnlData.getGrossPnl()));
            log.info("  Funding:       ${}", String.format("%.4f", pnlData.getTotalFundingNet()));
            log.info("  Open Fees:     ${}", String.format("%.4f", pnlData.getTotalOpenFees()));
            log.info("  Close Fees:    ${}", String.format("%.4f", pnlData.getTotalCloseFees()));
            log.info("  Net P&L:       ${}", String.format("%.4f", pnlData.getNetPnl()));
            log.info("  Total Slippage Impact: ${}",
                    String.format("%.4f", firstSlippageImpact + secondSlippageImpact));

            return pnlData;

        } catch (Exception e) {
            log.error("[FundingBot] Error calculating current P&L for {}", signal.getId(), e);
            return null;
        }
    }

    //Events
    private void publishFailureEvent(String positionId, FundingOpenSignal signal, String errorMsg, double balance, boolean success) {
        eventPublisher.publishEvent(new PositionOpenedEvent(
                positionId,
                signal.getTicker(),
                errorMsg,
                balance,
                signal.getFirstPosition().getDirection().toString(),
                signal.getSecondPosition().getDirection().toString(),
                signal.getMode().equals(HoldingMode.FAST_MODE) ? "Fast mode" : "Smart mode",
                success,
                signal.getRate()
        ));
    }

    /**
     * Utils
     */

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

    public Map<String, FundingCloseSignal> getOpenedPositions() {
        return Collections.unmodifiableMap(openedPositions);
    }

    public Map<String, PositionBalance> getTrades() {
        return Collections.unmodifiableMap(balanceMap);
    }

    private boolean shouldCloseSmart(FundingCloseSignal pos, double currentSpread, ArbitrageRates currentRate) {
        //Checking directions flip
        if (!pos.getAction().equalsIgnoreCase(currentRate.getAction())) {
            log.info("[FundingBot] Funding rate flipped! Spread={}, held={}min",
                    currentSpread, getHeldMinutes(pos));
            pos.setBadStreak(pos.getBadStreak() + 1);
        }

        //Min rate allowed
        double threshold = fundingConfig.getSmart().getCloseThreshold();

        if (currentSpread <= threshold) {
            pos.setBadStreak(pos.getBadStreak() + 1);
            log.debug("[FundingBot] Bad spread: {} <= {}, streak={}",
                    currentSpread, threshold, pos.getBadStreak());
            pos.setBadStreak(pos.getBadStreak() + 1);
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

    private void updateFunding(FundingCloseSignal signal) {
        PositionPnLData pnlData = calculateCurrentPnL(signal);

        double firstExchangeFunding = signal.getFirstExchange().calculateFunding(signal.getTicker(), signal.getFirstPosition().getDirection(), signal, pnlData.getFirstFundingNet());
        double secondExchangeFunding = signal.getSecondExchange().calculateFunding(signal.getTicker(), signal.getSecondPosition().getDirection(), signal, pnlData.getSecondFundingNet());
        log.info("[FundingBot] Got funding fees from both exchanges: {}, {}", firstExchangeFunding, secondExchangeFunding);

        pnlData.setFirstFundingNet(firstExchangeFunding);
        pnlData.setSecondFundingNet(secondExchangeFunding);
        pnlData.calculateTotals();
    }

    public void closeAllPositions() {
        if (openedPositions.isEmpty()) {
            log.debug("[FundingBot] No positions to close");
            return;
        }

        StringBuilder finalList = new StringBuilder();

        for (FundingCloseSignal signal : openedPositions.values()) {
            signal.setClosureReason("Manual closing");
            finalList.append(closePositions(signal));
        }

        openedPositions.clear();
        log.info("[FundingBot] All positions closed, queue cleared \n");
        log.info("[FundingBot] Positions:\n{}", finalList);
    }

    public void closePositionById(String positionId) {
        FundingCloseSignal signal = openedPositions.get(positionId);

        if (signal == null) {
            log.warn("[FundingBot] Position {} not found", positionId);
            return;
        }

        log.info("[FundingBot] Manual close requested for position {}: {}",
                positionId, signal.getTicker());

        signal.setClosureReason("Manual closing");
        closePositions(signal);

        openedPositions.remove(positionId);
        log.info("[FundingBot] Position {} closed manually", positionId);
    }

    private void setupSlTpOrders(FundingCloseSignal position) {
        try {
            log.info("[FundingBot] Setting up orders for position {}", position.getId());

            setupSlTpForExchange(
                    position.getFirstExchange(),
                    position.getFirstPosition().getDirection(),
                    position.getTicker()
            );

            setupSlTpForExchange(
                    position.getSecondExchange(),
                    position.getSecondPosition().getDirection(),
                    position.getTicker()
            );

        } catch (Exception e) {
            log.error("[FundingBot] Failed to setup SL/TP orders for position {}",
                    position.getId(), e);
        }
    }

    private void setupSlTpForExchange(Exchange exchange, Direction direction, String ticker) {
        if (!exchange.supportsSlTp()) {
            log.info("[FundingBot] {} doesn't support SL/TP, skipping", exchange.getName());
            return;
        }

        try {
            List<Position> positions = exchange.getPositions(ticker, direction);

            if (positions == null || positions.isEmpty()) {
                log.warn("[FundingBot] Position not found on {}", exchange.getName());
                return;
            }

            Position position = positions.getFirst();
            double entryPrice = position.getEntryPrice();

            double slPercent = fundingConfig.getSltp().getStopLossPercent() / 100.0;
            double tpPercent = fundingConfig.getSltp().getTakeProfitPercent() / 100.0;

            double stopLossPrice;
            double takeProfitPrice;

            if (direction == Direction.LONG) {
                stopLossPrice = entryPrice * (1 - slPercent);
                takeProfitPrice = entryPrice * (1 + tpPercent);
            } else {
                stopLossPrice = entryPrice * (1 + slPercent);
                takeProfitPrice = entryPrice * (1 - tpPercent);
            }

            log.info("[FundingBot] {} {} Entry={}, SL={} (-{}%), TP={} (+{}%)",
                    exchange.getName(),
                    direction,
                    String.format("%.6f", entryPrice),
                    String.format("%.6f", stopLossPrice),
                    String.format("%.2f", fundingConfig.getSltp().getStopLossPercent()),
                    String.format("%.6f", takeProfitPrice),
                    String.format("%.2f", fundingConfig.getSltp().getTakeProfitPercent())
            );

            String slOrderId = exchange.placeStopLoss(ticker, direction, stopLossPrice);
            log.info("[FundingBot] {} Stop Loss placed: {}", exchange.getName(), slOrderId);

            String tpOrderId = exchange.placeTakeProfit(ticker, direction, takeProfitPrice);
            log.info("[FundingBot] {} Take Profit placed: {}", exchange.getName(), tpOrderId);

        } catch (Exception e) {
            log.error("[FundingBot] Failed to setup SL/TP for {} {}",
                    exchange.getName(), ticker, e);
        }
    }

    //Checking positions in order of some of them got liquidated
    public boolean checkOpenedPositions(FundingCloseSignal signal) {
        Exchange firstExchange = signal.getFirstExchange();
        Exchange secondExchange = signal.getSecondExchange();

        List<Position> firstPositions = firstExchange.getPositions(signal.getTicker(), signal.getFirstPosition().getDirection());

        if (Objects.isNull(firstPositions) || firstPositions.isEmpty()) {
            log.error("[FundingBot] {} position Closed/Liquidated for {}! Closing hedge on {}",
                    firstExchange.getName(),
                    signal.getTicker(),
                    secondExchange.getName());

            signal.setClosureReason("Order/Liquidation trigger");
            closePositions(signal);
            return true;
        }

        //Validating position
        Position firstPosition = firstPositions.getFirst();

        if (firstPosition.getSize() <= 0) {
            log.error("[FundingBot] {} position Closed/Liquidated for {}! Closing hedge on {}",
                    firstExchange.getName(),
                    signal.getTicker(),
                    secondExchange.getName());

            signal.setClosureReason("Order/Liquidation trigger");
            closePositions(signal);
            return true;
        }

        List<Position> secondPositions = secondExchange.getPositions(signal.getTicker(), signal.getSecondPosition().getDirection());

        if (Objects.isNull(secondPositions) || secondPositions.isEmpty()) {
            log.error("[FundingBot] {} position Closed/Liquidated for {}! Closing hedge on {}",
                    secondExchange.getName(),
                    signal.getTicker(),
                    firstExchange.getName());

            signal.setClosureReason("Order/Liquidation trigger");
            closePositions(signal);
            return true;
        }

        Position secondPosition = secondPositions.getFirst();

        if (secondPosition.getSize() <= 0) {
            if (log.isErrorEnabled()) {
                log.error("[FundingBot] {} position Closed/Liquidated for {}! Closing hedge on {}",
                        secondExchange.getName(),
                        signal.getTicker(),
                        firstExchange.getName());
            }
            signal.setClosureReason("Order/Liquidation trigger");
            closePositions(signal);
            return true;
        }

        return false;
    }

    private String generatePositionId() {
        long id = positionIdCounter.getAndIncrement();
        return String.format("P-%04d", id);
    }

    private void rollbackPositionId() {
        long rolledBack = positionIdCounter.decrementAndGet();
        log.debug("[FundingBot] Position ID counter rolled back to: {}", rolledBack);
    }

    public PositionPnLData pnlPositionCalculator(String posId) {
        return calculateCurrentPnL(openedPositions.get(posId));
    }

    private void checkPnLThreshold(FundingCloseSignal signal, PositionPnLData pnlData) {
        if (!fundingConfig.getPnl().isEnableNotifications()) {
            return;
        }

        if (notifiedPositions.contains(signal.getId())) {
            return;
        }

        //Grace period check
        long positionAgeMinutes = TimeUnit.MILLISECONDS.toMinutes(
                System.currentTimeMillis() - signal.getOpenedAtMs()
        );

        long gracePeriodMinutes = 10; // First 10 min - no notis

        if (positionAgeMinutes < gracePeriodMinutes) {
            log.debug("[FundingBot] {} P&L check skipped - grace period (age: {}min, grace: {}min)",
                    signal.getId(), positionAgeMinutes, gracePeriodMinutes);
            return;
        }

        double netPnl = pnlData.getNetPnl();
        double marginUsed = signal.getBalance();

        double profitPercent = (netPnl / marginUsed) * 100;

        double thresholdPercent = fundingConfig.getPnl().getThresholdPercent();

        //Checking threshold
        if (profitPercent >= thresholdPercent) {
            log.info("[FundingBot] 🎯 P&L Threshold reached for {}: {}% (threshold: {}%)",
                    signal.getId(),
                    String.format("%.2f", profitPercent),
                    String.format("%.2f", thresholdPercent));

            //Sending notis
            String mode = signal.getMode().equals(HoldingMode.FAST_MODE) ? "Fast mode" : "Smart mode";

            eventPublisher.publishEvent(new PnLThresholdEvent(
                    signal.getId(),
                    signal.getTicker(),
                    pnlData,
                    profitPercent,
                    marginUsed,
                    mode
            ));

            log.info("[FundingBot] Profit threshold reached, closing position {}", signal.getId());
            signal.setClosureReason("P&L threshold reached");
            closePositions(signal);

            //Adding to notified
            notifiedPositions.add(signal.getId());

            log.info("[FundingBot] P&L threshold notification sent for {}", signal.getId());
        } else {
            log.debug("[FundingBot] {} P&L: {}% (threshold: {}% - not reached)",
                    signal.getId(),
                    String.format("%.2f", profitPercent),
                    String.format("%.2f", thresholdPercent));
        }
    }
}
