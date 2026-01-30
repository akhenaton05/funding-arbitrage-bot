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
import ru.dto.exchanges.FundingCloseSignal;
import ru.dto.exchanges.FundingOpenSignal;
import ru.dto.exchanges.PositionClosedEvent;
import ru.dto.exchanges.PositionOpenedEvent;
import ru.dto.exchanges.aster.AsterPosition;
import ru.dto.exchanges.extended.ExtendedPosition;
import ru.event.NewArbitrageEvent;
import ru.exceptions.BalanceException;
import ru.exceptions.ClosingPositionException;
import ru.exceptions.OpeningPositionException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@EnableScheduling
@AllArgsConstructor
public class ExchangesService {

    private final AsterClient asterClient;
    private final ExtendedClient extendedClient;
    private final ApplicationEventPublisher eventPublisher;

    private static final int LEVERAGE = 4;
    private final Map<UUID, FundingCloseSignal> openedPositions = new ConcurrentHashMap<>();

    @EventListener
    @Async
    public void handleArbitrageSignal(NewArbitrageEvent event) {
        log.info("[FundingBot] Received arbitrage signal for {}", event.getSignal().getTicker());

        String result = openPosition(event.getSignal());

        log.info("[FundingBot] Result: {}", result);
    }

    // Every 10 sec of new hour -> checking Map - if not empty - closing position
    @Scheduled(cron = "10 01 * * * *", zone = "UTC")
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

    public String openPosition(FundingOpenSignal signal) {
        double marginBalance = validateBalance();
        double balanceBefore = asterClient.getBalance() + extendedClient.getBalance();
        UUID positionId = UUID.randomUUID();

        String extendedOrderId;
        String asterOrderId;
        String extSymbol;
        String astSymbol;

        try {
            extSymbol = signal.getTicker() + "-USD";
            extendedOrderId = extendedClient.openPositionWithFixedMargin(
                    extSymbol,
                    marginBalance,
                    LEVERAGE,
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
                    LEVERAGE,
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
            double balanceAfter = getAsterBalance() + getExtendedBalance();
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
                double balanceAfter = getAsterBalance() + getExtendedBalance();
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

        //Creating opened position and putting into the map
        FundingCloseSignal positionToClose = FundingCloseSignal.builder()
                .id(positionId)
                .ticker(signal.getTicker())
                .balance(marginBalance)
                .extDirection(signal.getExtendedDirection().toString())
                .asterOrderId(asterOrderId)
                .extendedOrderId(extendedOrderId)
                .build();

        openedPositions.put(positionToClose.getId(), positionToClose);

        String successMsg = "[FundingBot] Successfully opened positions | Extended: " + extendedOrderId +
                " | Aster: " + asterOrderId + " | Waiting for funding rates";

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

        double balanceBefore = asterClient.getBalance() + extendedClient.getBalance();
        log.info("[FundingBot] Balance before closing positions: {}", balanceBefore);

        CompletableFuture<String> extFuture = CompletableFuture.supplyAsync(() ->
                extendedClient.closePosition(extSymbol, signal.getExtDirection())
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

            //Waiting 15 sec for data to load up
            Thread.sleep(15000);

            double balanceAfter = asterClient.getBalance() + extendedClient.getBalance();
            log.info("[FundingBot] Balance after closing positions: {}", balanceAfter);
            double profit = balanceAfter - balanceBefore;

            eventPublisher.publishEvent(new PositionClosedEvent(
                    signal.getId(),
                    signal.getTicker(),
                    profit,
                    true
            ));

            return String.format("[FundingBot] Positions closed. P&L: %.4f USD", profit);

        } catch (Exception e) {
            log.error("[FundingBot] Error closing positions for {}", signal.getTicker(), e);

            eventPublisher.publishEvent(new PositionClosedEvent(
                    signal.getId(),
                    signal.getTicker(),
                    0,
                    false
            ));

            return String.format("[FundingBot] %s Partial close - Manual check Needed!\n", signal.getTicker());
        }
    }

    private double validateBalance() {
        double asterBalance = getAsterBalance();
        if (asterBalance == 0) throw new BalanceException("[Aster] Balance is 0");

        double extendedBalance = getExtendedBalance();
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

    private double getAsterBalance() {
        return asterClient.getBalance();
    }

    private double getExtendedBalance() {
        return extendedClient.getBalance();
    }

    private void publishFailureEvent(UUID positionId, FundingOpenSignal signal, String errorMsg, double balance) {
        eventPublisher.publishEvent(new PositionOpenedEvent(
                positionId,
                signal.getTicker(),
                errorMsg,
                balance,
                signal.getExtendedDirection().toString(),
                signal.getAsterDirection().toString()
        ));
    }
}
