package ru.dto.exchanges;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Funding calculation result from exchange
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundingResult {
    private ExchangeType exchange;
    private String symbol;
    private Direction side;
    
    // Calculation details
    private double fundingRate;
    private double notional;
    private double fundingPnl;
    
    // Metadata
    private long minutesUntilNext;
    private long timestamp;
    private boolean applied;
}
