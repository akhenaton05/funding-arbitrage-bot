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
import ru.dto.db.model.Trade;
import ru.dto.exchanges.*;
import ru.dto.funding.*;
import ru.event.NewArbitrageEvent;
import ru.event.PnLThresholdEvent;
import ru.event.PositionUpdateEvent;
import ru.exceptions.ClosingPositionException;
import ru.exceptions.OpeningPositionException;
import ru.exchanges.Exchange;
import ru.exchanges.factory.ExchangeFactory;
import ru.repository.TradeRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private final TradeRepository tradeRepository;

    private final Map<String, FundingCloseSignal> openedPositions = new ConcurrentHashMap<>();
    private final Map<String, PositionBalance> balanceMap = new ConcurrentHashMap<>();
    private final Map<String, PositionPnLData> positionDataMap = new ConcurrentHashMap<>();
    private final Set<String> notifiedPositions = ConcurrentHashMap.newKeySet();
    private final AtomicLong positionIdCounter = new AtomicLong(1);
    private final Set<String> closingInProgress = ConcurrentHashMap.newKeySet();


    /**
     * Listeners & Scheduled tasks
     */
    @EventListener
    @Async
    public void handleArbitrageSignal(NewArbitrageEvent event) {
        log.info("[FundingBot] Received arbitrage signal for {}", event.getSignal().getTicker());

        if (isPositionAlreadyOpen(event.getSignal())) {
            log.warn("[FundingBot] Skipping {}: position already opened on same exchanges", event.getSignal().getTicker());
            return;
        }

        String result = openPositionWithEqualSize(event.getSignal());

        log.info("[FundingBot] Result: {}", result);
    }

    //Closing only Fast mode
    @Scheduled(cron = "10 01 * * * *", zone = "UTC")
    public void closeOnFundingTime() {
        if (openedPositions.isEmpty()) {
            log.debug("[FundingBot] Funding check: no positions to close");
            return;
        }

        log.info("[FundingBot] Funding time! Checking opened positions");

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
            signal.setClosureReason("Fast mode auto closing");
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

        log.info("[FundingBot] Predicting funding for {} positions", openedPositions.size());

        for (FundingCloseSignal signal : openedPositions.values()) {
            try {
                updateFunding(signal);
            } catch (Exception e) {
                log.error("[FundingBot] Failed to predict funding for {}: {}",
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
            if (closingInProgress.contains(signal.getId())) {
                log.debug("[FundingBot] Skipping PnL update for {} - closing in progress", signal.getId());
                continue;
            }

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

    @Scheduled(fixedDelay = 600000) //Every 10 min
    public void checkPositions() {
        if (openedPositions.isEmpty()) {
            return;
        }

        log.debug("[FundingBot] Checking {} opened positions for liquidation/closure",
                openedPositions.size());

        List<String> toRemove = new ArrayList<>();

        for (FundingCloseSignal signal : openedPositions.values()) {

            if (closingInProgress.contains(signal.getId())) {
                log.debug("[FundingBot] Skipping liquidation check for {} - closing in progress", signal.getId());
                continue;
            }

            //Mark/liquidation price checking
            validatePositionRisk(signal);

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
        //Creating exchanges
        Exchange exchangeOne = exchangeFactory.getExchange(signal.getFirstPosition().getExchange());
        Exchange exchangeTwo = exchangeFactory.getExchange(signal.getSecondPosition().getExchange());

        Balance exchangesBalances = validateBalance(exchangeOne, exchangeTwo);
        double marginBalance = exchangesBalances.getMargin();

        //Validating funding time - both ex has to be < 60 mins until payment
        if (!validateFundingTime(signal, exchangeOne, exchangeTwo)) {
            String errorMsg = "[FundingBot] More than an hour until funding, position not opened";
            log.info("[FundingBot] More than an hour until funding, position not opened");

            publishFailureEvent("E-0000", signal, errorMsg, marginBalance, false);
            return errorMsg;
        }

        if (marginBalance <= 10) {
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

        leverage = Math.min(leverage, validateLeverage(signal.getTicker(), exchangeOne, exchangeTwo, leverage));
        log.info("[FundingBot] Min leverage for {}: {}", signal.getTicker(), leverage);

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

        //Rounding
        targetSize = Math.floor(targetSize * 100) / 100.0;

        log.info("[FundingBot] Delta-neutral sizing: {} max: {}, {} max: {}, Target: {} (using minimum)",
                exchangeOne.getName(), String.format("%.4f", firstExchangeSize),
                exchangeTwo.getName(), String.format("%.4f", secondExchangeSize),
                String.format("%.4f", targetSize));

        final int finalLeverage = leverage;
        final double finalTargetSize = targetSize;

        String firstOrderId;
        String secondOrderId;

        //Create order
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

        //Executing open positions
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

        PositionPnLData data = positionDataMap.get(positionId);

        double price1 = data.getFirstOpenPrice();
        double price2 = data.getSecondOpenPrice();

        double spreadPct = Math.abs(price1 - price2) / Math.min(price1, price2) * 100;

        String openInfo = String.format(
                "%s price=%.6f / %s price=%.6f | Spread: %.4f%%",
                signal.getFirstPosition().getExchange().getDisplayName(), price1,
                signal.getSecondPosition().getExchange().getDisplayName(), price2,
                spreadPct
        );

        eventPublisher.publishEvent(PositionOpenedEvent.builder()
                .positionId(positionId)
                .ticker(signal.getTicker())
                .result(successMsg)
                .openInfo(openInfo)
                .balanceUsed(marginBalance)
                .firstDirection(signal.getFirstPosition().getDirection().toString())
                .secondDirection(signal.getSecondPosition().getDirection().toString())
                .mode(mode.equals(HoldingMode.FAST_MODE) ? "Fast mode" : "Smart mode")
                .success(true)
                .rate(signal.getRate())
                .build()
        );

//
//        eventPublisher.publishEvent(new PositionOpenedEvent(
//                positionId,
//                signal.getTicker(),
//                successMsg,
//                marginBalance,
//                signal.getFirstPosition().getDirection().toString(),
//                signal.getSecondPosition().getDirection().toString(),
//                mode.equals(HoldingMode.FAST_MODE) ? "Fast mode" : "Smart mode",
//                true,
//                signal.getRate()
//        ));

        log.info(successMsg);
        return successMsg;
    }

    public String closePositions(FundingCloseSignal signal) {
        if (!closingInProgress.add(signal.getId())) {
            log.warn("[FundingBot] Already closing {}, skipping duplicate call", signal.getId());
            return "Skipped: already closing " + signal.getId();
        }

        try {
            PositionBalance posBalance = balanceMap.get(signal.getId());
            if (posBalance == null) {
                log.error("[FundingBot] No balance data for {}", signal.getId());
                return "No balance data for " + signal.getId();
            }

            double balanceBefore = posBalance.getBalanceBefore();
            log.info("[FundingBot] Balance before closing positions: {}", balanceBefore);

            PositionPnLData pnlDataBefore = null;
            try {
                pnlDataBefore = calculateCurrentPnL(signal);
            } catch (Exception e) {
                log.warn("[FundingBot] Pre-close PnL failed (non-critical): {}", e.getMessage());
            }

            CompletableFuture<OrderResult> firstFuture = CompletableFuture.supplyAsync(() ->
                    signal.getFirstExchange().closePosition(signal.getTicker(), signal.getFirstPosition().getDirection())
            );

            CompletableFuture<OrderResult> secondFuture = CompletableFuture.supplyAsync(() ->
                    signal.getSecondExchange().closePosition(signal.getTicker(), signal.getSecondPosition().getDirection())
            );

            double currentSpread = 0.0;

            try {
                CompletableFuture.allOf(firstFuture, secondFuture).get(30, TimeUnit.SECONDS);

                double finalPnL = 0.0;
                try {
                    OrderResult r1 = firstFuture.get();
                    OrderResult r2 = secondFuture.get();
                    if (r1 != null && r1.getRealizedPnl() != null) finalPnL += r1.getRealizedPnl();
                    if (r2 != null && r2.getRealizedPnl() != null) finalPnL += r2.getRealizedPnl();
                    log.info("[FundingBot] Final PnL from API: {}, no funding fees applied", finalPnL);
                } catch (Exception e) {
                    log.warn("[FundingBot] Could not extract PnL from OrderResult: {}", e.getMessage());
                }

                if (pnlDataBefore != null) {
                    log.info("[FundingBot] Expected P&L before closing: ${}",
                            String.format("%.4f", pnlDataBefore.getNetPnl()));
                    finalPnL += pnlDataBefore.getTotalFundingNet();
                    log.info("[FundingBot] Final PnL with funding fees: {}", finalPnL);
                }

                Thread.sleep(20000);

                boolean bothClosed = validateClosedPositions(
                        signal.getFirstExchange(),
                        signal.getSecondExchange(),
                        signal
                );

                if (!bothClosed) {
                    signal.setClosureReason("Position was not closed on both or on one exchange");
                    throw new ClosingPositionException("Position closure verification failed for " + signal.getTicker());
                }

                double balanceAfter = signal.getFirstExchange().getBalance().getBalance()
                        + signal.getSecondExchange().getBalance().getBalance();
                log.info("[FundingBot] Balance after closing positions: {}", balanceAfter);
                double profit = balanceAfter - balanceBefore;
                posBalance.setBalanceAfter(balanceAfter);

                double usedMargin = signal.getBalance();
                double profitPercent = (profit / usedMargin) * 100;

                if (pnlDataBefore != null) {
                    double difference = profit - pnlDataBefore.getNetPnl();
                    log.info("[FundingBot] Calculated P&L: ${} | Actual: ${} | API: ${} | Difference: ${} ({}%)",
                            String.format("%.4f", pnlDataBefore.getNetPnl()),
                            String.format("%.4f", profit),
                            String.format("%.4f", finalPnL),
                            String.format("%.4f", difference),
                            String.format("%.2f", Math.abs(difference / profit) * 100));
                }

                log.info("[FundingBot] P&L: ${} ({}%)",
                        String.format("%.4f", profit),
                        String.format("%.2f", profitPercent));

                ArbitrageRates currentRate = getCurrentSpread(signal.getTicker());
                currentSpread = currentRate.getArbitrageRate();
                signal.setCurrentFindingRate(currentSpread);

                eventPublisher.publishEvent(new PositionClosedEvent(
                        signal.getId(),
                        signal.getTicker(),
                        profit,
                        finalPnL,
                        profitPercent,
                        true,
                        signal.getMode().equals(HoldingMode.FAST_MODE) ? "Fast mode" : "Smart mode",
                        currentSpread,
                        signal.getClosureReason()
                ));

                //Saving result to database
                saveResultToDb(signal, finalPnL, pnlDataBefore.getTotalFundingNet());

                notifiedPositions.remove(signal.getId());

                return String.format("[FundingBot] Positions closed. P&L: %.4f USD (%.2f%%)", profit, profitPercent);

            } catch (Exception e) {
                log.error("[FundingBot] Error closing positions for {}: {}",
                        signal.getTicker(), e, e);

                eventPublisher.publishEvent(new PositionClosedEvent(
                        signal.getId(),
                        signal.getTicker(),
                        0, 0, 0,
                        false,
                        signal.getMode().equals(HoldingMode.FAST_MODE) ? "Fast mode" : "Smart mode",
                        signal.getCurrentFindingRate(),
                        signal.getClosureReason()
                ));

                return String.format("[FundingBot] %s Partial close - Manual check Needed!\n", signal.getTicker());
            }

        } finally {
            closingInProgress.remove(signal.getId()); //Always removing id from closeInProgress
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

    private boolean validateFundingTime(FundingOpenSignal signal, Exchange ex1, Exchange ex2) {
        boolean ex1Valid = ex1.isFundingTimeValid(signal.getTicker());
        boolean ex2Valid = ex2.isFundingTimeValid(signal.getTicker());

        if (!ex1Valid || !ex2Valid) {
            Exchange failed = !ex1Valid ? ex1 : ex2;
            long minutes = failed.getMinutesUntilFunding(signal.getTicker());
            log.info("[FundingBot] Funding too far on {}: {} minutes, skipping signal",
                    failed.getName(), minutes);
            return false;
        }

        log.info("[FundingBot] Funding validated correctly.");
        return true;
    }

    public int validateLeverage(String symbol, Exchange ex1, Exchange ex2, int leverage) {
        int firstLev = ex1.getMaxLeverage(symbol, leverage);
        int secondLev = ex2.getMaxLeverage(symbol, leverage);

        return Math.min(firstLev, secondLev);
    }

    public int validateLeverage(String symbol, Exchange ex, int leverage) {

        int asterLeverage = ex.getMaxLeverage(symbol, leverage);

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

    private boolean validateClosedPositions(Exchange ex1, Exchange ex2, FundingCloseSignal signal) {
        log.info("[FundingBot] Validating closure: {}={}, {}={}",
                ex1.getName(), ex1.formatSymbol(signal.getTicker()),
                ex2.getName(), ex2.formatSymbol(signal.getTicker()));

        List<Position> firstPositions = ex1.getPositions(
                signal.getTicker(),
                signal.getFirstPosition().getDirection()
        );
        boolean firstClosed = isPositionClosed(firstPositions);

        if (firstClosed) {
            log.info("[FundingBot] {} position confirmed closed", ex1.getName());
        } else {
            log.warn("[FundingBot] {} position still opened, size={}",
                    ex1.getName(), firstPositions.getFirst().getSize());
        }

        List<Position> secondPositions = ex2.getPositions(
                signal.getTicker(),
                signal.getSecondPosition().getDirection()
        );

        boolean secondClosed = isPositionClosed(secondPositions);

        if (secondClosed) {
            log.info("[FundingBot] {} position confirmed closed", ex2.getName());
        } else {
            log.warn("[FundingBot] {} position still opened, size={}",
                    ex2.getName(), secondPositions.getFirst().getSize());
        }

        if (firstClosed && secondClosed) {
            log.info("[FundingBot] Both positions verified as closed");
            return true;
        }

        log.error("[FundingBot] Closure validation failed! {}={}, {}={}",
                ex1.getName(), firstClosed ? "Closed" : "Opened",
                ex2.getName(), secondClosed ? "Closed" : "Opened");

        if (!firstClosed) {
            log.warn("[FundingBot] Retrying close for {}", ex1.getName());
            try {
                ex1.closePosition(signal.getTicker(), signal.getFirstPosition().getDirection());
                log.info("[FundingBot] {} retry close successful", ex1.getName());
            } catch (Exception e) {
                log.error("[FundingBot] {} retry close failed: {}", ex1.getName(), e.getMessage(), e);
            }
        }

        if (!secondClosed) {
            log.warn("[FundingBot] Retrying close for {}", ex2.getName());
            try {
                ex2.closePosition(signal.getTicker(), signal.getSecondPosition().getDirection());
                log.info("[FundingBot] {} retry close successful", ex2.getName());
            } catch (Exception e) {
                log.error("[FundingBot] {} retry close failed: {}", ex2.getName(), e.getMessage(), e);
            }
        }

        return false;
    }

    private void validatePositionRisk(FundingCloseSignal signal) {
        PositionPnLData pnlData = positionDataMap.get(signal.getId());
        double warnThr = fundingConfig.getLiquidation().getWarn();
        double critThr = fundingConfig.getLiquidation().getCritical();

        checkExchangeRisk(signal.getFirstExchange(), signal.getTicker(),
                signal.getFirstPosition().getDirection(), pnlData, signal, warnThr, critThr);
        checkExchangeRisk(signal.getSecondExchange(), signal.getTicker(),
                signal.getSecondPosition().getDirection(), pnlData, signal, warnThr, critThr);
    }

    private void checkExchangeRisk(Exchange exchange, String ticker, Direction direction,
                                   PositionPnLData pnlData, FundingCloseSignal signal,
                                   double warnThr, double critThr) {
        try {
            PositionRiskControl risk = exchange.validatePositionRisk(ticker, direction);

            if (risk == null || risk.getLiquidationPrice() <= 0) {
                log.debug("[FundingBot] {} Skipping risk check for {} — liq={}",
                        exchange.getName(), ticker,
                        risk != null ? risk.getLiquidationPrice() : "null");
                return;
            }

            double liq = risk.getLiquidationPrice();
            double mark = risk.getMarkPrice();
            double entry = risk.getEntryPrice();

            if (entry <= 0 || mark <= 0) {
                log.debug("[FundingBot] {} Skipping risk check for {} — invalid entry={} or mark={}",
                        exchange.getName(), ticker, entry, mark);
                return;
            }

            boolean isShort = liq > entry;
            double progressToLiq = isShort
                    ? (mark - entry) / (liq - entry) * 100.0
                    : (entry - mark) / (entry - liq) * 100.0;

            log.info("[FundingBot] {} {} liq progress: entry={}, mark={}, liq={}, progress={}% ({})",
                    exchange.getName(), ticker,
                    String.format("%.6f", entry),
                    String.format("%.6f", mark),
                    String.format("%.6f", liq),
                    String.format("%.1f", progressToLiq),
                    isShort ? "SHORT" : "LONG");

            if (progressToLiq < 0) {
                return;
            }

            if (progressToLiq >= warnThr) {
                String level = progressToLiq >= critThr ? "Critical" : "Warning";
                String msg = String.format(
                        "%s: Liquidation risk on %s $%s: %.1f%% progress to liq " +
                                "(entry=%.6f, mark=%.6f, liq=%.6f)",
                        level, exchange.getName(), ticker, progressToLiq, entry, mark, liq
                );
                eventPublisher.publishEvent(PositionUpdateEvent.builder()
                        .message(msg)
                        .pnlData(pnlData)
                        .positionId(signal.getId())
                        .mode(signal.getMode().toString())
                        .ticker(ticker)
                        .build()
                );
                log.warn("[FundingBot] {}", msg);
            }

        } catch (Exception e) {
            log.error("[FundingBot] {} Error getting risk data for {}: {}",
                    exchange.getName(), ticker, e.getMessage());
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
                return null;
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
                return null;
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
            log.info("  Total Slippage Impact: ${}",
                    String.format("%.4f", firstSlippageImpact + secondSlippageImpact));
            log.info("  Net P&L:       ${}", String.format("%.4f", pnlData.getNetPnl()));

            return pnlData;

        } catch (Exception e) {
            log.error("[FundingBot] Error calculating current P&L for {}", signal.getId(), e);
            return null;
        }
    }

    //Events
    private void publishFailureEvent(String positionId, FundingOpenSignal signal, String errorMsg, double balance, boolean success) {
        eventPublisher.publishEvent(PositionOpenedEvent.builder()
                .positionId(positionId)
                .ticker(signal.getTicker())
                .result(errorMsg)
                .openInfo("No info")
                .balanceUsed(balance)
                .firstDirection(signal.getFirstPosition().getDirection().toString())
                .secondDirection(signal.getSecondPosition().getDirection().toString())
                .mode(signal.getMode().equals(HoldingMode.FAST_MODE) ? "Fast mode" : "Smart mode")
                .success(false)
                .rate(signal.getRate())
                .build()
        );
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

    public Map<String, Double> getExchangesBalance() {
        Map<String, Double> balances = new LinkedHashMap<>();
        double total = 0.0;

        for (Exchange exchange : exchangeFactory.getAllExchanges()) {
            try {
                double balance = exchange.getBalance().getBalance();
                balances.put(exchange.getName(), balance);
                total += balance;
            } catch (Exception e) {
                log.error("[FundingBot] Error getting balance for {}: {}",
                        exchange.getType(), e.getMessage());
                balances.put(exchange.getType().name(), 0.0);
            }
        }

        balances.put("TOTAL", total);
        return balances;
    }

    private boolean shouldCloseSmart(FundingCloseSignal pos, double currentSpread, ArbitrageRates currentRate) {

        boolean rateFlipped = isRateFlipped(pos, currentRate);

        if (rateFlipped) {
            String message = "Funding Rate flipped! Spread: " + currentSpread;
            log.info("[FundingBot] {}", message);
            PositionPnLData pnLData = positionDataMap.get(pos.getId());
            eventPublisher.publishEvent(PositionUpdateEvent.builder()
                    .message(message)
                    .pnlData(pnLData)
                    .positionId(pos.getId())
                    .mode(pos.getMode().toString())
                    .ticker(pos.getTicker())
                    .build()
            );
        }

        double threshold = fundingConfig.getSmart().getCloseThreshold();

        if (currentSpread <= threshold) {
            log.info("[FundingBot] Bad spread: {} <= {}, streak={}",
                    currentSpread, threshold, pos.getBadStreak());
            PositionPnLData pnLData = positionDataMap.get(pos.getId());
            eventPublisher.publishEvent(PositionUpdateEvent.builder()
                    .message("Spread is low: " + currentSpread)
                    .pnlData(pnLData)
                    .positionId(pos.getId())
                    .mode(pos.getMode().toString())
                    .ticker(pos.getTicker())
                    .build()
            );
        }

        return false;
    }

    private boolean isRateFlipped(FundingCloseSignal pos, ArbitrageRates currentRate) {
        ExchangeType posFirst = pos.getFirstExchange().getType();
        Direction posFirstDir = pos.getFirstPosition().getDirection();

        ExchangeType currentShortExchange = currentRate.getFirstRate() > currentRate.getSecondRate()
                ? currentRate.getFirstExchange()
                : currentRate.getSecondExchange();

        boolean openedFirstWasShort = posFirstDir == Direction.SHORT;
        boolean currentFirstIsShort = currentShortExchange == posFirst;

        return openedFirstWasShort != currentFirstIsShort;
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

    private void saveResultToDb(FundingCloseSignal signal, double pnl, double funding) {
        Trade trade =  Trade.builder()
                .positionId(signal.getId())
                .ticker(signal.getTicker())
                .exchange(signal.getFirstExchange().getName() + "/" + signal.getSecondExchange().getName())
                .volume(signal.getBalance() * 5) //leverage
                .funding(funding)
                .pnl(pnl)
                .openedFundingRate(signal.getOpenedFundingRate())
                .closedFindingRate(signal.getCurrentFindingRate())
                .openedAt(Instant.ofEpochMilli(signal.getOpenedAtMs())
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime())
                .closedAt(LocalDateTime.now())
                .build();

        try {
            tradeRepository.save(trade);
            log.info("[FundingBot] Trade saved to DB: {}", trade);
        } catch (Exception e) {
            log.error("[FundingBot] Failed to save trade to DB: positionId={}, pnl={}, error={}",
                    signal.getId(), pnl, e.getMessage());
        }
    }

    private boolean isPositionClosed(List<Position> positions) {
        if (positions == null || positions.isEmpty()) return true;
        Position pos = positions.getFirst();
        if (pos == null) return true;
        return pos.getSize() == 0.0;
    }

    //Checking if position with ticker or any exchange already opened
    private boolean isPositionAlreadyOpen(FundingOpenSignal signal) {
        String ticker = signal.getTicker();
        ExchangeType incomingFirst = signal.getFirstPosition().getExchange();
        ExchangeType incomingSecond = signal.getSecondPosition().getExchange();

        for (FundingCloseSignal opened : openedPositions.values()) {
            if (!opened.getTicker().equals(ticker)) {
                continue;
            }

            ExchangeType openedFirst = opened.getFirstExchange().getType();
            ExchangeType openedSecond = opened.getSecondExchange().getType();

            boolean firstOccupied = incomingFirst == openedFirst || incomingFirst == openedSecond;
            boolean secondOccupied = incomingSecond == openedFirst || incomingSecond == openedSecond;

            if (firstOccupied || secondOccupied) {
                log.warn("[FundingBot] Skipping {} — exchange already opened for this ticker. " +
                                "Incoming: [{}, {}], Occupied by {}: [{}, {}]",
                        ticker,
                        incomingFirst, incomingSecond,
                        opened.getId(),
                        openedFirst, openedSecond);
                return true;
            }
        }

        return false;
    }


//    private boolean isPositionAlreadyOpen(FundingOpenSignal signal) {
//        for (FundingCloseSignal opened : openedPositions.values()) {
//            boolean sameTicker = opened.getTicker().equals(signal.getTicker());
//            boolean sameExchanges = opened.getFirstExchange().getType() == signal.getFirstPosition().getExchange()
//                            && opened.getSecondExchange().getType() == signal.getSecondPosition().getExchange()
//                            || opened.getFirstExchange().getType() == signal.getSecondPosition().getExchange()
//                            && opened.getSecondExchange().getType() == signal.getFirstPosition().getExchange();
//
//            if (sameTicker && sameExchanges) {
//                return true;
//            }
//        }
//        return false;
//    }
}
