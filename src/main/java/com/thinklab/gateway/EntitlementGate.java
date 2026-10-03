package com.thinklab.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Decides whether an organisation's plan includes a feature, by asking {@code subscription-billing-service}
 * ({@code entitlement/evaluate}). <b>Fail-open</b>, like the evaluation itself (billing ADR-032): billing down, slow or answering
 * something unexpected means the request goes through - a billing outage must never lock every customer out of the product. The
 * answers that were obtained are cached for a short time; failures are never cached, so recovery is immediate.
 */
@Singleton
public class EntitlementGate {

    private static final Logger log = LoggerFactory.getLogger(EntitlementGate.class);
    private static final String EVALUATE = "/subscription-billing/v1/entitlement/evaluate?feature=";
    static final int MAX_CACHED = 10_000;

    private record Answer(boolean allowed, long expiresAtNanos) {
    }

    private final HttpClient client;
    private final EntitlementProperties properties;
    private final MeterRegistry meters;
    private final LongSupplier nanoClock;
    private final Map<String, Answer> cache = new ConcurrentHashMap<>();

    @Inject
    public EntitlementGate(@Client("gateway-billing") HttpClient client, EntitlementProperties properties, MeterRegistry meters) {
        this(client, properties, meters, System::nanoTime);
    }

    EntitlementGate(HttpClient client, EntitlementProperties properties, MeterRegistry meters, LongSupplier nanoClock) {
        this.client = client;
        this.properties = properties;
        this.meters = meters;
        this.nanoClock = nanoClock;
    }

    boolean enabled() {
        return properties.isEnabled();
    }

    /** The feature a request to this path needs, if its Service Domain (first path segment) is gated. */
    Optional<String> featureFor(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        return Optional.ofNullable(properties.getFeatures().get(slash < 0 ? trimmed : trimmed.substring(0, slash)));
    }

    /** True when the organisation may use the feature - or when that could not be determined. */
    Mono<Boolean> allowed(String tenantId, String feature) {
        String key = tenantId + "|" + feature;
        long now = nanoClock.getAsLong();
        Answer cached = cache.get(key);
        if (cached != null && cached.expiresAtNanos() > now) {
            return Mono.just(cached.allowed());
        }
        var get = HttpRequest.GET(EVALUATE + URLEncoder.encode(feature, StandardCharsets.UTF_8)).header("X-Tenant-Id", tenantId);
        return Mono.from(client.exchange(get, Map.class))
                .map(response -> !Boolean.FALSE.equals(response.getBody(Map.class).map(body -> body.get("allowed")).orElse(null)))
                .doOnNext(allowed -> remember(key, allowed, now))
                .onErrorResume(failure -> {
                    meters.counter("gateway.entitlement.unavailable").increment();
                    log.warn("[ENTITLEMENT] Could not evaluate feature {} for organisation {}; letting the request through. Reason: {}",
                            feature, tenantId, failure.getMessage());
                    return Mono.just(true);
                })
                .defaultIfEmpty(true);
    }

    private void remember(String key, boolean allowed, long now) {
        if (cache.size() >= MAX_CACHED) {
            cache.clear();
        }
        cache.put(key, new Answer(allowed, now + properties.getCacheTtl().toNanos()));
    }
}
