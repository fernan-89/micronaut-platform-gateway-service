package com.thinklab.domain.exception;

/** No upstream is configured for the requested path, or the path is not exposed through the gateway. */
public class RouteNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-GTW-00404";

    public RouteNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
