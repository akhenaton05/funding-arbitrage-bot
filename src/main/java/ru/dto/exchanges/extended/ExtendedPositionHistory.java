package ru.dto.exchanges.extended;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtendedPositionHistory {
    private Long id;
    private Long accountId;
    private String market;
    private String side;
    private String leverage;
    private String size;
    private String openPrice;
    private String exitPrice;
    private String exitType;
    private String realisedPnl;
    private RealisedPnlBreakdown realisedPnlBreakdown;
    private String maxPositionSize;
    private Long createdTime;
    private Long closedTime;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RealisedPnlBreakdown {
        private String tradePnl;
        private String openFees;
        private String closeFees;
        private String fundingFees;
    }
}
