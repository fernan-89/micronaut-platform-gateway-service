package com.thinklab.domain.exception;

import java.util.Objects;

/**
 * Domain Layer: Abstract base class for all business rule and domain invariant violations.
 *
 * <p>Mirrors the Hash Token Registry Service Domain's {@code BusinessException} pattern (ADR-013)
 * to keep RFC 7807 problem-detail mapping consistent across the platform.
 *
 * @author ThinkLab
 * @since 1.0
 */
public abstract class BusinessException extends RuntimeException {

    private final String errorCode;

    protected BusinessException(String errorCode, String message) {
        super(validateMessage(message));
        this.errorCode = validateErrorCode(errorCode);
    }

    protected BusinessException(String errorCode, String message, Throwable cause) {
        super(validateMessage(message), Objects.requireNonNull(cause, "Root cause cannot be null."));
        this.errorCode = validateErrorCode(errorCode);
    }

    public String getErrorCode() {
        return errorCode;
    }

    private static String validateErrorCode(String errorCode) {
        Objects.requireNonNull(errorCode, "Domain Exception constraint violated: errorCode cannot be null.");
        if (errorCode.isBlank()) {
            throw new IllegalArgumentException("Domain Exception constraint violated: errorCode cannot be blank.");
        }
        return errorCode;
    }

    private static String validateMessage(String message) {
        Objects.requireNonNull(message, "Domain Exception constraint violated: message cannot be null.");
        if (message.isBlank()) {
            throw new IllegalArgumentException("Domain Exception constraint violated: message cannot be blank.");
        }
        return message;
    }
}
