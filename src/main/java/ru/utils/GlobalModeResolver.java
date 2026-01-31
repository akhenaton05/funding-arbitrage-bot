package ru.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.dto.funding.HoldingMode;

@Slf4j
@Component
public class GlobalModeResolver {
    private volatile HoldingMode currentMode = HoldingMode.ONE_FUNDING;
    
    public HoldingMode getCurrentMode() {
        return currentMode;
    }
    
    public void setMode(HoldingMode mode) {
        log.info("[ModeResolver] Switching mode: {} → {}", currentMode, mode);
        this.currentMode = mode;
    }
}