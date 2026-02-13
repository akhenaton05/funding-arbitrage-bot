package ru.dto.exchanges;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketInfo {
    private String symbol;
    private int maxLeverage;
    private double minOrderSize;
}