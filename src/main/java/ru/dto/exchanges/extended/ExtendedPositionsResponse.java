package ru.dto.exchanges.extended;

import lombok.Data;
import java.util.List;

@Data
public class ExtendedPositionsResponse {
    private String status;
    private List<ExtendedPosition> data;
}

