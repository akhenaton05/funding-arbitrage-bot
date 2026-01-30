package ru.dto.funding;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class ArbitrageRates {
    private String symbol;
    private double arbitrageRate;
    private double extendedRate;
    private double asterRate;
    private String action;
}