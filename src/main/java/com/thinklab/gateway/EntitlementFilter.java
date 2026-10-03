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
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns away, with 403, a request for a Service Domain whose feature the caller's plan does not include (ADR-026). It runs after
 * the rate limiter and the audit filter, so a refusal is itself recorded. Only requests that carry a tenant are judged: a sign-in
 * (no tenant yet) is never blocked by a plan. Does nothing unless {@code gateway.entitlements.enabled} is true.
 */
@Filter(Filter.MATCH_ALL_PATTERN)
public class EntitlementFilter implements HttpServerFilter {

    private final EntitlementGate gate;

    @Inject
    public EntitlementFilter(EntitlementGate gate) {
        this.gate = gate;
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.order() + 3;
    }

    @Override
    @NonNull
    public Publisher<MutableHttpResponse<?>> doFilter(@NonNull HttpRequest<?> request, @NonNull ServerFilterChain chain) {
        String tenant = request.getHeaders().get("X-Tenant-Id");
        if (!gate.enabled() || tenant == null || tenant.isBlank()) {
            return chain.proceed(request);
        }
        return gate.featureFor(request.getPath())
                .<Publisher<MutableHttpResponse<?>>>map(feature -> gate.allowed(tenant, feature)
                        .flatMapMany(allowed -> allowed ? chain.proceed(request) : Flux.just(refusal(feature))))
                .orElseGet(() -> chain.proceed(request));
    }

    private static MutableHttpResponse<?> refusal(String feature) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://api.thinklab.com/errors/err-gtw-00403");
        body.put("title", HttpStatus.FORBIDDEN.getReason());
        body.put("status", HttpStatus.FORBIDDEN.getCode());
        body.put("error_code", "ERR-GTW-00403");
        body.put("detail", "Your plan does not include '" + feature + "'.");
        body.put("timestamp", Instant.now().toString());
        return HttpResponse.<Map<String, Object>>status(HttpStatus.FORBIDDEN).body(body);
    }
}
