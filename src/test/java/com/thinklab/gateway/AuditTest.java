package com.thinklab.gateway;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The audit recording: what is described, how it is appended (fail-open) and when the filter bothers. */
@SuppressWarnings({"unchecked", "rawtypes"})
class AuditTest {

    private static final String TENANT = "11111111-2222-3333-4444-555555555555";
    private static final String ASSET = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private final HttpClient client = mock(HttpClient.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AuditProperties properties = new AuditProperties();
    private AuditRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new AuditRecorder(client, properties, meters);
    }

    private static HttpRequest<?> request(HttpMethod method, String path, String tenant, String executor) {
        var request = HttpRequest.create(method, path);
        if (tenant != null) {
            request.header("X-Tenant-Id", tenant);
        }
        if (executor != null) {
            request.header("X-Executor", executor);
        }
        return request;
    }

    // ------------------------------------------------------------ describer

    @Test
    @DisplayName("a mutation with a tenant is described: executor, method + masked path, domain, first id, status")
    void describesMutation() {
        var described = AuditEntryDescriber.describe(request(HttpMethod.PUT, "/it-asset-registry/v1/" + ASSET + "/control/deploy", TENANT, "alice"), 204).orElseThrow();

        assertEquals(TENANT, described.tenantId());
        assertEquals("alice", described.actor());
        assertEquals("PUT /it-asset-registry/v1/{id}/control/deploy", described.action());
        assertEquals("it-asset-registry", described.resourceType());
        assertEquals(ASSET, described.resourceId());
        assertEquals("status=204", described.detail());
    }

    @Test
    @DisplayName("every identifier is masked in the action, a path without one has no resource id, a missing executor is 'unknown'")
    void masksAndDefaults() {
        var two = AuditEntryDescriber.describe(request(HttpMethod.POST, "/it-topology-graph/v1/edge/" + ASSET + "/x/" + TENANT, TENANT, null), 201).orElseThrow();
        var none = AuditEntryDescriber.describe(request(HttpMethod.POST, "/it-asset-registry/v1/initiate", TENANT, ""), 201).orElseThrow();
        var rootOnly = AuditEntryDescriber.describe(request(HttpMethod.DELETE, "/it-asset-registry", TENANT, "bob"), 404).orElseThrow();

        assertEquals("POST /it-topology-graph/v1/edge/{id}/x/{id}", two.action());
        assertEquals(ASSET, two.resourceId());
        assertEquals("unknown", two.actor());
        assertNull(none.resourceId());
        assertEquals("unknown", none.actor());
        assertEquals("it-asset-registry", rootOnly.resourceType());
    }

    @Test
    @DisplayName("reads, requests without a valid tenant, the ledger's own domain and an empty path are not audited")
    void skips() {
        assertTrue(AuditEntryDescriber.describe(request(HttpMethod.GET, "/it-asset-registry/v1/retrieve", TENANT, "a"), 200).isEmpty());
        assertTrue(AuditEntryDescriber.describe(request(HttpMethod.POST, "/it-asset-registry/v1/initiate", null, "a"), 201).isEmpty());
        assertTrue(AuditEntryDescriber.describe(request(HttpMethod.POST, "/it-asset-registry/v1/initiate", "not-a-uuid", "a"), 201).isEmpty());
        assertTrue(AuditEntryDescriber.describe(request(HttpMethod.POST, "/compliance-audit-ledger/v1/initiate", TENANT, "a"), 201).isEmpty());
        assertTrue(AuditEntryDescriber.describe(request(HttpMethod.POST, "/", TENANT, "a"), 404).isEmpty());
    }

    @Test
    @DisplayName("fields longer than the ledger accepts are truncated rather than rejected")
    void truncates() {
        String longDomain = "d".repeat(300);
        var described = AuditEntryDescriber.describe(request(HttpMethod.POST, "/" + longDomain + "/v1/x", TENANT, "e".repeat(300)), 201).orElseThrow();

        assertEquals(200, described.resourceType().length());
        assertEquals(200, described.actor().length());
        assertEquals(200, described.action().length());
    }

    // ------------------------------------------------------------ recorder

