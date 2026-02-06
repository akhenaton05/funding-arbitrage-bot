package ru.dto.funding;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PositionNotionalData {
    private double notional;
    private double fee;
    private double price;
    private double size;
}