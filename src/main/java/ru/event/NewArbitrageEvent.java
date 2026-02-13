package ru.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import ru.dto.funding.FundingOpenSignal;

@Getter
@AllArgsConstructor
public class NewArbitrageEvent {
    private final FundingOpenSignal signal;
}