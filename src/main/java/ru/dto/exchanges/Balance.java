package ru.dto.exchanges;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Balance {
    private double balance;
    private double margin;
}
