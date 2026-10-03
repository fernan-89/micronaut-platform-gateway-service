package com.thinklab.domain.exception;

/** A session endpoint was called without the header only the platform's own web app sends (a cross-site request cannot add it). */
public class CsrfRejectedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-GTW-00403";

    public CsrfRejectedException() {
        super(ERROR_CODE, "This request must come from the platform's web app.");
    }
}
