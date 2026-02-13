package ru.dto.exchanges;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceLevel {
    private double price;
    private double size;

    public double getNotional() {
        return price * size;
    }
}
