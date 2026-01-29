package ru.dto.exchanges.extended;

import lombok.Data;

import java.util.List;

@Data
public class ExtendedOrdersHistoryResponse {
    private List<ExtendedOrderHistoryItem> data;
    private Pagination pagination;
    private String status;
}