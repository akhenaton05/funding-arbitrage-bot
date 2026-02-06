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
    
    @Data
    public static class ThresholdsConfig {
        private double smartModeRate;  // 50 bps
        private double fastModeRate;    // 150 bps
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
}
