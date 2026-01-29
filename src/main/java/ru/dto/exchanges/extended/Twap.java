package ru.dto.exchanges.extended;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class Twap {
    @JsonProperty("durationSeconds")
    private int durationSeconds;

    @JsonProperty("frequencySeconds")
    private int frequencySeconds;

    private boolean randomise;
}
