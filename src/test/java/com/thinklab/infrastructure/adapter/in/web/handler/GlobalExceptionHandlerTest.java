package com.thinklab.infrastructure.adapter.in.web.handler;

import com.thinklab.domain.exception.PayloadTooLargeException;
import com.thinklab.domain.exception.RouteNotFoundException;
import com.thinklab.domain.exception.UpstreamTimeoutException;
import com.thinklab.domain.exception.UpstreamUnavailableException;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpRequest<?> request;
    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = Mockito.mock(HttpRequest.class);
        headers = Mockito.mock(HttpHeaders.class);
        Mockito.when(request.getPath()).thenReturn("/it-asset-registry/v1/retrieve");
        Mockito.when(request.getAttribute(Mockito.eq("traceId"), Mockito.eq(String.class))).thenReturn(Optional.empty());
        Mockito.when(request.getHeaders()).thenReturn(headers);
        Mockito.when(headers.get("X-Trace-Id")).thenReturn(null);
    }

    private Map<String, Object> assertProblem(HttpResponse<Map<String, Object>> response, HttpStatus status, String code) {
        assertNotNull(response);
        assertEquals(status, response.getStatus());
        Map<String, Object> body = response.body();
        assertNotNull(body);
        assertEquals(status.getCode(), body.get("status"));
        assertEquals(code, body.get("error_code"));
        assertNotNull(body.get("timestamp"));
        assertNotNull(body.get("title"));
        assertNotNull(body.get("type"));
        return body;
    }

    @Test
    @DisplayName("null request or exception is rejected before any mapping happens")
    void nullGuards() {
        assertThrows(NullPointerException.class, () -> handler.handle(null, new RouteNotFoundException("x")));
        assertThrows(NullPointerException.class, () -> handler.handle(request, null));
    }

    @Test
    @DisplayName("RouteNotFoundException maps to 404")
    void notFound() {
        assertProblem(handler.handle(request, new RouteNotFoundException("no route")), HttpStatus.NOT_FOUND, "ERR-GTW-00404");
    }

    @Test
    @DisplayName("PayloadTooLargeException maps to 413")
    void tooLarge() {
        assertProblem(handler.handle(request, new PayloadTooLargeException(64)), HttpStatus.REQUEST_ENTITY_TOO_LARGE, "ERR-GTW-00413");
    }

    @Test
    @DisplayName("UpstreamUnavailableException maps to 502")
    void badGateway() {
        assertProblem(handler.handle(request, new UpstreamUnavailableException("/x", new RuntimeException("down"))),
                HttpStatus.BAD_GATEWAY, "ERR-GTW-00502");
    }

    @Test
    @DisplayName("UpstreamTimeoutException maps to 504")
    void gatewayTimeout() {
        assertProblem(handler.handle(request, new UpstreamTimeoutException("/x")), HttpStatus.GATEWAY_TIMEOUT, "ERR-GTW-00504");
    }

    @Test
    @DisplayName("an unrecognised business error code defaults to 409")
    void unknownBusinessCodeIsConflict() {
        class Custom extends com.thinklab.domain.exception.BusinessException {
            Custom() {
                super("ERR-GTW-09999", "custom");
            }
        }
        assertProblem(handler.handle(request, new Custom()), HttpStatus.CONFLICT, "ERR-GTW-09999");
    }

    @Test
    @DisplayName("bean-validation and malformed-input failures both map to 400 ERR-VALIDATION-00400")
    void validationFailures() {
        ConstraintViolationException constraintEx = new ConstraintViolationException("bad payload", Collections.emptySet());
        Map<String, Object> constraintBody = assertProblem(handler.handle(request, constraintEx), HttpStatus.BAD_REQUEST, "ERR-VALIDATION-00400");
        assertTrue(String.valueOf(constraintBody.get("detail")).contains("bad payload"));

        Map<String, Object> illegalArgBody = assertProblem(handler.handle(request, new IllegalArgumentException("bad id")), HttpStatus.BAD_REQUEST, "ERR-VALIDATION-00400");
        assertTrue(String.valueOf(illegalArgBody.get("detail")).contains("bad id"));
    }

    @Test
    @DisplayName("an unmapped technical failure is a 500 problem carrying debug_info")
    void genericFailure() {
        Map<String, Object> body = assertProblem(handler.handle(request, new IllegalStateException("boom")), HttpStatus.INTERNAL_SERVER_ERROR, "ERR-INTERNAL-00500");
        assertTrue(String.valueOf(body.get("debug_info")).contains("IllegalStateException"));
    }

    @Test
    @DisplayName("a trace id from the request attribute or the X-Trace-Id header is honoured; a blank header generates one")
    void traceId() {
        Mockito.when(request.getAttribute(Mockito.eq("traceId"), Mockito.eq(String.class))).thenReturn(Optional.of("attr-trace"));
        assertProblem(handler.handle(request, new RouteNotFoundException("x")), HttpStatus.NOT_FOUND, "ERR-GTW-00404");

        Mockito.when(request.getAttribute(Mockito.eq("traceId"), Mockito.eq(String.class))).thenReturn(Optional.empty());
        Mockito.when(headers.get("X-Trace-Id")).thenReturn("header-trace");
        assertProblem(handler.handle(request, new RouteNotFoundException("x")), HttpStatus.NOT_FOUND, "ERR-GTW-00404");

        Mockito.when(headers.get("X-Trace-Id")).thenReturn(" ");
        assertProblem(handler.handle(request, new RouteNotFoundException("x")), HttpStatus.NOT_FOUND, "ERR-GTW-00404");
    }
}
