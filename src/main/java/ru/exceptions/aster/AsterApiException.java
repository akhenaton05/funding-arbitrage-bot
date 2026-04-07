package ru.exceptions.aster;

public class AsterApiException extends RuntimeException {

    private final int httpStatus;
    private final Integer asterCode;
    private final String asterMessage;
    private final String responseBody;

    public AsterApiException(int httpStatus, Integer asterCode, String asterMessage, String responseBody) {
        super(buildMessage(httpStatus, asterCode, asterMessage));
        this.httpStatus = httpStatus;
        this.asterCode = asterCode;
        this.asterMessage = asterMessage;
        this.responseBody = responseBody;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public Integer getAsterCode() {
        return asterCode;
    }

    public String getAsterMessage() {
        return asterMessage;
    }

    public String getResponseBody() {
        return responseBody;
    }

    private static String buildMessage(int httpStatus, Integer asterCode, String asterMessage) {
        return "Aster API error: httpStatus=" + httpStatus
                + ", asterCode=" + asterCode
                + ", message=" + asterMessage;
    }
}