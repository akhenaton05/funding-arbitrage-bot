package ru.dto.exchanges.extended;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtendedMarketStats {
    private String markPrice;
    private String bidPrice;
    private String askPrice;
    private String lastPrice;
    private String fundingRate;
    private String dailyVolume;
    private String dailyPriceChangePercentage;
}
