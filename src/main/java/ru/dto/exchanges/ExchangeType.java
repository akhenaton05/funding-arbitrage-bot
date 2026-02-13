package ru.dto.exchanges;

public enum ExchangeType {
    ASTER("Aster"),
    EXTENDED("Extended"),
    LIGHTER("Lighter");
    
    private final String displayName;
    
    ExchangeType(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}
