package ru.event;

import lombok.Getter;
import ru.dto.funding.ArbitrageRates;

@Getter
public class FundingAlertEvent {
    private final ArbitrageRates message;
    private final Long chatId;
    
    public FundingAlertEvent(Long chatId, ArbitrageRates message) {
        this.chatId = chatId;
        this.message = message;
    }
}
