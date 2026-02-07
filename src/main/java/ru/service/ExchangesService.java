package ru.service;

import lombok.AllArgsConstructor;
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
import ru.dto.exchanges.aster.AsterBookTicker;
import ru.dto.exchanges.aster.AsterPosition;
import ru.dto.exchanges.aster.PremiumIndexResponse;
import ru.dto.exchanges.extended.ExtendedFundingHistoryResponse;
import ru.dto.exchanges.extended.ExtendedOrderBook;
import ru.dto.exchanges.extended.ExtendedPosition;
import ru.dto.funding.ArbitrageRates;
import ru.dto.funding.HoldingMode;
import ru.dto.funding.PositionNotionalData;
import ru.dto.funding.PositionPnLData;
import ru.event.NewArbitrageEvent;
import ru.event.PnLThresholdEvent;
import ru.exceptions.BalanceException;
import ru.exceptions.ClosingPositionException;
import ru.exceptions.OpeningPositionException;

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

    //Exchanges fees(for p&l calculation)
    private static final double ASTER_MAKER_FEE = 0.00005;  // 0.005%
    private static final double ASTER_TAKER_FEE = 0.0004;   // 0.04%
    private static final double EXTENDED_MAKER_FEE = 0.0;   // 0%
    private static final double EXTENDED_TAKER_FEE = 0.00025; // 0.025%

    private final AsterClient asterClient;
    private final ExtendedClient extendedClient;
    private final ApplicationEventPublisher eventPublisher;
    private final FundingArbitrageService fundingArbitrageService;
    private final FundingConfig fundingConfig;

    private final Map<String, FundingCloseSignal> openedPositions = new ConcurrentHashMap<>();
    private final Map<String, PositionBalance> balanceMap = new ConcurrentHashMap<>();
    private final Map<String, PositionPnLData> positionDataMap = new ConcurrentHashMap<>();
    private final Set<String> notifiedPositions = ConcurrentHashMap.newKeySet();
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
            }
        } else {
            log.info("[FundingBot] SmartMode - no position to close, position hold continued");
        }
    }

    //Calculating and sum for extended funding(real data)
    @Scheduled(cron = "30 00 * * * *")
    private void collectExtendedFundingHistory() {
        log.info("[FundingBot] Starting Extended funding history collection");

        if (openedPositions.isEmpty()) {
            log.debug("[FundingBot] No positions to collect funding for");
            return;
        }

        for (FundingCloseSignal signal : openedPositions.values()) {
            try {
                updateExtendedFundingForPosition(signal);
            } catch (Exception e) {
                log.error("[FundingBot] Error collecting funding for position {}: {}",
                        signal.getId(), e.getMessage(), e);
            }
        }

        log.info("[FundingBot] Funding collection for Extended completed");
    }

    // Calculating predicted funding for aster
    @Scheduled(cron = "0 00 * * * *", zone = "UTC")
    private void predictAsterFunding() {
        if (openedPositions.isEmpty()) {
            log.debug("[FundingBot] No open positions");
            return;
        }

        log.info("[FundingBot] Predicting Aster funding for {} positions", openedPositions.size());

        for (FundingCloseSignal signal : openedPositions.values()) {
            try {
                addAsterFundingPrediction(signal);
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

    //Main logic

    public String openPosition(FundingOpenSignal signal) {
        double marginBalance = validateBalance();

        //Validating data for opening position
        if (!validateFundingTime(signal)) {
            String errorMsg = "[FundingBot] More than an hour until funding, position not opened";
            log.info("[FundingBot] More than an hour until funding, position not opened");

            publishFailureEvent("P-0000", signal, errorMsg, marginBalance, false);
            return errorMsg;
        }

        if (marginBalance <= 10) {
            String errorMsg = "[FundingBot] No balance available to open position: " + marginBalance;
            log.info("[FundingBot] No balance available to open position: {}", marginBalance);

            publishFailureEvent("P-0000", signal, errorMsg, marginBalance, false);
            return errorMsg;
        }

        String positionId = generatePositionId();
        double balanceBefore = asterClient.getBalance() + extendedClient.getBalance();
        HoldingMode mode = signal.getMode();

        PositionBalance positionBalance = new PositionBalance();
        positionBalance.setBalanceBefore(balanceBefore);

        int leverage = Math.min(signal.getLeverage(), validateLeverage(signal.getTicker()));

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
                    leverage,
                    signal.getExtendedDirection().toString()
            );

            if (extendedOrderId == null) {
                throw new OpeningPositionException("[Extended] Failed to open position - returned null");
            }

            log.info("[Extended] Successfully created order with ID {}", extendedOrderId);

        } catch (Exception e) {
            String errorMsg = "[Extended] Error creating position: " + e.getMessage();
            log.error(errorMsg, e);

            publishFailureEvent(positionId, signal, errorMsg, marginBalance, false);

            return errorMsg;
        }

        //Aster opening position
        try {
            astSymbol = signal.getTicker() + "USDT";
            asterOrderId = asterClient.openPositionWithFixedMargin(
                    astSymbol,
                    marginBalance,
                    leverage,
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

            publishFailureEvent(positionId, signal, errorMsg, marginBalance, false);

            return errorMsg;
        }

        //Waiting 4 sec before validation opened positions
        try {
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[FundingBot] Interrupted during sleep", e);
        }

        // Validation for opened positions
        try {
            if (!validateOpenedPositions(extSymbol, astSymbol, signal, positionId)) {
                log.error("[FundingBot] Position validation failed - positions were closed");

                //Calculations losses/profits
                double balanceAfter = asterClient.getBalance() + extendedClient.getBalance();
                double balanceLoss = balanceBefore - balanceAfter;

                String errorMsg = "[FundingBot] Position validation failed - all positions closed with delta " + balanceLoss;

                publishFailureEvent(positionId, signal, errorMsg, balanceLoss, false);
                return errorMsg;
            }
        } catch (ClosingPositionException e) {
            log.error("[FundingBot] Failed to close positions during validation! Manual check required!", e);

            String errorMsg = "[FundingBot] Failed to close positions during validation! Manual check required!\n"
                    + e.getMessage();

            publishFailureEvent(positionId, signal, errorMsg, marginBalance, false);
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
                .openedFundingRate(signal.getRate())
                .action(signal.getAction())
                .mode(mode)
                .openedAtMs(System.currentTimeMillis())
                .openSpread(0.0)
                .badStreak(0)
                .build();

        openedPositions.put(positionToClose.getId(), positionToClose);

        //Funding validation for extended
        PositionPnLData pnlData = positionDataMap.get(positionId);
        if (pnlData != null) {
            try {
                ExtendedFundingHistoryResponse history = extendedClient.getFundingHistory(
                        extSymbol,
                        signal.getExtendedDirection().toString(),
                        positionToClose.getOpenedAtMs(),
                        1000
                );

                if (history != null && history.getSummary() != null) {
                    double initialFunding = history.getSummary().getNetFunding();
                    pnlData.setInitialExtFunding(initialFunding);
                    pnlData.setExtendedFundingNet(0.0);
                    pnlData.calculateTotals();

                    log.info("[FundingBot] {} Extended funding baseline set at position open: ${} (will be subtracted from future readings)",
                            positionId, String.format("%.4f", initialFunding));
                } else {
                    log.warn("[FundingBot] Failed to get Extended funding baseline for {}, will retry on next cycle",
                            positionId);
                    pnlData.setInitialExtFunding(0.0);
                    pnlData.setExtendedFundingNet(0.0);
                }
            } catch (Exception e) {
                log.error("[FundingBot] Error getting Extended funding baseline for {}: {}",
                        positionId, e.getMessage());
                pnlData.setInitialExtFunding(0.0);
                pnlData.setExtendedFundingNet(0.0);
            }
        } else {
            log.error("[FundingBot] P&L data not found for {} after validation!", positionId);
        }

        String successMsg = "[FundingBot] Successfully opened positions | Extended: " + extendedOrderId +
                " | Aster: " + asterOrderId + " | Mode: " + mode + ". Waiting for funding fees.";

        //Sending Telegram notification with observer
        eventPublisher.publishEvent(new PositionOpenedEvent(
                positionId,
                signal.getTicker(),
                successMsg,
                marginBalance,
                signal.getExtendedDirection().toString(),
                signal.getAsterDirection().toString(),
                mode.equals(HoldingMode.FAST_MODE) ? "Fast mode" : "Smart mode",
                true,
                signal.getRate()
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

        double currentSpread = 0.0;

        try {
            //Closing both positions at the same time
            CompletableFuture.allOf(extFuture, astFuture).get(30, TimeUnit.SECONDS);

            //Calculating closing pnl
            PositionPnLData pnlDataBefore = calculateCurrentPnL(signal);
            if (pnlDataBefore != null) {
                log.info("[FundingBot] Expected P&L before closing: ${}",
                        String.format("%.4f", pnlDataBefore.getNetPnl()));
            }

            //Waiting 20 sec for data to load up
            Thread.sleep(20000);

            double balanceAfter = asterClient.getBalance() + extendedClient.getBalance();
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

            //Collecting tare at closing
            ArbitrageRates currentRate = getCurrentSpread(signal.getTicker());
            currentSpread = currentRate.getArbitrageRate();

            eventPublisher.publishEvent(new PositionClosedEvent(
                    signal.getId(),
                    signal.getTicker(),
                    profit,
                    profitPercent,
                    true,
                    signal.getMode().equals(HoldingMode.FAST_MODE) ? "Fast mode" : "Smart mode",
                    currentSpread
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
                    signal.getCurrentFindingRate()
            ));

            return String.format("[FundingBot] %s Partial close - Manual check Needed!\n", signal.getTicker());
        }
    }

    private boolean validateFundingTime(FundingOpenSignal signal) {
        long minutesUntilFunding = asterClient.getMinutesUntilFunding(signal.getTicker() + "USDT");
        //If Funding is more than 60 min - not opening position
        if (minutesUntilFunding > 60) {
            log.info("[FundingBot] Aster funding too far: {} minutes, skipping signal", minutesUntilFunding);
            return false;
        }

        log.info("[FundingBot] Aster funding validated correctly, in less than {} min.", minutesUntilFunding);
        return true;
    }

    private double validateBalance() {
        double asterBalance = asterClient.getBalance();
        if (asterBalance == 0) throw new BalanceException("[Aster] Balance is 0");

        double extendedBalance = extendedClient.getBalance();
        if (extendedBalance == 0) throw new BalanceException("[Extended] Balance is 0");

        //Adjusting balance for fees and slippage while opening position
        double minBalance = Math.min(asterBalance, extendedBalance);
        double safeBalance = minBalance * 0.85; // 85% of balance

        log.info("[FundingBot] Balances: Aster=${}, Extended=${}, using ${}",
                asterBalance, extendedBalance, safeBalance);

        return safeBalance;
    }

    //Checking opened positions for both exchanges
    private boolean validateOpenedPositions(String extSymbol, String asterSymbol, FundingOpenSignal signal, String positionId) {
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
            PositionNotionalData extData = calculateExtendedNotional(
                    extPositions.getFirst(),
                    false
            );
            log.info("[FundingBot] Extended notional for position: {}", extData);

            AsterPosition curPosition = asterPositions.stream()
                    .filter(p -> p.getPositionSide().equalsIgnoreCase(signal.getAsterDirection().toString()))
                    .findFirst()
                    .orElse(null);

            if (Objects.isNull(curPosition)) {
                log.info("[FundingBot] Error parsing Aster position! Check logs!");
            }

            PositionNotionalData asterData = calculateAsterNotional(
                    curPosition,
                    0,
                    false
            );
            log.info("[FundingBot] Aster notional for position: {}", asterData);

            double totalOpenFees = extData.getFee() + asterData.getFee();

            log.info("[FundingBot] Opening fees: Extended=${}, Aster=${}, Total=${}",
                    String.format("%.4f", extData.getFee()),
                    String.format("%.4f", asterData.getFee()),
                    String.format("%.4f", totalOpenFees));

            // Saving data
            PositionPnLData pnlData = PositionPnLData.builder()
                    .positionId(positionId)
                    .ticker(signal.getTicker())
                    .openTime(LocalDateTime.now(ZoneOffset.UTC))
                    .totalOpenFees(totalOpenFees)
                    .totalCloseFees(0.0)
                    .extendedFundingNet(0.0)
                    .asterFundingNet(0.0)
                    .extUnrealizedPnl(0.0)
                    .asterUnrealizedPnl(0.0)
                    .build();

            pnlData.calculateTotals();
            positionDataMap.put(positionId, pnlData);

            log.info("[FundingBot] P&L initialized: positionId={}, openFees=${}, netPnl=${}",
                    positionId,
                    String.format("%.4f", totalOpenFees),
                    String.format("%.4f", pnlData.getNetPnl()));

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

    private void publishFailureEvent(String positionId, FundingOpenSignal signal, String errorMsg, double balance, boolean success) {
        eventPublisher.publishEvent(new PositionOpenedEvent(
                positionId,
                signal.getTicker(),
                errorMsg,
                balance,
                signal.getExtendedDirection().toString(),
                signal.getAsterDirection().toString(),
                signal.getMode().equals(HoldingMode.FAST_MODE) ? "Fast mode" : "Smart mode",
                success,
                signal.getRate()
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

    public int validateLeverage(String symbol) {
        int asterLeverage = asterClient.getMaxLeverage(symbol + "USDT");

        log.info("[FundingBot] Aster leverage for {}: {}",
                symbol, asterLeverage);

        return asterLeverage;
    }

    public void closeAllPositions() {
        if (openedPositions.isEmpty()) {
            log.debug("[FundingBot] Funding check: no positions to close");
            return;
        }

        log.info("[FundingBot] Funding received! Closing {} positions...", openedPositions.size());

        StringBuilder finalList = new StringBuilder();

        for (FundingCloseSignal signalToClose : openedPositions.values()) {
            finalList.append(closePositions(signalToClose));
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

        closePositions(signal);

        openedPositions.remove(positionId);
        log.info("[FundingBot] Position {} closed manually", positionId);

    }

    private void updateExtendedFundingForPosition(FundingCloseSignal signal) {
        PositionPnLData pnlData = positionDataMap.get(signal.getId());
        if (pnlData == null) {
            log.warn("[FundingBot] No P&L data for {}, skipping", signal.getId());
            return;
        }

        String extSymbol = signal.getTicker() + "-USD";

        log.info("[FundingBot] openTime from signal {}", signal.getOpenedAtMs());

        //Getting funding history
        ExtendedFundingHistoryResponse history = extendedClient.getFundingHistory(
                extSymbol,
                signal.getExtDirection().toString(),
                signal.getOpenedAtMs(),
                1000
        );

        if (history == null || history.getSummary() == null) {
            log.warn("[FundingBot] Failed to get Extended funding for {}", signal.getId());
            return;
        }

        double currentTotal = history.getSummary().getNetFunding();

        if (pnlData.getInitialExtFunding() == 0.0) {
            pnlData.setInitialExtFunding(currentTotal);
            pnlData.setExtendedFundingNet(0.0);

            log.info("[FundingBot] {} Extended funding baseline initialized: ${}",
                    signal.getId(), String.format("%.4f", currentTotal));
        } else {
            double netFunding = currentTotal - pnlData.getInitialExtFunding();
            pnlData.setExtendedFundingNet(netFunding);

            log.info("[FundingBot] {} Extended funding updated: baseline=${}, current=${}, net=${}",
                    signal.getId(),
                    String.format("%.4f", pnlData.getInitialExtFunding()),
                    String.format("%.4f", currentTotal),
                    String.format("%.4f", netFunding));
        }

        pnlData.calculateTotals();
    }

    private void addAsterFundingPrediction(FundingCloseSignal signal) {
        PositionPnLData pnlData = positionDataMap.get(signal.getId());
        if (pnlData == null) {
            log.warn("[FundingBot] No P&L data for {}, skipping", signal.getId());
            return;
        }

        String astSymbol = signal.getTicker() + "USDT";

        //Getting funding data updated
        PremiumIndexResponse premium = asterClient.getPremiumIndexInfo(astSymbol);
        if (premium == null) {
            log.warn("[FundingBot] Failed to get premium index for {}", signal.getId());
            return;
        }

        long minutesUntilFunding = premium.getMinutesUntilFunding();

        //Checking if funding in this hrs
        if (minutesUntilFunding > 10) {
            log.debug("[FundingBot] {} Aster funding too far: {} min, skipping",
                    signal.getId(), minutesUntilFunding);
            return;
        }

        log.info("[FundingBot] {} Aster funding in {} min, calculating...",
                signal.getId(), minutesUntilFunding);

        //Getting position for funding calculation
        List<AsterPosition> positions = asterClient.getPositions(astSymbol);
        AsterPosition asterPos = null;

        String side = signal.getAstDirection().toString().toUpperCase();
        for (AsterPosition pos : positions) {
            if (pos.getPositionSide().equalsIgnoreCase(side)) {
                double amt = Math.abs(Double.parseDouble(pos.getPositionAmt()));
                if (amt > 0.001) {
                    asterPos = pos;
                    break;
                }
            }
        }

        if (asterPos == null) {
            log.warn("[FundingBot] Aster position not found for {}", signal.getId());
            return;
        }

        //Funding calculation
        double size = Math.abs(Double.parseDouble(asterPos.getPositionAmt()));
        double markPrice = premium.getMarkPriceAsDouble();
        double notional = size * markPrice;
        double fundingRate = premium.getLastFundingRateAsDouble();

        boolean isLong = (signal.getAstDirection() == Direction.LONG);
        double fundingPnl = isLong ? -notional * fundingRate : notional * fundingRate;

        //Adding to total funding value
        pnlData.setAsterFundingNet(pnlData.getAsterFundingNet() + fundingPnl);
        pnlData.calculateTotals();

        log.info("[FundingBot] {} Aster funding: rate={}%, notional=${}, pnl=${}, total=${}",
                signal.getId(),
                String.format("%.6f", fundingRate * 100),
                String.format("%.2f", notional),
                String.format("%.4f", fundingPnl),
                String.format("%.4f", pnlData.getAsterFundingNet()));
    }

    public PositionPnLData calculateCurrentPnL(FundingCloseSignal signal) {
        String extSymbol = signal.getTicker() + "-USD";
        String astSymbol = signal.getTicker() + "USDT";

        PositionPnLData pnlData = positionDataMap.get(signal.getId());
        if (pnlData == null) {
            log.warn("[FundingBot] No P&L data for position {}", signal.getId());
            return null;
        }

        try {
            List<ExtendedPosition> extPositions = extendedClient.getPositions(
                    extSymbol,
                    signal.getExtDirection().toString()
            );

            log.info("[FundingBot] Extended position data: {}", extPositions);

            if (extPositions == null || extPositions.isEmpty()) {
                log.warn("[FundingBot] Extended position not found for {}", signal.getId());
                return null;
            }

            ExtendedPosition extPos = extPositions.getFirst();

            //Getting data from OrderBook for better calculation
            double extSize = Double.parseDouble(extPos.getSize());
            double extEntryPrice = Double.parseDouble(extPos.getOpenPrice());
            double extMarkPrice = Double.parseDouble(extPos.getMarkPrice());
            boolean isExtLong = signal.getExtDirection() == Direction.LONG;

            double extEffectivePrice;
            String extPriceSource;

            //Order Book request
            ExtendedOrderBook extBook = extendedClient.getOrderBook(extSymbol);

            if (extBook != null && extBook.getBid() != null && !extBook.getBid().isEmpty()
                    && extBook.getAsk() != null && !extBook.getAsk().isEmpty()) {

                //Getting Bid and Ask prices
                double extBidPrice = Double.parseDouble(extBook.getBid().getFirst().getPrice());
                double extAskPrice = Double.parseDouble(extBook.getAsk().getFirst().getPrice());

                // LONG closing SELL → using BID
                // SHORT closing BUY → using ASK
                extEffectivePrice = isExtLong ? extBidPrice : extAskPrice;
                extPriceSource = "OrderBook";

                double extSpread = ((extAskPrice - extBidPrice) / extMarkPrice) * 100;

                log.info("[Extended] Order Book: mark={}, bid={}, ask={}, spread={}%, effective={}",
                        String.format("%.6f", extMarkPrice),
                        String.format("%.6f", extBidPrice),
                        String.format("%.6f", extAskPrice),
                        String.format("%.3f", extSpread),
                        String.format("%.6f", extEffectivePrice));

            } else {
                //If Order Book unavailable → using slippage instead
                log.warn("[Extended] Order Book unavailable for {}, using fixed slippage", extSymbol);

                double extSlippage = 0.004; // 0.4% fallback slippage
                extEffectivePrice = isExtLong
                        ? extMarkPrice * (1 - extSlippage)  // BID estimate
                        : extMarkPrice * (1 + extSlippage);  // ASK estimate
                extPriceSource = "MarkPrice+Slippage";

                log.info("[Extended] Fallback: mark={}, slippage={}%, effective={}",
                        String.format("%.6f", extMarkPrice),
                        String.format("%.1f", extSlippage * 100),
                        String.format("%.6f", extEffectivePrice));
            }

            //Calculating PnL
            double extCalculatedPnl = isExtLong
                    ? extSize * (extEffectivePrice - extEntryPrice)
                    : extSize * (extEntryPrice - extEffectivePrice);

            double extApiPnl = Double.parseDouble(extPos.getUnrealisedPnl());
            double extSlippageImpact = extCalculatedPnl - extApiPnl;

            pnlData.setExtUnrealizedPnl(extCalculatedPnl);

            log.info("[Extended] P&L: size={}, entry={}, effective={} ({})",
                    String.format("%.4f", extSize),
                    String.format("%.6f", extEntryPrice),
                    String.format("%.6f", extEffectivePrice),
                    extPriceSource);
            log.info("[Extended] P&L: Calculated=${} (realistic), API=${}, Slippage Impact=${}",
                    String.format("%.4f", extCalculatedPnl),
                    String.format("%.4f", extApiPnl),
                    String.format("%.4f", extSlippageImpact));

            //Aster calculations
            List<AsterPosition> asterPositions = asterClient.getPositions(astSymbol);

            log.info("[FundingBot] Aster position data: {}", asterPositions);

            if (asterPositions == null || asterPositions.isEmpty()) {
                log.warn("[FundingBot] Aster position not found for {}", signal.getId());
                return null;
            }

            // Find Aster position
            AsterPosition asterPos = null;
            for (AsterPosition pos : asterPositions) {
                if (pos.getPositionSide().equalsIgnoreCase(signal.getAstDirection().toString())) {
                    asterPos = pos;
                    break;
                }
            }

            if (asterPos == null) {
                log.warn("[FundingBot] Aster position with side {} not found", signal.getAstDirection());
                return null;
            }

            // Get premium index for mark price
            PremiumIndexResponse premium = asterClient.getPremiumIndexInfo(astSymbol);
            if (premium == null) {
                log.warn("[FundingBot] Failed to get Aster premium index for {}", signal.getId());
                return null;
            }

            //Getting data from book ticker
            double astSize = Math.abs(Double.parseDouble(asterPos.getPositionAmt()));
            double astEntryPrice = Double.parseDouble(asterPos.getEntryPrice());
            double astMarkPrice = premium.getMarkPriceAsDouble();
            boolean isAstLong = signal.getAstDirection() == Direction.LONG;

            double astEffectivePrice;
            String astPriceSource;

            //Sending book ticker request
            AsterBookTicker asterTicker = asterClient.getBookTicker(astSymbol);

            if (asterTicker != null && asterTicker.getBidPrice() != null && asterTicker.getAskPrice() != null) {

                //Using real bid\ask
                double astBidPrice = Double.parseDouble(asterTicker.getBidPrice());
                double astAskPrice = Double.parseDouble(asterTicker.getAskPrice());

                // LONG closing SELL → using BID
                // SHORT closing BUY → using ASK
                astEffectivePrice = isAstLong ? astBidPrice : astAskPrice;
                astPriceSource = "BookTicker";

                double astSpread = ((astAskPrice - astBidPrice) / astMarkPrice) * 100;

                log.info("[Aster] Book Ticker: mark={}, bid={}, ask={}, spread={}%, effective={}",
                        String.format("%.6f", astMarkPrice),
                        String.format("%.6f", astBidPrice),
                        String.format("%.6f", astAskPrice),
                        String.format("%.3f", astSpread),
                        String.format("%.6f", astEffectivePrice));

            } else {
                //Using slippage if book ticker unavailable
                log.warn("[Aster] Book Ticker unavailable for {}, using fixed slippage", astSymbol);

                double astSlippage = 0.004; // 0.4% fallback slippage
                astEffectivePrice = isAstLong
                        ? astMarkPrice * (1 - astSlippage)  // BID estimate
                        : astMarkPrice * (1 + astSlippage);  // ASK estimate
                astPriceSource = "MarkPrice+Slippage";

                log.info("[Aster] Fallback: mark={}, slippage={}%, effective={}",
                        String.format("%.6f", astMarkPrice),
                        String.format("%.1f", astSlippage * 100),
                        String.format("%.6f", astEffectivePrice));
            }

            //PnL calculation
            double astCalculatedPnl = isAstLong
                    ? astSize * (astEffectivePrice - astEntryPrice)
                    : astSize * (astEntryPrice - astEffectivePrice);

            double astApiPnl = Double.parseDouble(asterPos.getUnrealizedProfit());
            double astSlippageImpact = astCalculatedPnl - astApiPnl;

            pnlData.setAsterUnrealizedPnl(astCalculatedPnl);

            log.info("[Aster] P&L: size={}, entry={}, effective={} ({})",
                    String.format("%.4f", astSize),
                    String.format("%.6f", astEntryPrice),
                    String.format("%.6f", astEffectivePrice),
                    astPriceSource);
            log.info("[Aster] P&L: Calculated=${} (realistic), API=${}, Slippage Impact=${}",
                    String.format("%.4f", astCalculatedPnl),
                    String.format("%.4f", astApiPnl),
                    String.format("%.4f", astSlippageImpact));

            //Closing fees
            PositionNotionalData extCloseData = calculateExtendedNotional(
                    extPos,
                    true
            );

            PositionNotionalData asterCloseData = calculateAsterNotional(
                    asterPos,
                    astMarkPrice,
                    true
            );

            double totalCloseFees = extCloseData.getFee() + asterCloseData.getFee();
            pnlData.setTotalCloseFees(totalCloseFees);

            //Totals
            pnlData.calculateTotals();

            log.info("[FundingBot] {} P&L Summary:", signal.getId());
            log.info("  Extended:      ${} ({})",
                    String.format("%.4f", pnlData.getExtUnrealizedPnl()),
                    extPriceSource);
            log.info("  Aster:         ${} ({})",
                    String.format("%.4f", pnlData.getAsterUnrealizedPnl()),
                    astPriceSource);
            log.info("  Gross P&L:     ${}", String.format("%.4f", pnlData.getGrossPnl()));
            log.info("  Funding:       ${}", String.format("%.4f", pnlData.getTotalFundingNet()));
            log.info("  Open Fees:     ${}", String.format("%.4f", pnlData.getTotalOpenFees()));
            log.info("  Close Fees:    ${}", String.format("%.4f", pnlData.getTotalCloseFees()));
            log.info("  Net P&L:       ${}", String.format("%.4f", pnlData.getNetPnl()));
            log.info("  Total Slippage Impact: ${}",
                    String.format("%.4f", extSlippageImpact + astSlippageImpact));

            return pnlData;

        } catch (Exception e) {
            log.error("[FundingBot] Error calculating current P&L for {}", signal.getId(), e);
            return null;
        }
    }

    //Notional and fee calculation for extended
    private PositionNotionalData calculateExtendedNotional(ExtendedPosition position, boolean isClosing) {
        double size = Double.parseDouble(position.getSize());
        double price = isClosing
                ? Double.parseDouble(position.getMarkPrice())
                : Double.parseDouble(position.getOpenPrice());

        double notional = size * price;
        double fee = notional * EXTENDED_TAKER_FEE;

        return new PositionNotionalData(notional, fee, price, size);
    }

    //Notional and fee calculation for aster
    private PositionNotionalData calculateAsterNotional(AsterPosition position, double markPrice, boolean isClosing) {
        double size = Math.abs(Double.parseDouble(position.getPositionAmt()));
        double price = isClosing
                ? markPrice
                : Double.parseDouble(position.getEntryPrice());

        double notional = size * price;
        double fee = notional * ASTER_TAKER_FEE;

        return new PositionNotionalData(notional, fee, price, size);
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