    @Test
    @DisplayName("recording appends to the ledger with the tenant, the writer as executor, and counts it")
    void appendsToTheLedger() {
        when(client.exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class))).thenReturn(Mono.just(HttpResponse.created("{}")));

        recorder.record(request(HttpMethod.PUT, "/it-asset-registry/v1/" + ASSET + "/control/deploy", TENANT, "alice"), 204);

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).exchange(sent.capture(), any(Argument.class), any(Argument.class));
        HttpRequest<?> post = sent.getValue();
        assertEquals(HttpMethod.POST, post.getMethod());
        assertEquals("/compliance-audit-ledger/v1/initiate", post.getPath());
        assertEquals(TENANT, post.getHeaders().get("X-Tenant-Id"));
        assertEquals("platform-gateway", post.getHeaders().get("X-Executor"));
        Map<String, Object> body = (Map<String, Object>) post.getBody().orElseThrow();
        assertEquals("platform-gateway", body.get("source"));
        assertEquals("alice", body.get("actor"));
        assertEquals("PUT /it-asset-registry/v1/{id}/control/deploy", body.get("action"));
        assertEquals("it-asset-registry", body.get("resourceType"));
        assertEquals(ASSET, body.get("resourceId"));
        assertEquals("status=204", body.get("detail"));
        assertEquals(1.0, meters.counter("gateway.audit.recorded").count());
        assertEquals(0.0, meters.counter("gateway.audit.dropped").count());
    }

    @Test
    @DisplayName("fail-open: a ledger failure is counted and logged, never thrown at the caller")
    void ledgerFailureIsSwallowed() {
        when(client.exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class))).thenReturn(Mono.error(new IllegalStateException("ledger down")));

        recorder.record(request(HttpMethod.POST, "/it-asset-registry/v1/initiate", TENANT, "alice"), 201);

        assertEquals(1.0, meters.counter("gateway.audit.dropped").count());
        assertEquals(0.0, meters.counter("gateway.audit.recorded").count());
    }

    @Test
    @DisplayName("a request that is not audit-worthy never reaches the ledger")
    void nothingToRecord() {
        recorder.record(request(HttpMethod.GET, "/it-asset-registry/v1/retrieve", TENANT, "alice"), 200);

        verify(client, never()).exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class));
    }

    @Test
    @DisplayName("auditing is off by default and follows gateway.audit.enabled")
    void enabledFlag() {
        assertFalse(recorder.enabled());

        properties.setEnabled(true);

        assertTrue(recorder.enabled());
    }

    // ------------------------------------------------------------ filter

    @Test
    @DisplayName("the filter runs after the rate limiter, and records the status the caller receives")
    void filterRecords() {
        properties.setEnabled(true);
        when(client.exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class))).thenReturn(Mono.just(HttpResponse.created("{}")));
        AuditFilter filter = new AuditFilter(recorder);
        ServerFilterChain chain = mock(ServerFilterChain.class);
        HttpRequest<?> request = request(HttpMethod.PUT, "/it-asset-registry/v1/" + ASSET + "/control/deploy", TENANT, "alice");
        when(chain.proceed(any())).thenReturn(Mono.just(HttpResponse.<String>status(HttpStatus.CONFLICT)));

        assertEquals(ServerFilterPhase.SECURITY.order() + 2, filter.getOrder());
        StepVerifier.create(filter.doFilter(request, chain)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).exchange(sent.capture(), any(Argument.class), any(Argument.class));
        assertEquals("status=409", ((Map<String, Object>) sent.getValue().getBody().orElseThrow()).get("detail"));
    }

    @Test
    @DisplayName("with auditing off the filter just proceeds")
    void filterDisabled() {
        AuditFilter filter = new AuditFilter(recorder);
        ServerFilterChain chain = mock(ServerFilterChain.class);
        MutableHttpResponse<?> ok = HttpResponse.ok();
        when(chain.proceed(any())).thenReturn((org.reactivestreams.Publisher) Mono.just(ok));

        StepVerifier.create(filter.doFilter(request(HttpMethod.POST, "/it-asset-registry/v1/initiate", TENANT, "alice"), chain)).expectNext(ok).verifyComplete();

        verify(client, never()).exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class));
    }
}
