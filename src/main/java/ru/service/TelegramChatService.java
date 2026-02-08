package ru.service;

import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.config.*;
import ru.dto.exchanges.FundingCloseSignal;
import ru.dto.exchanges.PositionBalance;
import ru.dto.exchanges.PositionClosedEvent;
import ru.dto.exchanges.PositionOpenedEvent;
import ru.dto.funding.ArbitrageRates;
import ru.dto.funding.HoldingMode;
import ru.dto.funding.PositionPnLData;
import ru.event.FundingAlertEvent;
import ru.event.PnLThresholdEvent;
import ru.utils.FundingArbitrageContext;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@AllArgsConstructor
public class TelegramChatService extends TelegramLongPollingBot {

    private final TelegramBotConfig telegramBotConfig;
    private final FundingArbitrageContext fundingContext;
    private final FundingArbitrageService fundingService;
    private final ExchangesService exchangesService;

    @Override
    public String getBotUsername() {
        String username = telegramBotConfig.getBotUsername();
        return username.startsWith("@") ? username.substring(1) : username;
    }

    @Override
    public String getBotToken() {
        return telegramBotConfig.getBotToken();
    }

    @SneakyThrows
    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            Message message = update.getMessage();
            Long chatId = message.getChatId();

            log.info("Received message from {}", chatId);

