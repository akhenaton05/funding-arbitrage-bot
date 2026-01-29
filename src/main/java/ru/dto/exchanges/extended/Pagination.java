package ru.dto.exchanges.extended;

import lombok.Data;

@Data
public class Pagination {
    private int count;
    private long cursor;
}