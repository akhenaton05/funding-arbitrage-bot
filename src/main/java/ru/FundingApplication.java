package ru;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.service.TelegramChatService;

@Slf4j
@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties
public class FundingApplication {

    public static void main(String[] args) {
        ApplicationContext context = SpringApplication.run(FundingApplication.class, args);
        TelegramChatService telegramService = context.getBean(TelegramChatService.class);

        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(telegramService);
            log.info("Telegram bot registered successfully!");
        } catch (TelegramApiException e) {
            log.error("Failed to register Telegram bot: " + e.getMessage());
            if (!e.getMessage().contains("404")) {
                e.printStackTrace();
            }
        }
    }
}