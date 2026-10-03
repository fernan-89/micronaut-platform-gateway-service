package com.thinklab.gateway;

import com.thinklab.gateway.AuditEntryDescriber.Described;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        recorder = new AuditRecorder(client, properties, meters, io.micronaut.json.JsonMapper.createDefault());
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
        var described = AuditEntryDescriber.describe(request(HttpMethod.PUT, "/it-asset-registry/v1/" + ASSET + "/control/deploy", TENANT, "alice"), 204, "").orElseThrow();

        assertEquals(TENANT, described.tenantId());
        assertEquals("alice", described.actor());
        assertEquals("PUT /it-asset-registry/v1/{id}/control/deploy", described.action());
        assertEquals("it-asset-registry", described.resourceType());
        assertEquals(ASSET, described.resourceId());
        assertEquals("status=204", described.detail());
    }

    @Test
    @DisplayName("an executor that looks like an email is recorded as a keyed pseudonym, never as the address")
    void emailExecutorIsPseudonymised() {
        var keyed = AuditEntryDescriber.describe(request(HttpMethod.PUT, "/it-asset-registry/v1/" + ASSET + "/control/ready", TENANT, "Alice@Example.com"), 204, "k3y").orElseThrow();
        var unkeyed = AuditEntryDescriber.describe(request(HttpMethod.PUT, "/it-asset-registry/v1/" + ASSET + "/control/ready", TENANT, "alice@example.com"), 204, "").orElseThrow();

        assertTrue(keyed.actor().matches("user:[0-9a-f]{32}"), keyed.actor());
        assertEquals(keyed.actor(), "user:" + AuditPseudonymizer.of("k3y", "alice@example.com"));
        assertEquals("user:unkeyed", unkeyed.actor());
        assertFalse(keyed.actor().toLowerCase().contains("alice"));
    }

    @Test
    @DisplayName("every identifier is masked in the action, a path without one has no resource id, a missing executor is 'unknown'")
    void masksAndDefaults() {
        var two = AuditEntryDescriber.describe(request(HttpMethod.POST, "/it-topology-graph/v1/edge/" + ASSET + "/x/" + TENANT, TENANT, null), 201, "").orElseThrow();
        var none = AuditEntryDescriber.describe(request(HttpMethod.POST, "/it-asset-registry/v1/initiate", TENANT, ""), 201, "").orElseThrow();
        var rootOnly = AuditEntryDescriber.describe(request(HttpMethod.DELETE, "/it-asset-registry", TENANT, "bob"), 404, "").orElseThrow();

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
        assertTrue(AuditEntryDescriber.describe(request(HttpMethod.GET, "/it-asset-registry/v1/retrieve", TENANT, "a"), 200, "").isEmpty());
        assertTrue(AuditEntryDescriber.describe(request(HttpMethod.POST, "/it-asset-registry/v1/initiate", null, "a"), 201, "").isEmpty());
        assertTrue(AuditEntryDescriber.describe(request(HttpMethod.POST, "/it-asset-registry/v1/initiate", "not-a-uuid", "a"), 201, "").isEmpty());
        assertTrue(AuditEntryDescriber.describe(request(HttpMethod.POST, "/compliance-audit-ledger/v1/initiate", TENANT, "a"), 201, "").isEmpty());
        assertTrue(AuditEntryDescriber.describe(request(HttpMethod.POST, "/", TENANT, "a"), 404, "").isEmpty());
    }

    @Test
    @DisplayName("fields longer than the ledger accepts are truncated rather than rejected")
    void truncates() {
        String longDomain = "d".repeat(300);
        var described = AuditEntryDescriber.describe(request(HttpMethod.POST, "/" + longDomain + "/v1/x", TENANT, "e".repeat(300)), 201, "").orElseThrow();

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
    @DisplayName("appendRequired reports the outcome to the caller: success completes and is counted, a failure is propagated (not swallowed)")
    void appendRequiredPropagates() {
        var entry = new Described(TENANT, "alice", "POST /gateway/v1/investigation/pseudonym", "investigation", null, "target=login:abc; reason=ticket 12345");
        when(client.exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class))).thenReturn(Mono.just(HttpResponse.created("{}")));

        StepVerifier.create(recorder.appendRequired(entry)).verifyComplete();
        assertEquals(1.0, meters.counter("gateway.audit.recorded").count());

        when(client.exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class))).thenReturn(Mono.error(new IllegalStateException("ledger down")));
        StepVerifier.create(recorder.appendRequired(entry)).expectError(IllegalStateException.class).verify();
        assertEquals(0.0, meters.counter("gateway.audit.dropped").count());
    }

    @Test
    @DisplayName("the gateway's own endpoints (session bridge, investigation) are not described as proxied requests")
    void gatewayOwnEndpointsAreNotDescribed() {
        assertTrue(AuditEntryDescriber.describe(request(HttpMethod.POST, "/gateway/v1/investigation/pseudonym", TENANT, "a"), 200, "").isEmpty());
    }

    @Test
    @DisplayName("appends run one at a time, in the order the requests completed: the second waits for the first")
    void appendsAreOrdered() {
        reactor.core.publisher.Sinks.One<HttpResponse<String>> firstDone = reactor.core.publisher.Sinks.one();
        when(client.exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class)))
                .thenReturn(firstDone.asMono()).thenReturn(Mono.just(HttpResponse.created("{}")));

        recorder.record(request(HttpMethod.POST, "/it-asset-registry/v1/initiate", TENANT, "alice"), 201);
        recorder.record(request(HttpMethod.PUT, "/it-asset-registry/v1/" + ASSET + "/control/ready", TENANT, "alice"), 204);

        verify(client, org.mockito.Mockito.times(1)).exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class));
        firstDone.tryEmitValue(HttpResponse.created("{}"));
        verify(client, org.mockito.Mockito.times(2)).exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class));
        assertEquals(2.0, meters.counter("gateway.audit.recorded").count());
    }

    @Test
    @DisplayName("requests completing on many threads at once are all recorded: a sink refuses a concurrent emission, so emission is serialised")
    void concurrentRequestsAreAllRecorded() throws Exception {
        when(client.exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class))).thenReturn(Mono.just(HttpResponse.created("{}")));
        int threads = 16;
        int perThread = 50;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    recorder.record(request(HttpMethod.PUT, "/it-asset-registry/v1/" + ASSET + "/control/ready", TENANT, "alice"), 204);
                }
                return null;
            }));
        }
        start.countDown();
        for (var future : futures) {
            future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals((double) threads * perThread, meters.counter("gateway.audit.recorded").count());
        assertEquals(0.0, meters.counter("gateway.audit.dropped").count());
    }

    @Test
    @DisplayName("an entry that cannot be queued (queue full or closed) is dropped and counted, never thrown")
    void unqueueableEntryIsDropped() {
        recorder.close();

        recorder.record(request(HttpMethod.POST, "/it-asset-registry/v1/initiate", TENANT, "alice"), 201);

        assertEquals(1.0, meters.counter("gateway.audit.dropped").count());
        verify(client, never()).exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class));
    }

    // ------------------------------------------------------------ data minimisation (PCI / LGPD / HIPAA discipline)

    private static byte[] credentials(String organisation, String email, String password) {
        return ("{\"organisationId\":\"" + organisation + "\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a sign-in is recorded with the organisation, a keyed pseudonym and the status - and NEITHER the password NOR the email reaches the ledger")
    void signInNeverLeaksCredentialsOrEmail() {
        properties.setEnabled(true);
        properties.setPseudonymKey("k3y");
        when(client.exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class))).thenReturn(Mono.just(HttpResponse.created("{}")));

        recorder.recordSignIn(credentials(TENANT, "Alice@Example.com", "s3cr3t-P@ss"), 200);

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).exchange(sent.capture(), any(Argument.class), any(Argument.class));
        String everything = sent.getValue().getBody().orElseThrow() + " " + sent.getValue().getHeaders().asMap() + " " + sent.getValue().getUri();
        assertFalse(everything.contains("s3cr3t-P@ss"), everything);
        assertFalse(everything.toLowerCase().contains("alice"), everything);
        assertFalse(everything.contains("example.com"), everything);
        Map<String, Object> body = (Map<String, Object>) sent.getValue().getBody().orElseThrow();
        assertEquals(TENANT, sent.getValue().getHeaders().get("X-Tenant-Id"));
        assertEquals("POST /party-authentication/v1/session/initiate", body.get("action"));
        assertEquals("status=200", body.get("detail"));
        assertEquals("party-authentication", body.get("resourceType"));
        assertTrue(((String) body.get("actor")).matches("login:[0-9a-f]{32}"), String.valueOf(body.get("actor")));
    }

    @Test
    @DisplayName("the pseudonym is stable per person (case and spaces ignored), differs between people and between keys, and an unkeyed gateway records login:unkeyed - never a plain hash")
    void pseudonymsAreKeyedAndStable() {
        String alice = AuditPseudonymizer.of("k3y", "alice@example.com");

        assertEquals(alice, AuditPseudonymizer.of("k3y", "  ALICE@example.com "));
        assertNotEquals(alice, AuditPseudonymizer.of("k3y", "bob@example.com"));
        assertNotEquals(alice, AuditPseudonymizer.of("other-key", "alice@example.com"));
        assertEquals("unkeyed", AuditPseudonymizer.of("", "alice@example.com"));
        assertEquals("unkeyed", AuditPseudonymizer.of(null, "alice@example.com"));
        assertEquals(32, alice.length());
        assertThrows(IllegalStateException.class, () -> AuditPseudonymizer.hmac("NOT-AN-ALGORITHM", "k", "v"));
    }

    @Test
    @DisplayName("sign-in description: an unkeyed gateway, a missing or non-text email, and an unusable organisation id")
    void signInDescriptionEdges() {
        assertEquals("login:unkeyed", AuditEntryDescriber.describeSignIn(TENANT, "a@b.co", 401, "").orElseThrow().actor());
        assertEquals("login:unknown", AuditEntryDescriber.describeSignIn(TENANT, null, 401, "k").orElseThrow().actor());
        assertEquals("login:unknown", AuditEntryDescriber.describeSignIn(TENANT, "  ", 401, "k").orElseThrow().actor());
        assertEquals("login:unknown", AuditEntryDescriber.describeSignIn(TENANT, 42, 401, "k").orElseThrow().actor());
        assertTrue(AuditEntryDescriber.describeSignIn(null, "a@b.co", 401, "k").isEmpty());
        assertTrue(AuditEntryDescriber.describeSignIn(42, "a@b.co", 401, "k").isEmpty());
        assertTrue(AuditEntryDescriber.describeSignIn("not-a-uuid", "a@b.co", 401, "k").isEmpty());
        assertEquals(TENANT, AuditEntryDescriber.describeSignIn("  " + TENANT + " ", "a@b.co", 200, "k").orElseThrow().tenantId());
    }

    @Test
    @DisplayName("sign-in recording does nothing when auditing is off, with no body, or with a body that is not JSON (the parse error is never kept)")
    void signInNotRecorded() {
        recorder.recordSignIn(credentials(TENANT, "a@b.co", "pw"), 200);
        properties.setEnabled(true);
        recorder.recordSignIn(null, 200);
        recorder.recordSignIn("password=hunter2 not json".getBytes(java.nio.charset.StandardCharsets.UTF_8), 400);
        recorder.recordSignIn("[1,2]".getBytes(java.nio.charset.StandardCharsets.UTF_8), 400);
        recorder.recordSignIn(("{\"organisationId\":\"nope\",\"email\":\"a@b.co\",\"password\":\"hunter2\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8), 400);

        verify(client, never()).exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class));
        assertEquals(0.0, meters.counter("gateway.audit.dropped").count());
    }

    @Test
    @DisplayName("a mutation never puts the query string, headers other than tenant/executor, or any body on the ledger")
    void mutationRecordsOnlyAllowListedFields() {
        properties.setEnabled(true);
        when(client.exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class))).thenReturn(Mono.just(HttpResponse.created("{}")));
        HttpRequest<?> request = HttpRequest.create(HttpMethod.POST, "/it-asset-registry/v1/initiate?token=abc123&email=a@b.co")
                .header("X-Tenant-Id", TENANT).header("X-Executor", "alice").header("Authorization", "Bearer eyJsecret").body("{\"serialNumber\":\"PII-123\"}");

        recorder.record(request, 201);

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).exchange(sent.capture(), any(Argument.class), any(Argument.class));
        String everything = sent.getValue().getBody().orElseThrow() + " " + sent.getValue().getHeaders().asMap();
        assertFalse(everything.contains("abc123") || everything.contains("a@b.co") || everything.contains("eyJsecret") || everything.contains("PII-123"), everything);
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
