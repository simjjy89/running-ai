package com.runningai.common.exception;

/** A well-formed request that the application cannot fulfil (HTTP 422), with a machine-readable code. */
public class UnprocessableRequestException extends RuntimeException {

    private final String code;

    public UnprocessableRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
