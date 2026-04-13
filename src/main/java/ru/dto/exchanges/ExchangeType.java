package ru.dto.exchanges;

public enum ExchangeType {
    ASTER("Aster"),
    EXTENDED("Extended"),
    HYPERLIQUID("Hyperliquid"),
    LIGHTER("Lighter");
    
    private final String displayName;
    
    ExchangeType(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }

    public static String abbreviate(String exchangeName) {
        if (exchangeName == null) return "???";
        return switch (exchangeName.toLowerCase()) {
            case "aster", "asterdex" -> "Ast";
            case "lighter" -> "Lit";
            case "extended" -> "Ext";
            case "hyperliquid" -> "Hype";
            default -> exchangeName.length() > 4
                    ? exchangeName.substring(0, 4)
                    : exchangeName;
        };
    }
}
