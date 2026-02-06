package ru.dto.exchanges.aster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AsterBookTicker {
    private String symbol;
    private String bidPrice;   // Лучшая цена покупки
    private String bidQty;     // Количество на лучшем BID
    private String askPrice;   // Лучшая цена продажи
    private String askQty;     // Количество на лучшем ASK
    private Long time;         // Timestamp
}
