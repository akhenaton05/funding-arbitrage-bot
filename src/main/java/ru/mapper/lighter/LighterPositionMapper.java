package ru.mapper.lighter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.dto.exchanges.Direction;
import ru.dto.exchanges.ExchangeType;
import ru.dto.exchanges.Position;
import ru.dto.exchanges.aster.AsterPosition;
import ru.dto.exchanges.lighter.LighterPosition;

import java.util.Objects;

@Slf4j
@Component
public class LighterPositionMapper {

    public static Position toPosition(LighterPosition lighterPos) {
        if (Objects.isNull(lighterPos)) {
            return null;
        }

        try {
            double positionAmt = Double.parseDouble(lighterPos.getSize());
            double size = Math.abs(positionAmt);

            if (size < 0.0001) {
                return null;
            }

            double entryPrice = Double.parseDouble(lighterPos.getOpenPrice());
            double markPrice = Double.parseDouble(lighterPos.getMarkPrice());
            double unrealizedPnl = Double.parseDouble(lighterPos.getUnrealisedPnl());

            Direction side = Direction.valueOf(lighterPos.getSide());

            return Position.builder()
                    .exchange(ExchangeType.LIGHTER)
                    .symbol(lighterPos.getMarket())
                    .side(side)
                    .size(size)
                    .entryPrice(entryPrice)
                    .markPrice(markPrice)
                    .unrealizedPnl(unrealizedPnl)
                    .build();

        } catch (Exception e) {
            log.error("[Lighter] Failed to map position: {}", e.getMessage(), e);
            return null;
        }
    }
}
