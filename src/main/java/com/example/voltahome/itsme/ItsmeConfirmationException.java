package com.example.voltahome.itsme;

/**
 * README: "Confirmation of an operation". The operation was not confirmed. The code is one of the confirmation error
 * codes of the README; the application maps it to a message of its own and must not execute the operation.
 */
public class ItsmeConfirmationException extends RuntimeException {

    private final String code;

    public ItsmeConfirmationException(String code) {
        this(code, null);
    }

    public ItsmeConfirmationException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
