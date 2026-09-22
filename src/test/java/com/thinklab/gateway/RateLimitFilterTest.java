package com.thinklab.gateway;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.filter.ServerFilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final RateLimitFilter filter = new RateLimitFilter(rateLimiter);
    private final ServerFilterChain chain = mock(ServerFilterChain.class);

    private HttpRequest<?> requestWithHeaders(String path, String executor, InetSocketAddress remote) {
        HttpRequest<?> request = mock(HttpRequest.class);
        HttpHeaders headers = mock(HttpHeaders.class);
        when(request.getPath()).thenReturn(path);
        when(request.getHeaders()).thenReturn(headers);
        when(headers.get("X-Executor")).thenReturn(executor);
        when(request.getRemoteAddress()).thenReturn(remote);
        return request;
    }

    @Test
    @DisplayName("the filter runs right after the security phase")
    void order() {
        assertEquals(io.micronaut.http.filter.ServerFilterPhase.SECURITY.order() + 1, filter.getOrder());
    }

    @Test
    @DisplayName("health, prometheus and metrics bypass the limiter entirely")
    void managementEndpointsBypass() {
        when(chain.proceed(any())).thenReturn(Mono.just(HttpResponse.ok()));

        for (String path : new String[]{"/health/liveness", "/prometheus", "/metrics"}) {
            StepVerifier.create(filter.doFilter(requestWithHeaders(path, null, null), chain))
                    .expectNextMatches(r -> r.getStatus().getCode() == 200).verifyComplete();
        }

        verify(rateLimiter, never()).tryAcquire(anyString());
    }

    @Test
    @DisplayName("an allowed request proceeds down the chain")
    void allowed() {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(0L);
        when(chain.proceed(any())).thenReturn(Mono.just(HttpResponse.ok()));

        StepVerifier.create(filter.doFilter(requestWithHeaders("/it-asset-registry/v1/retrieve", null, null), chain))
                .expectNextMatches(r -> r.getStatus().getCode() == 200).verifyComplete();
    }

    @Test
    @DisplayName("a denied request is a 429 RFC 7807 problem with a Retry-After header")
    void denied() {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(3L);

        MutableHttpResponse<?> response = Flux.from(filter.doFilter(requestWithHeaders("/it-asset-registry/v1/retrieve", null, null), chain)).blockFirst();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatus());
        assertEquals("3", response.getHeaders().get("Retry-After"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody().orElseThrow();
        assertEquals("ERR-GTW-00429", body.get("error_code"));
        assertTrue(String.valueOf(body.get("detail")).contains("3 second"));
    }

    @Test
    @DisplayName("an authenticated caller is keyed by executor; an anonymous one by remote address, or 'unknown' without one")
    void callerIdentity() {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(0L);
        when(chain.proceed(any())).thenReturn(Mono.just(HttpResponse.ok()));

        Flux.from(filter.doFilter(requestWithHeaders("/x", "user-7", null), chain)).blockFirst();
        verify(rateLimiter).tryAcquire(eq("id:user-7"));

        InetSocketAddress remote = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
        Flux.from(filter.doFilter(requestWithHeaders("/x", null, remote), chain)).blockFirst();
        verify(rateLimiter).tryAcquire(eq("ip:" + InetAddress.getLoopbackAddress().getHostAddress()));

        Flux.from(filter.doFilter(requestWithHeaders("/x", " ", null), chain)).blockFirst();
        verify(rateLimiter).tryAcquire(eq("ip:unknown"));

        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("nowhere.invalid", 80);
        Flux.from(filter.doFilter(requestWithHeaders("/x", null, unresolved), chain)).blockFirst();
        verify(rateLimiter, org.mockito.Mockito.times(2)).tryAcquire(eq("ip:unknown"));
    }
}
