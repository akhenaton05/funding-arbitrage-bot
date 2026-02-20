package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class DepthLevel {
    private Double price;
    private Double size;

    @JsonProperty("cumulative_size")
    private Double cumulativeSize;

    @JsonProperty("cumulative_value")
    private Double cumulativeValue;
}
