package ru.mapper.aster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.dto.exchanges.Direction;
import ru.dto.exchanges.ExchangeType;
import ru.dto.exchanges.Position;
import ru.dto.exchanges.aster.AsterPosition;

@Slf4j
@Component
public class AsterPositionMapper {

    public static Position toPosition(AsterPosition asterPos) {
        if (asterPos == null) {
            return null;
        }

        try {
            double positionAmt = Double.parseDouble(asterPos.getPositionAmt());
            double size = Math.abs(positionAmt);

            if (size < 0.0001) {
                return null;
            }

            double entryPrice = Double.parseDouble(asterPos.getEntryPrice());
            double markPrice = Double.parseDouble(asterPos.getMarkPrice());
            double unrealizedPnl = Double.parseDouble(asterPos.getUnrealizedProfit());

            Direction side = Direction.valueOf(asterPos.getPositionSide());

            return Position.builder()
                    .exchange(ExchangeType.ASTER)
                    .symbol(asterPos.getSymbol())
                    .side(side)
                    .size(size)
                    .entryPrice(entryPrice)
                    .markPrice(markPrice)
                    .unrealizedPnl(unrealizedPnl)
                    .build();

        } catch (Exception e) {
            log.error("[Aster] Failed to map position: {}", e.getMessage(), e);
            return null;
        }
    }
}
