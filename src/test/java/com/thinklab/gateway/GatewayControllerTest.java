package com.thinklab.gateway;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage of the delegation itself: every HTTP method forwards to {@link UpstreamProxy} with
 * the right body nullability - GET/DELETE never carry one, POST/PUT/PATCH pass whatever the caller sent
 * through unchanged, even {@code null}. {@link GatewayIntegrationTest} proves the same contract end to end
 * through a real server; this test isolates the controller's own five one-line methods, which that
 * integration test cannot target individually.
 */
@SuppressWarnings("unchecked")
class GatewayControllerTest {

    private final UpstreamProxy proxy = mock(UpstreamProxy.class);
    private final HttpRequest<?> request = mock(HttpRequest.class);
    private GatewayController controller;

    @BeforeEach
    void setUp() {
        controller = new GatewayController(proxy);
    }

    @Test
    @DisplayName("GET forwards with a null body")
    void get() {
        MutableHttpResponse<byte[]> response = mock(MutableHttpResponse.class);
        when(proxy.forward(request, null)).thenReturn(Mono.just(response));

        StepVerifier.create(controller.get(request, "any/path")).expectNext(response).verifyComplete();
        verify(proxy).forward(request, null);
    }

    @Test
    @DisplayName("DELETE forwards with a null body")
    void delete() {
        MutableHttpResponse<byte[]> response = mock(MutableHttpResponse.class);
        when(proxy.forward(request, null)).thenReturn(Mono.just(response));

        StepVerifier.create(controller.delete(request, "any/path")).expectNext(response).verifyComplete();
        verify(proxy).forward(request, null);
    }

    @Test
    @DisplayName("POST forwards the given body unchanged")
    void post() {
        byte[] body = "{\"a\":1}".getBytes();
        MutableHttpResponse<byte[]> response = mock(MutableHttpResponse.class);
        when(proxy.forward(eq(request), eq(body))).thenReturn(Mono.just(response));

        StepVerifier.create(controller.post(request, "any/path", body)).expectNext(response).verifyComplete();
        verify(proxy).forward(request, body);
    }

    @Test
    @DisplayName("POST forwards a null body when the caller sent none")
    void postWithNullBody() {
        MutableHttpResponse<byte[]> response = mock(MutableHttpResponse.class);
        when(proxy.forward(eq(request), isNull())).thenReturn(Mono.just(response));

        StepVerifier.create(controller.post(request, "any/path", null)).expectNext(response).verifyComplete();
        verify(proxy).forward(request, null);
    }

    @Test
    @DisplayName("PUT forwards the given body unchanged")
    void put() {
        byte[] body = "{\"a\":1}".getBytes();
        MutableHttpResponse<byte[]> response = mock(MutableHttpResponse.class);
        when(proxy.forward(eq(request), eq(body))).thenReturn(Mono.just(response));

        StepVerifier.create(controller.put(request, "any/path", body)).expectNext(response).verifyComplete();
        verify(proxy).forward(request, body);
    }

    @Test
    @DisplayName("PATCH forwards the given body unchanged")
    void patch() {
        byte[] body = "{\"a\":1}".getBytes();
        MutableHttpResponse<byte[]> response = mock(MutableHttpResponse.class);
        when(proxy.forward(eq(request), eq(body))).thenReturn(Mono.just(response));

        StepVerifier.create(controller.patch(request, "any/path", body)).expectNext(response).verifyComplete();
        verify(proxy).forward(request, body);
    }
}
