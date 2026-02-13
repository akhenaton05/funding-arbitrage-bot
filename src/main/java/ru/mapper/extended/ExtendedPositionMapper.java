package ru.mapper.extended;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.dto.exchanges.Direction;
import ru.dto.exchanges.ExchangeType;
import ru.dto.exchanges.Position;
import ru.dto.exchanges.extended.ExtendedPosition;

@Slf4j
@Component
public class ExtendedPositionMapper {

    public static Position toPosition(ExtendedPosition extPos) {
        if (extPos == null) {
            return null;
        }
        
        try {
            double size = Double.parseDouble(extPos.getSize());
            double entryPrice = Double.parseDouble(extPos.getOpenPrice());
            double markPrice = Double.parseDouble(extPos.getMarkPrice());
            double unrealizedPnl = Double.parseDouble(extPos.getUnrealisedPnl());

            Direction side = Direction.valueOf(extPos.getSide());
            
            return Position.builder()
                    .exchange(ExchangeType.EXTENDED)
                    .symbol(extPos.getMarket())
                    .side(side)
                    .size(size)
                    .entryPrice(entryPrice)
                    .markPrice(markPrice)
                    .unrealizedPnl(unrealizedPnl)
                    .build();
                    
        } catch (Exception e) {
            log.error("[ExtendedMapper] Failed to map position: {}", e.getMessage(), e);
            return null;
        }
    }
}
