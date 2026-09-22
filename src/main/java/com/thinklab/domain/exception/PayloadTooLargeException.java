package com.thinklab.domain.exception;

/** The request body exceeds the gateway limit. */
public class PayloadTooLargeException extends BusinessException {

    private static final String ERROR_CODE = "ERR-GTW-00413";

    public PayloadTooLargeException(int limitBytes) {
        super(ERROR_CODE, "The request body exceeds the limit of " + limitBytes + " bytes.");
    }
}
