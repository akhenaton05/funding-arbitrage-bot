package ru.dto.exchanges.aster;

import lombok.Data;

@Data
public class SymbolFilter {
    private String stepSize;
    private String minQty;
    private String minNotional;
}