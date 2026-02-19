package ru.dto.exchanges.lighter;

import lombok.Data;

import java.util.List;

@Data
public class LighterPositionsResponse {
    private String status;
    private List<LighterPosition> data;
}
