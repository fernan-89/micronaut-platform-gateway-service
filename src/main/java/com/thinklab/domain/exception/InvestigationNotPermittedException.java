package com.thinklab.domain.exception;

/** A pseudonym lookup was asked for by someone below ADMIN (ADR-027). */
public class InvestigationNotPermittedException extends BusinessException {

    public InvestigationNotPermittedException() {
        super("ERR-GTW-00403", "Looking a person up behind a pseudonym needs the ADMIN role.");
    }
}
