package com.thinklab.domain.exception;

/** The upstream service did not answer in time. */
public class UpstreamTimeoutException extends BusinessException {

    private static final String ERROR_CODE = "ERR-GTW-00504";

    public UpstreamTimeoutException(String path) {
        super(ERROR_CODE, "The upstream service for " + path + " timed out.");
    }
}
