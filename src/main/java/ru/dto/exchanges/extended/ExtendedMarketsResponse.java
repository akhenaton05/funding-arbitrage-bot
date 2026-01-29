package ru.dto.exchanges.extended;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtendedMarketsResponse {
    private String status;
    private List<MarketData> data;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MarketData {
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("assetName")
        private String assetName;
        
        @JsonProperty("assetPrecision")
        private Integer assetPrecision;
        
        @JsonProperty("collateralAssetName")
        private String collateralAssetName;
        
        @JsonProperty("collateralAssetPrecision")
        private Integer collateralAssetPrecision;
        
        @JsonProperty("active")
        private Boolean active;
        
        @JsonProperty("status")
        private String status;
        
        @JsonProperty("marketStats")
        private MarketStats marketStats;
        
        @JsonProperty("tradingConfig")
        private TradingConfig tradingConfig;
        
        @JsonProperty("l2Config")
        private L2Config l2Config;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MarketStats {
        @JsonProperty("dailyVolume")
        private String dailyVolume;
        
        @JsonProperty("dailyVolumeBase")
        private String dailyVolumeBase;
        
        @JsonProperty("dailyPriceChangePercentage")
        private String dailyPriceChangePercentage;
        
        @JsonProperty("dailyLow")
        private String dailyLow;
        
        @JsonProperty("dailyHigh")
        private String dailyHigh;
        
        @JsonProperty("lastPrice")
        private String lastPrice;
        
        @JsonProperty("askPrice")
        private String askPrice;
        
        @JsonProperty("bidPrice")
        private String bidPrice;
        
        @JsonProperty("markPrice")
        private String markPrice;
        
        @JsonProperty("indexPrice")
        private String indexPrice;
        
        @JsonProperty("fundingRate")
        private String fundingRate;
        
        @JsonProperty("nextFundingRate")
        private Long nextFundingRate;
        
        @JsonProperty("openInterest")
        private String openInterest;
        
        @JsonProperty("openInterestBase")
        private String openInterestBase;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TradingConfig {
        @JsonProperty("minOrderSize")
        private String minOrderSize;
        
        @JsonProperty("minOrderSizeChange")
        private String minOrderSizeChange;
        
        @JsonProperty("minPriceChange")
        private String minPriceChange;
        
        @JsonProperty("maxMarketOrderValue")
        private String maxMarketOrderValue;
        
        @JsonProperty("maxLimitOrderValue")
        private String maxLimitOrderValue;
        
        @JsonProperty("maxPositionValue")
        private String maxPositionValue;
        
        @JsonProperty("maxLeverage")
        private String maxLeverage;
        
        @JsonProperty("maxNumOrders")
        private String maxNumOrders;
        
        @JsonProperty("limitPriceCap")
        private String limitPriceCap;
        
        @JsonProperty("limitPriceFloor")
        private String limitPriceFloor;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class L2Config {
        @JsonProperty("type")
        private String type;
        
        @JsonProperty("collateralId")
        private String collateralId;
        
        @JsonProperty("collateralResolution")
        private Long collateralResolution;
        
        @JsonProperty("syntheticId")
        private String syntheticId;
        
        @JsonProperty("syntheticResolution")
        private Long syntheticResolution;
    }
}
