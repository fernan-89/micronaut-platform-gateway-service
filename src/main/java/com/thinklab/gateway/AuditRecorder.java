package com.thinklab.gateway;

import com.thinklab.gateway.AuditEntryDescriber.Described;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Appends one entry to the compliance ledger for each audit-worthy request (ADR-032 of the ledger service).
 *
 * <p><b>Asynchronous and fail-open:</b> the append is fired after the response is known and never awaited, and any
 * failure (ledger down, slow, rejecting) is logged and counted, never propagated - the ledger must not be able to
 * delay or break the request it describes. The cost is that an outage leaves a hole the chain cannot show;
 * {@code gateway.audit.dropped} makes it visible.
 */
@Singleton
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);
    private static final String LEDGER_INITIATE = "/compliance-audit-ledger/v1/initiate";

    private final HttpClient client;
    private final AuditProperties properties;
    private final MeterRegistry meters;

    @Inject
    public AuditRecorder(@Client("gateway-audit") HttpClient client, AuditProperties properties, MeterRegistry meters) {
        this.client = client;
        this.properties = properties;
        this.meters = meters;
    }

    boolean enabled() {
        return properties.isEnabled();
    }

    /** Fire-and-forget: returns immediately; the append runs on the client's own event loop. */
    void record(HttpRequest<?> request, int status) {
        AuditEntryDescriber.describe(request, status).ifPresent(this::append);
    }

    private void append(Described entry) {
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
        Mono.from(client.exchange(post, Argument.of(String.class), Argument.of(String.class)))
                .subscribe(
                        response -> meters.counter("gateway.audit.recorded").increment(),
                        failure -> {
                            meters.counter("gateway.audit.dropped").increment();
                            log.warn("[AUDIT] Could not append {} to the ledger (organisation {}); the request already completed. Reason: {}",
                                    entry.action(), entry.tenantId(), failure.getMessage());
                        });
    }
}
