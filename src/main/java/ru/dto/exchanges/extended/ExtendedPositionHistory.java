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
    private Double size;
    private Double openPrice;
    private Double exitPrice;
    private String exitType;
    private Double realisedPnl;
    private RealisedPnlBreakdown realisedPnlBreakdown;
    private String maxPositionSize;
    private Long createdTime;
    private Long closedTime;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RealisedPnlBreakdown {
        private Double tradePnl;
        private Double openFees;
        private Double closeFees;
        private Double fundingFees;
    }
}
