package ru.dto.exchanges.extended;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtendedOrderBook {
    private String market;
    private List<OrderBookLevel> bid;
    private List<OrderBookLevel> ask;
}
