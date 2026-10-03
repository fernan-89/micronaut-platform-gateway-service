package com.thinklab.infrastructure.adapter.in.web.handler;

import com.thinklab.domain.exception.BusinessException;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Infrastructure Adapter: Global Exception Handler for centralized error management.
 *
 * <p><b>Architectural Role (ADR-013):</b> Mirrors every other Service Domain's exception handler, so the
 * gateway emits the same RFC 7807 "Problem Details" shape (including {@code error_code}) as the services it
 * fronts, even for failures that only the gateway itself can raise.
 *
 * <p><b>Gateway-specific mappings (ADR-022):</b> {@code ERR-GTW-00404} (no route / denied path),
 * {@code ERR-GTW-00413} (request body over the limit), {@code ERR-GTW-00502} (upstream unreachable) and
 * {@code ERR-GTW-00504} (upstream timeout). Any other {@code ERR-GTW-*} code defaults to 409 Conflict, the
 * platform-wide convention for state conflicts (ADR-019); the gateway itself does not currently raise one.
 */
@Produces
@Singleton
@Requires(classes = {ExceptionHandler.class})
public class GlobalExceptionHandler implements ExceptionHandler<Throwable, HttpResponse<Map<String, Object>>> {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROBLEM_TYPE_BASE_URI = "https://api.thinklab.com/errors/";
    private static final String MDC_TRACE_KEY = "traceId";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public HttpResponse<Map<String, Object>> handle(HttpRequest request, Throwable exception) {
        Objects.requireNonNull(request, "HTTP request context cannot be null.");
        Objects.requireNonNull(exception, "Caught exception cannot be null.");

        String traceId = request.getAttribute(MDC_TRACE_KEY, String.class)
                .orElseGet(() -> request.getHeaders().get(TRACE_ID_HEADER));

        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        final String activeTraceId = traceId;

        MDC.put(MDC_TRACE_KEY, activeTraceId);
        try {
            String path = request.getPath();

            if (exception instanceof BusinessException businessEx) {
                log.info("[ACTION: GLOBAL_EXCEPTION_HANDLER] [PATH: {}] [CODE: {}] - Business rule violation intercepted: {}",
                        path, businessEx.getErrorCode(), businessEx.getMessage());
                return handleBusinessException(businessEx, path);
            }

            if (exception instanceof ConstraintViolationException constraintEx) {
                log.warn("[ACTION: GLOBAL_EXCEPTION_HANDLER] [PATH: {}] - JSR-380 input validation failure intercepted: {}",
                        path, constraintEx.getMessage());
                return handleValidationException(constraintEx.getMessage(), path);
            }

            if (exception instanceof IllegalArgumentException illegalArgumentEx) {
                log.warn("[ACTION: GLOBAL_EXCEPTION_HANDLER] [PATH: {}] - Malformed input intercepted: {}",
                        path, illegalArgumentEx.getMessage());
                return handleValidationException(illegalArgumentEx.getMessage(), path);
            }

            log.error("[ACTION: GLOBAL_EXCEPTION_HANDLER] [PATH: {}] - CRITICAL: Unhandled technical failure encountered in pipeline: {}",
                    path, exception.getMessage(), exception);
            return handleGenericException(exception, path);
        } finally {
            MDC.remove(MDC_TRACE_KEY);
        }
    }

    private HttpResponse<Map<String, Object>> handleBusinessException(BusinessException ex, String path) {
        HttpStatus status = switch (ex.getErrorCode()) {
            case "ERR-GTW-00404" -> HttpStatus.NOT_FOUND;
            case "ERR-GTW-00401" -> HttpStatus.UNAUTHORIZED;
            case "ERR-GTW-00403" -> HttpStatus.FORBIDDEN;
            case "ERR-GTW-00413" -> HttpStatus.REQUEST_ENTITY_TOO_LARGE;
            case "ERR-GTW-00502" -> HttpStatus.BAD_GATEWAY;
            case "ERR-GTW-00504" -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.CONFLICT;
        };

        Map<String, Object> problem = createProblemDetails(
                URI.create(PROBLEM_TYPE_BASE_URI + ex.getErrorCode().toLowerCase().replace('_', '-')),
                ex.getErrorCode(),
                status.getCode(),
                status.getReason(),
                ex.getMessage(),
                path
        );

        return HttpResponse.status(status).body(problem);
    }

    private HttpResponse<Map<String, Object>> handleValidationException(String message, String path) {
        Map<String, Object> problem = createProblemDetails(
                URI.create(PROBLEM_TYPE_BASE_URI + "err-validation-00400"),
                "ERR-VALIDATION-00400",
                HttpStatus.BAD_REQUEST.getCode(),
                HttpStatus.BAD_REQUEST.getReason(),
                "The request failed structural validation constraints: " + message,
                path
        );

        return HttpResponse.badRequest().body(problem);
    }

    private HttpResponse<Map<String, Object>> handleGenericException(Throwable ex, String path) {
        Map<String, Object> problem = createProblemDetails(
                URI.create(PROBLEM_TYPE_BASE_URI + "err-internal-00500"),
                "ERR-INTERNAL-00500",
                HttpStatus.INTERNAL_SERVER_ERROR.getCode(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReason(),
                "An unexpected technical failure occurred within the processing pipeline.",
                path
        );

        problem.put("debug_info", ex.getClass().getSimpleName() + ": " + ex.getMessage());

        return HttpResponse.serverError(problem);
    }

    private Map<String, Object> createProblemDetails(
            URI type,
            String code,
            int status,
            String title,
            String detail,
            String instance
    ) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", type.toString());
        problem.put("title", title);
        problem.put("status", status);
        problem.put("error_code", code);
        problem.put("detail", detail);
        problem.put("instance", instance);
        problem.put("timestamp", Instant.now().toString());
        return problem;
    }
}
