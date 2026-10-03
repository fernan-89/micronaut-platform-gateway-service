package com.thinklab.domain.exception;

/** Too many pseudonym lookups by the same person within the hour (ADR-027). */
public class InvestigationRateExceededException extends BusinessException {

    public InvestigationRateExceededException(long retryAfterSeconds) {
        super("ERR-GTW-00429", "Too many pseudonym lookups; try again in " + retryAfterSeconds + " second(s).");
    }
}
