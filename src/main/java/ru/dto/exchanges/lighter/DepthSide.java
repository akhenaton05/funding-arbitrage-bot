package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class DepthSide {
    private List<DepthLevel> levels;

    @JsonProperty("total_size")
    private Double totalSize;

    @JsonProperty("total_value")
    private Double totalValue;

    @JsonProperty("average_price")
    private Double averagePrice;
}
