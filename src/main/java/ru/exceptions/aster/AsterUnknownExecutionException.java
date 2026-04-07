package ru.exceptions.aster;

public class AsterUnknownExecutionException extends AsterApiException {
    public AsterUnknownExecutionException(int httpStatus, Integer asterCode, String asterMessage, String responseBody) {
        super(httpStatus, asterCode, asterMessage, responseBody);
    }
}
