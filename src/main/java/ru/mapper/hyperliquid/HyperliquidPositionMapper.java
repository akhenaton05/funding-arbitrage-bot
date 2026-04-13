package ru.mapper.hyperliquid;

import ru.dto.exchanges.Direction;
import ru.dto.exchanges.ExchangeType;
import ru.dto.exchanges.Position;
import ru.dto.exchanges.hyperliquid.HyperliquidPosition;

public class HyperliquidPositionMapper {

    public static Position toPosition(HyperliquidPosition p) {
        if (p == null) return null;
        return Position.builder()
                .exchange(ExchangeType.HYPERLIQUID)
                .symbol(p.getMarket())
                .side(p.getSide() != null ? Direction.valueOf(p.getSide()) : Direction.LONG)
                .size(parseDouble(p.getSize()))
                .entryPrice(parseDouble(p.getOpenPrice()))
                .markPrice(parseDouble(p.getMarkPrice()))
                .unrealizedPnl(parseDouble(p.getUnrealisedPnl()))
                .liquidationPrice(parseDouble(p.getLiquidationPrice()))
                .build();
    }

    private static int parseLeverage(String leverageStr) {
        if (leverageStr == null) return 1;
        try { return Integer.parseInt(leverageStr.replace("x", "")); }
        catch (NumberFormatException e) { return 1; }
    }

    private static double parseDouble(String val) {
        try { return val != null ? Double.parseDouble(val) : 0.0; }
        catch (NumberFormatException e) { return 0.0; }
    }
}