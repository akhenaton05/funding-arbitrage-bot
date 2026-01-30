package ru.dto.exchanges;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class FundingCloseSignal {
    private String id;
    private String ticker;
    private double balance;
    private String extDirection; //Extended asking for the current direction of the position
    private String asterOrderId;
    private String extendedOrderId;
}
