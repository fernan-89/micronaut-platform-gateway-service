package com.thinklab.gateway;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import jakarta.inject.Inject;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rate-limits every request except the management endpoints. It runs right after the security filter, so an
 * authenticated caller is limited by identity ({@code X-Executor}, derived from the token) and an anonymous one (login
 * attempts) by network address: the bucket a brute-force attempt burns is its own, not a legitimate user's.
 */
@Filter(Filter.MATCH_ALL_PATTERN)
public class RateLimitFilter implements HttpServerFilter {

    private final RateLimiter rateLimiter;

    @Inject
    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.order() + 1;
    }

    @Override
    @NonNull
    public Publisher<MutableHttpResponse<?>> doFilter(@NonNull HttpRequest<?> request, @NonNull ServerFilterChain chain) {
        String path = request.getPath();
        if (path.startsWith("/health") || path.startsWith("/prometheus") || path.startsWith("/metrics")) {
            return chain.proceed(request);
        }
        long retryAfter = rateLimiter.tryAcquire(callerOf(request));
        if (retryAfter == 0) {
            return chain.proceed(request);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://api.thinklab.com/errors/err-gtw-00429");
        body.put("title", HttpStatus.TOO_MANY_REQUESTS.getReason());
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.getCode());
        body.put("error_code", "ERR-GTW-00429");
        body.put("detail", "Rate limit exceeded. Retry after " + retryAfter + " second(s).");
        body.put("timestamp", Instant.now().toString());
        return Mono.just(HttpResponse.<Map<String, Object>>status(HttpStatus.TOO_MANY_REQUESTS).body(body)
                .header("Retry-After", String.valueOf(retryAfter)));
    }

    private static String callerOf(HttpRequest<?> request) {
        String executor = request.getHeaders().get("X-Executor");
        if (executor != null && !executor.isBlank()) {
            return "id:" + executor;
        }
        return "ip:" + (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress() : "unknown");
    }
}
