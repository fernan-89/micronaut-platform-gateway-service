package com.thinklab.domain.exception;

/** The upstream service could not be reached. */
public class UpstreamUnavailableException extends BusinessException {

    private static final String ERROR_CODE = "ERR-GTW-00502";

    public UpstreamUnavailableException(String path, Throwable cause) {
        super(ERROR_CODE, "The upstream service for " + path + " is unavailable.", cause);
    }
}
