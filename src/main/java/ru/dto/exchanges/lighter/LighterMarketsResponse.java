package ru.dto.exchanges.lighter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LighterMarketsResponse {
    private String status;
    private Integer count;
    private List<LighterMarket> data;
}
