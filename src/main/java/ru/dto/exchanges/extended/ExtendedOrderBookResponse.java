package ru.dto.exchanges.extended;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtendedOrderBookResponse {
    private String status;
    private ExtendedOrderBook data;
}

