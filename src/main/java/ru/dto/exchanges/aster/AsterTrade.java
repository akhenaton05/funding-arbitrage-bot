package ru.dto.exchanges.aster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AsterTrade {
    private Long id;
    private String price;
    private String qty;
    private String quoteQty;
    private Long time;
    private Boolean isBuyerMaker;
}
