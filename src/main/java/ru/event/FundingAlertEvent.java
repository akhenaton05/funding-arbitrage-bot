package ru.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import ru.dto.funding.ArbitrageRates;
import ru.dto.funding.HoldingMode;

@Getter
@AllArgsConstructor
public class FundingAlertEvent {
    private final Long chatId;
    private final ArbitrageRates message;
    private HoldingMode mode;
    private int leverage;
}
