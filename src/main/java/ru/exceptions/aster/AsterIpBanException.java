package ru.exceptions.aster;

public class AsterIpBanException extends AsterApiException {
    public AsterIpBanException(int httpStatus, Integer asterCode, String asterMessage, String responseBody) {
        super(httpStatus, asterCode, asterMessage, responseBody);
    }
}
