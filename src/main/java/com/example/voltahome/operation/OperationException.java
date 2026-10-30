package com.example.voltahome.operation;

/**
 * README: "Several operations, one callback". The operation was not started or not executed. The code is one of the
 * operation error codes of the README; the application maps it to a message of its own.
 */
public class OperationException extends RuntimeException {

    private final String code;

    public OperationException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
