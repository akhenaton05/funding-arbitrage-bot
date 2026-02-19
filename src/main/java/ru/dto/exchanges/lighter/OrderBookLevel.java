package ru.dto.exchanges.lighter;

import lombok.Data;

@Data
public class OrderBookLevel {
    private String price;
    private String size;
}
