package com.thinklab.gateway;

import com.thinklab.gateway.AuditEntryDescriber.Described;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.json.JsonMapper;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Appends one entry to the compliance ledger for each audit-worthy request (ADR-023, and ADR-032 of the ledger service).
 *
 * <p><b>Asynchronous, ordered and fail-open.</b> The caller only enqueues: appends run one at a time, in the order the
 * requests completed, on a bounded queue. Ordering matters because a ledger position is assigned on arrival - sent in
 * parallel, a later request's entry could overtake an earlier one's and the chain would no longer read in the order
 * things happened (found live: an {@code initiate} landed at position 4). Failures (ledger down, slow, rejecting) and a
 * full queue are logged and counted ({@code gateway.audit.dropped}), never propagated: the ledger must not be able to
 * delay or break the request it describes. The cost is that an outage leaves a hole the chain cannot show.
 */
@Singleton
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);
    private static final String LEDGER_INITIATE = "/compliance-audit-ledger/v1/initiate";
    static final int QUEUE_CAPACITY = 1000;

    private final HttpClient client;
    private final AuditProperties properties;
    private final MeterRegistry meters;
    private final JsonMapper json;
    private final Sinks.Many<Described> queue = Sinks.many().unicast().onBackpressureBuffer(Queues.<Described>get(QUEUE_CAPACITY).get());

    @Inject
    public AuditRecorder(@Client("gateway-audit") HttpClient client, AuditProperties properties, MeterRegistry meters, JsonMapper json) {
        this.client = client;
        this.properties = properties;
        this.meters = meters;
        this.json = json;
        queue.asFlux().concatMap(this::append).subscribe();
    }

    boolean enabled() {
        return properties.isEnabled();
    }

    /** Returns immediately: the entry is queued and appended in order, off the request path. */
    void record(HttpRequest<?> request, int status) {
        AuditEntryDescriber.describe(request, status, properties.getPseudonymKey()).ifPresent(this::enqueue);
    }

    private void enqueue(Described entry) {
        if (queue.tryEmitNext(entry).isFailure()) {
            dropped(entry, "the audit queue is full");
        }
    }

    /**
     * Records a sign-in attempt (the caller already has the response status). Data minimisation: see
     * {@link AuditEntryDescriber#describeSignIn}. A body that cannot be parsed is simply not recorded - and the parse error is
     * deliberately neither logged nor kept, because its message can quote the very text (the password) we must never retain.
     */
    void recordSignIn(byte[] body, int status) {
        if (!enabled() || body == null) {
            return;
        }
        Map<String, Object> credentials;
        try {
            credentials = json.readValue(body, Argument.mapOf(String.class, Object.class));
        } catch (IOException | RuntimeException unreadable) {
            return;
        }
        AuditEntryDescriber.describeSignIn(credentials.get("organisationId"), credentials.get("email"), status, properties.getPseudonymKey())
                .ifPresent(this::enqueue);
    }

    @PreDestroy
    void close() {
        queue.tryEmitComplete();
    }

    private Mono<Void> append(Described entry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", AuditEntryDescriber.SOURCE);
        body.put("actor", entry.actor());
        body.put("action", entry.action());
        body.put("resourceType", entry.resourceType());
        body.put("resourceId", entry.resourceId());
        body.put("detail", entry.detail());
        MutableHttpRequest<Map<String, Object>> post = HttpRequest.<Map<String, Object>>POST(LEDGER_INITIATE, body)
                .header("X-Tenant-Id", entry.tenantId())
                .header("X-Executor", AuditEntryDescriber.SOURCE);
        return Mono.from(client.exchange(post, Argument.of(String.class), Argument.of(String.class)))
                .doOnSuccess(response -> meters.counter("gateway.audit.recorded").increment())
                .onErrorResume(failure -> {
                    dropped(entry, failure.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private void dropped(Described entry, String reason) {
        meters.counter("gateway.audit.dropped").increment();
        log.warn("[AUDIT] Could not append {} to the ledger (organisation {}); the request already completed. Reason: {}",
                entry.action(), entry.tenantId(), reason);
    }
}
