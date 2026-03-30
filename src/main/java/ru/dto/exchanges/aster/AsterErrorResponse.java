package ru.dto.exchanges.aster;

import lombok.Data;

@Data
public class AsterErrorResponse {
    private int code;
    private String msg;
}