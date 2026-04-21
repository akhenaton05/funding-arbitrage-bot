package ru.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "spread")
public class SpreadConfig {
    private OiConfig oi;
    private double entryThresholdBps;
    private double exitThresholdBps;

    @Data
    public static class OiConfig {
        private boolean enabled ;
        private int maxRank;
    }
}
