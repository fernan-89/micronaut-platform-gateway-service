package com.thinklab.infrastructure.adapter.in.web.handler;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.validation.exceptions.ConstraintExceptionHandler;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;

import java.util.Map;

/**
 * Routes bean-validation failures through the platform's RFC 7807 problem shape.
 *
 * <p><b>Why this exists:</b> Micronaut ships a {@link ConstraintExceptionHandler} for
 * {@link ConstraintViolationException}. Because its type is more specific than the catch-all
 * {@code ExceptionHandler<Throwable>}, the framework always picked it, so a request body that failed
 * {@code @Valid} came back in Micronaut's own JSON envelope (no {@code error_code}) and the
 * {@link GlobalExceptionHandler} validation branch was unreachable through HTTP. It only surfaced in
 * the first live end-to-end run. Replacing the built-in handler restores the contract.
 */
@Produces
@Singleton
@Replaces(ConstraintExceptionHandler.class)
public class ValidationExceptionHandler
        implements ExceptionHandler<ConstraintViolationException, HttpResponse<Map<String, Object>>> {

    private final GlobalExceptionHandler delegate;

    public ValidationExceptionHandler(GlobalExceptionHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public HttpResponse<Map<String, Object>> handle(HttpRequest request, ConstraintViolationException exception) {
        return delegate.handle(request, exception);
    }
}