            if (message.hasText()) {
                String userMessage = message.getText();
                log.info("Text message: {}", userMessage);

                if (userMessage.startsWith("/")) {
                    handleCommand(chatId, userMessage);
                }
            }
        }
    }

    private void handleCommand(Long chatId, String command) {
        String[] parts = command.trim().split("\\s+", 2);
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "/track" -> fundingContext.addSubscriberId(chatId);
            case "/untrack" -> fundingContext.removeSubscriberId(chatId);
            case "/rates" -> sendRates(chatId);
            case "/trades" -> getTrades(chatId);
            case "/close" -> closePositionById(chatId, parts);
            case "/closeall" -> closeAllPositions();
            case "/pospnl" -> calculatePositionPnl(chatId, parts);
        }
    }

    private void sendRates(Long chatId) {
        log.info("Request to get funding rates received");
        sendTypingAction(chatId);

        try {
            List<ArbitrageRates> rates = fundingService.calculateArbitrageRates();

            StringBuilder result = new StringBuilder();
            result.append("```\n");
            result.append(String.format("%-7s | %-7s | %-7s | %-7s\n",
                    "Ticker", "Max Arb", "Extended", "Aster"));
            result.append("-".repeat(40)).append("\n");

            rates.stream()
                    .limit(10)
                    .forEach(opp -> result.append(String.format("%-7s | %6.1f%% | %7.1f%% | %8.1f%%\n",
                            opp.getSymbol(),
                            opp.getArbitrageRate(),
                            opp.getExtendedRate(),
                            opp.getAsterRate())));

            result.append("```");

            sendMessage(chatId, result.toString());
        } catch (Exception e) {
            sendMessage(chatId, " Error getting data");
            log.error("Error sending rates", e);
        }
    }

    private void calculatePositionPnl(Long chatId, String[] parts) {
        log.info("Request to close all positions");

        //Checking parameter
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            sendMessage(chatId,
                    "🤖 *FundingBot:* *Usage:* `/close <position_id>`\n\n" +
                            "*Example:* `/close P-0001`\n\n" +
                            "Use /trades to see active positions");
            return;
        }

        String positionId = parts[1].trim().toUpperCase();

        //Checking the format ID (P-0001, P-0002...)
        if (!positionId.matches("P-\\d{4}")) {
            sendMessage(chatId,
                    "🤖 *FundingBot:* Invalid position ID format\n\n" +
                            "*Format:* `P-XXXX` (e.g. `P-0001`)\n\n" +
                            "Use /trades to see active positions");
            return;
        }
        PositionPnLData posData = exchangesService.pnlPositionCalculator(positionId);

        sendMessage(chatId, validateCurrentPnl(posData));
    }

    private void closeAllPositions() {
        log.info("Request to close all positions");
        exchangesService.closeAllPositions();
    }

    private void closePositionById(Long chatId, String[] parts) {
        log.info("[Telegram] Close by ID request from chat {}", chatId);

        //Checking parameter
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            sendMessage(chatId,
                    "🤖 *FundingBot:* *Usage:* `/close <position_id>`\n\n" +
                            "*Example:* `/close P-0001`\n\n" +
                            "Use /trades to see active positions");
            return;
        }

        String positionId = parts[1].trim().toUpperCase();

        //Checking the format ID (P-0001, P-0002...)
        if (!positionId.matches("P-\\d{4}")) {
            sendMessage(chatId,
                    "🤖 *FundingBot:* Invalid position ID format\n\n" +
                            "*Format:* `P-XXXX` (e.g. `P-0001`)\n\n" +
                            "Use /trades to see active positions");
            return;
        }

        exchangesService.closePositionById(positionId);
    }

    public void sendMessage(Long chatId, String text) {
        sendTypingAction(chatId);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        message.setParseMode("Markdown");

        try {
            execute(message);
            log.info("Sent message to Telegram chat {}: {}", chatId, text);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to Telegram chat {}: {}", chatId, e.getMessage());
        }
    }

    private void sendTypingAction(Long chatId) {
        try {
            SendChatAction chatAction = new SendChatAction();
            chatAction.setChatId(chatId);
            chatAction.setAction(ActionType.TYPING);
            execute(chatAction);
        } catch (Exception e) {
            log.debug("Could not send typing action", e);
        }
    }

    @EventListener
    @Async
    public void handleFundingAlert(FundingAlertEvent event) {
        log.info("Received funding alert event for chat {}", event.getChatId());
        sendMessage(event.getChatId(), formatAlert(event.getMessage()));
    }

    @EventListener
    @Async
    public void handlePositionOpened(PositionOpenedEvent event) {
        log.info("[Telegram] Position opened event for {}", event.getTicker());

        String message = formatPositionOpenedMessage(event);

        for (Long chatId : fundingContext.getSubscriberIds()) {
            sendMessage(chatId, message);
        }
    }

    @EventListener
    @Async
    public void handlePositionClosed(PositionClosedEvent event) {
        log.info("[Telegram] Position closed event for {}", event.getTicker());

        String message = formatPositionClosedMessage(event);

        for (Long chatId : fundingContext.getSubscriberIds()) {
            sendMessage(chatId, message);
        }
    }

    @EventListener
    @Async
    public void handlePnLThreshold(PnLThresholdEvent event) {
        log.info("[Telegram] P&L threshold event for {}: {}%",
                event.getPositionId(),
                String.format("%.2f", event.getThresholdPercent()));

        String message = formatPnLThresholdMessage(event);

        for (Long chatId : fundingContext.getSubscriberIds()) {
            sendMessage(chatId, message);
        }
    }

    private void getTrades(Long chatId) {
        log.info("[Telegram] Got trades history request");

        String message = formatBalanceMap(
                exchangesService.getTrades(),
                exchangesService.getOpenedPositions()
        );

        sendMessage(chatId, message);
    }

    public String formatBalanceMap(Map<String, PositionBalance> balanceMap,
                                   Map<String, FundingCloseSignal> openedPositions) {
        if (balanceMap.isEmpty()) {
            return "🤖 *FundingBot:* Balance Tracker\n\n_No positions tracked yet_";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🤖 *FundingBot:* Position Balances\n");
        sb.append("━━━━━━━━━━━━━━━━━━\n\n");

        balanceMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String positionId = entry.getKey();
                    PositionBalance balance = entry.getValue();

                    String emoji;

                    if (balance.isClosed()) {
                        // if closed - showing profit/loss
                        emoji = balance.getProfit() > 0 ? "✅" : "❌";
                        String profitSign = balance.getProfit() > 0 ? "+" : "";

                        sb.append(String.format(
                                "%s *#%s*\n" +
                                        "   Before: $%.2f → After: $%.2f" +
                                        " (P&L: %s$%.2f)\n",
                                emoji,
                                positionId,
                                balance.getBalanceBefore(),
                                balance.getBalanceAfter(),
                                profitSign,
                                balance.getProfit()
                        ));
                    } else {
                        FundingCloseSignal position = openedPositions.get(positionId);
                        emoji = (position != null && position.getMode() == HoldingMode.FAST_MODE)
                                ? "⚡"
                                : "🧠";

                        sb.append(String.format(
                                "%s *#%s*\n" +
                                        "   Before: $%.2f → After: _pending_\n",
                                emoji,
                                positionId,
                                balance.getBalanceBefore()
                        ));
                    }
                });

        return sb.toString();
    }

    private String formatPositionOpenedMessage(PositionOpenedEvent event) {
        if (!event.isSuccess()) {
            if (event.getResult() != null &&
                    event.getResult().contains("No balance available to open position")) {
                return "🤖 *FundingBot:* No margin available to open position";
            }
            else if (event.getResult() != null &&
                    event.getResult().contains("More than an hour until funding, position not opened")) {
                return "🤖 *FundingBot:* Funding payment in more than an hour, position wasn't opened";
            }

            return String.format(
                    "🤖 *FundingBot:* Position Opening Failed ❌\n\n" +
                            "*Position ID:* `%s`\n" +
                            "*Mode:* %s\n" +
                            "*Ticker:* %s\n" +
                            "*Error:* %s\n",
                    event.getPositionId(),
                    event.getMode(),
                    event.getTicker(),
                    event.getResult()
            );
        }

        return String.format(
                "🤖 *FundingBot:* Position Opened ✅\n\n" +
                        "*Position ID:* `%s`\n" +
                        "*Mode:* %s\n" +
                        "*Ticker:* %s\n" +
                        "*Margin used:* %.2f USD\n" +
                        "*Funding rate:* %s\n",
                event.getPositionId(),
                event.getMode(),
                event.getTicker(),
                event.getBalanceUsed(),
                event.getRate()
        );
    }

    private String formatPositionClosedMessage(PositionClosedEvent event) {
        String sign = event.getPnl() >= 0 ? "+" : "";
        if (!event.isSuccess()) {
            return String.format(
                    "🤖 *FundingBot:* Position Close Error ❌\n\n" +
                            "*Position ID:* `%s`\n" +
                            "*Mode:* %s\n" +
                            "*Ticker:* %s\n" +
                            "*Status:* Manual check required!",
                    event.getPositionId(),
                    event.getMode(),
                    event.getTicker()
            );
        }

        return String.format(
                "🤖 *FundingBot:* Position Closed ✅\n\n" +
                        "*Position ID:* `%s`\n" +
                        "*Mode:* %s\n" +
                        "*Ticker:* %s\n" +
                        "*P&L:* " + sign + "%.2f USD (%.2f%%)\n",
                event.getPositionId(),
                event.getMode(),
                event.getTicker(),
                event.getPnl(),
                event.getPercent()
        );
    }

    private String formatAlert(ArbitrageRates rate) {
        return String.format("🚨 *High Arbitrage Alert* 🚨\n\n" +
                        "*Symbol:* %s\n" +
                        "*Max Arb:* %.2f%%\n" +
                        "*Extended:* %.2f%%\n" +
                        "*Aster:* %.2f%%\n" +
                        "*Action:* %s",
                rate.getSymbol(),
                rate.getArbitrageRate(),
                rate.getExtendedRate(),
                rate.getAsterRate(),
                rate.getAction());
    }

    public String validateCurrentPnl(PositionPnLData pnlData) {
        if (pnlData == null) {
            return "Failed to calculate P&L";
        }

        Duration duration = Duration.between(
                pnlData.getOpenTime(),
                LocalDateTime.now(ZoneOffset.UTC)
        );

        long heldHours = duration.toHours();
        long heldMinutes = duration.toMinutesPart();

        StringBuilder sb = new StringBuilder();
        sb.append("🤖 *FundingBot:* Position P&L \uD83D\uDCCA \n\n");
        sb.append("*Position ID:* ").append("`").append(pnlData.getPositionId()).append("`");
        sb.append("*Ticker:* ").append(pnlData.getTicker()).append("\n");
        sb.append("*Hold time:* ");
        if (heldHours > 0) {
            sb.append(heldHours).append("h ");
        }
        sb.append(heldMinutes).append("m\n");

        sb.append("*Gross P&L:* ").append(formatMoney(pnlData.getGrossPnl())).append("\n");
        sb.append("*Funding:* ").append(formatMoney(pnlData.getTotalFundingNet())).append("\n");
        sb.append("*Fees:* -").append(String.format("%.4f USD", pnlData.getTotalOpenFees() + pnlData.getTotalCloseFees())).append("\n\n");

        double netPnl = pnlData.getNetPnl();
        String sign = netPnl >= 0 ? "+" : "";
        sb.append("*Net P&L:* ").append(sign).append(String.format("%.4f USD", netPnl));

        return sb.toString();
    }

    private String formatMoney(double amount) {
        return String.format("%+.4f USD", amount);
    }

    private String formatPnLThresholdMessage(PnLThresholdEvent event) {
        PositionPnLData pnl = event.getPnlData();

        return String.format(
                "🤖 *FundingBot:* P&L Alert \uD83D\uDCB0\n\n" +
                        "*Position:* `%s`\n" +
                        "*Ticker:* %s\n" +
                        "*ROI:* %.2f%%\n" +
                        "*Net:* $%.2f",
                event.getPositionId(),
                event.getTicker(),
                event.getThresholdPercent(),
                pnl.getNetPnl()
        );
    }
}
