package ru.exceptions.aster;

public class AsterRateLimitException extends AsterApiException {
    public AsterRateLimitException(int httpStatus, Integer asterCode, String asterMessage, String responseBody) {
        super(httpStatus, asterCode, asterMessage, responseBody);
    }
}
