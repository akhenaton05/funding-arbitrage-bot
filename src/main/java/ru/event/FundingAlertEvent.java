package ru.event;

import lombok.Getter;

@Getter
public class FundingAlertEvent {
    private final String message;
    private final Long chatId;
    
    public FundingAlertEvent(Long chatId, String message) {
        this.chatId = chatId;
        this.message = message;
    }
}
