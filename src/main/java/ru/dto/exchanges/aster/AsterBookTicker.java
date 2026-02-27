package ru.dto.exchanges.aster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AsterBookTicker {
    private String symbol;
    private String bidPrice;
    private String bidQty;
    private String askPrice;
    private String askQty;
    private Long time;
}
