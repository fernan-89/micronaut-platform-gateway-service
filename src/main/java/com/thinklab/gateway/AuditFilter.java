package com.thinklab.gateway;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import jakarta.inject.Inject;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * Records, once the response is known, every mutating request that carries a tenant on the compliance ledger. It runs
 * after the rate limiter, so a request the limiter turns away (429) is never forwarded and so never recorded as done,
 * and it sees the status the caller actually receives. Does nothing unless {@code gateway.audit.enabled} is true.
 */
@Filter(Filter.MATCH_ALL_PATTERN)
public class AuditFilter implements HttpServerFilter {

    private final AuditRecorder recorder;

    @Inject
    public AuditFilter(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.order() + 2;
    }

    @Override
    @NonNull
    public Publisher<MutableHttpResponse<?>> doFilter(@NonNull HttpRequest<?> request, @NonNull ServerFilterChain chain) {
        if (!recorder.enabled()) {
            return chain.proceed(request);
        }
        return Flux.from(chain.proceed(request)).doOnNext(response -> recorder.record(request, response.getStatus().getCode()));
    }
}
