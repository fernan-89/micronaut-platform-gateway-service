package com.thinklab.infrastructure.adapter.in.web.handler;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValidationExceptionHandlerTest {

    @Test
    @DisplayName("bean-validation failures are delegated to the RFC 7807 handler")
    void delegatesToGlobalHandler() {
        HttpRequest<?> request = Mockito.mock(HttpRequest.class);
        Mockito.when(request.getPath()).thenReturn("/x");
        Mockito.when(request.getAttribute("traceId", String.class)).thenReturn(Optional.of("t-1"));

        HttpResponse<Map<String, Object>> response = new ValidationExceptionHandler(new GlobalExceptionHandler())
                .handle(request, new ConstraintViolationException("invalid", Collections.emptySet()));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        assertEquals("ERR-VALIDATION-00400", response.body().get("error_code"));
    }
}