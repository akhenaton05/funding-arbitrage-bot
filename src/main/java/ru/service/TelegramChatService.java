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
import ru.dto.exchanges.PositionClosedEvent;
import ru.dto.exchanges.PositionOpenedEvent;
import ru.dto.funding.ArbitrageRates;
import ru.event.FundingAlertEvent;
import ru.utils.FundingArbitrageContext;

import java.util.List;
import java.util.Map;

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

    private void getTrades(Long chatId) {
        log.info("[Telegram] Got trades history request");

        String message = formatBalanceMap(exchangesService.getTrades());

        sendMessage(chatId, message);
    }

    public String formatBalanceMap(Map<String, Double> balanceMap) {
        if (balanceMap.isEmpty()) {
            return "📊 *Balance Tracker*\n\n_No positions tracked yet_";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Position Balances*\n");
        sb.append("━━━━━━━━━━━━━━━━━━\n\n");

        double total = 0.0;

        for (Map.Entry<String, Double> entry : balanceMap.entrySet()) {
            String positionId = entry.getKey();
            double balance = entry.getValue();
            total += balance;

            sb.append(String.format("🔹 *#%s*\n", positionId));
            sb.append(String.format("   💰 $%.2f\n\n", balance));
        }

        sb.append("━━━━━━━━━━━━━━━━━━\n");
        sb.append(String.format("💵 *Total Allocated:* $%.2f\n", total));
        sb.append(String.format("📦 *Active Positions:* %d", balanceMap.size()));

        return sb.toString();
    }

    private String formatPositionOpenedMessage(PositionOpenedEvent event) {
        if (event.getResult() != null &&
                (event.getResult().contains("Error") || event.getResult().contains("Failed"))) {

            return String.format(
                    "\uD83E\uDD16 *FundingBot:* Position Opening Failed ❌\n\n" +
                            "*Position ID:* %s\n" +
                            "*Ticker:* %s\n" +
                            "*Error:* %s\n",
                    event.getPositionId(),
                    event.getTicker(),
                    event.getResult()
            );
        }

        return String.format(
                "\uD83E\uDD16 *FundingBot:* Position Opened ✅\n\n" +
                        "*Position ID:* %s\n" +
                        "*Ticker:* %s\n" +
                        "*Balance used:* %.2f USD\n",
                event.getPositionId(),
                event.getTicker(),
                event.getBalanceUsed()
        );
    }

    private String formatPositionClosedMessage(PositionClosedEvent event) {
        if (!event.isSuccess()) {
            return String.format(
                    "\uD83E\uDD16 *FundingBot:* Position Close Error ❌\n\n" +
                            "*Position ID:* %s\n" +
                            "*Ticker:* %s\n" +
                            "*Status:* Manual check required!",
                    event.getPositionId(),
                    event.getTicker()
            );
        }

        return String.format(
                "\uD83E\uDD16 *FundingBot:* Position Closed ✅\n\n" +
                        "*Position ID:* %s\n" +
                        "*Ticker:* %s\n" +
                        "*P&L:* %.2f USD\n",
                event.getPositionId(),
                event.getTicker(),
                event.getPnl()
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
}
