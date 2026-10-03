package com.thinklab.domain.exception;

/** There is no session to refresh: no refresh cookie, or the identity service no longer accepts it. */
public class SessionUnauthenticatedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-GTW-00401";

    public SessionUnauthenticatedException() {
        super(ERROR_CODE, "There is no active session; sign in again.");
    }
}
