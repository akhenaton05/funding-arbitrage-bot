package ru.dto.exchanges.lighter;

import lombok.Data;

import java.util.List;

@Data
public class LighterOrderBook {
    private List<OrderBookLevel> bid;
    private List<OrderBookLevel> ask;
}
