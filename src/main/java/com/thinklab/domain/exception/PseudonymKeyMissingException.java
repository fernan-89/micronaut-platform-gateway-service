package com.thinklab.domain.exception;

/** No pseudonymisation key is configured, so there is no pseudonym to compute (ADR-027). */
public class PseudonymKeyMissingException extends BusinessException {

    public PseudonymKeyMissingException() {
        super("ERR-GTW-00409", "No pseudonymisation key is configured on the gateway, so sign-ins were recorded without a per-person pseudonym.");
    }
}
