package ru.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "funding")
public class FundingConfig {
    
    private ThresholdsConfig thresholds;
    private FastModeConfig fast;
    private SmartModeConfig smart;
    private PnLConfig pnl;
    private SlTpConfig sltp;
    private OiConfig oi;
    
    @Data
    public static class ThresholdsConfig {
        private double smartModeRate;
        private double fastModeRate;
    }
    
    @Data
    public static class FastModeConfig {
        private int leverage;
    }
    
    @Data
    public static class SmartModeConfig {
        private int leverage;
        private long checkDelayMs;
        private int maxHoldMinutes;
        private int badStreakThreshold;
        private double closeThreshold;
    }

    @Data
    public static class PnLConfig {
        private double thresholdPercent;
        private long checkIntervalMs;
        private boolean enableNotifications;
    }

    @Data
    public static class SlTpConfig {
        private boolean enabled;
        private double stopLossPercent;
        private double takeProfitPercent;
    }

    @Data
    public static class OiConfig {
        private boolean enabled ;
        private int maxRank;
    }
}
