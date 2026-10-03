package com.thinklab.gateway;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Plan-based feature gating (ADR-026): what is asked of billing, what is cached, when the gateway fails open and when it refuses. */
@SuppressWarnings({"unchecked", "rawtypes"})
class EntitlementTest {

    private static final String TENANT = "11111111-2222-3333-4444-555555555555";

    private final HttpClient client = mock(HttpClient.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final EntitlementProperties properties = new EntitlementProperties();
    private final AtomicLong nanos = new AtomicLong(1_000);
    private EntitlementGate gate;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.getFeatures().put("compliance-audit-ledger", "audit");
        properties.setCacheTtl(Duration.ofSeconds(30));
        gate = new EntitlementGate(client, properties, meters, nanos::get);
    }

    private void billingAnswers(Object body) {
        when(client.exchange(any(HttpRequest.class), eq(Map.class))).thenReturn(Mono.just(body == null ? HttpResponse.ok() : HttpResponse.ok(body)));
    }

    // ------------------------------------------------------------ which paths are gated

    @Test
    @DisplayName("a Service Domain is gated by the first segment of the path, with or without a leading slash")
    void featureIsTheFirstSegment() {
        assertEquals("audit", gate.featureFor("/compliance-audit-ledger/v1/retrieve").orElseThrow());
        assertEquals("audit", gate.featureFor("compliance-audit-ledger/v1/retrieve").orElseThrow());
        assertEquals("audit", gate.featureFor("/compliance-audit-ledger").orElseThrow());
        assertTrue(gate.featureFor("/it-asset-registry/v1/retrieve").isEmpty());
    }

    @Test
    @DisplayName("the defaults gate nothing and are switched off, with a 30 second cache")
    void defaults() {
        var fresh = new EntitlementProperties();
        assertEquals(false, fresh.isEnabled());
        assertTrue(fresh.getFeatures().isEmpty());
        assertEquals(Duration.ofSeconds(30), fresh.getCacheTtl());
        fresh.setFeatures(Map.of("a", "b"));
        assertEquals("b", fresh.getFeatures().get("a"));
        assertEquals(false, new EntitlementGate(client, fresh, meters).enabled());
        assertEquals(true, gate.enabled());
    }

    // ------------------------------------------------------------ the question to billing

    @Test
    @DisplayName("billing is asked for the feature on behalf of the organisation, and its yes and no are passed on")
    void asksBilling() {
        billingAnswers(Map.of("allowed", true, "limit", 5, "source", "SUBSCRIPTION", "planCode", "TEAM"));
        StepVerifier.create(gate.allowed(TENANT, "audit")).expectNext(true).verifyComplete();

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).exchange(sent.capture(), eq(Map.class));
        assertEquals(HttpMethod.GET, sent.getValue().getMethod());
        assertEquals("/subscription-billing/v1/entitlement/evaluate?feature=audit", sent.getValue().getUri().toString());
        assertEquals(TENANT, sent.getValue().getHeaders().get("X-Tenant-Id"));

        billingAnswers(Map.of("allowed", false, "source", "PLAN"));
        StepVerifier.create(gate.allowed("22222222-2222-3333-4444-555555555555", "audit")).expectNext(false).verifyComplete();
    }

    @Test
    @DisplayName("an answer without a verdict (or without a body) is not a no")
    void unreadableAnswerIsAllowed() {
        billingAnswers(Map.of("planCode", "TEAM"));
        StepVerifier.create(gate.allowed(TENANT, "audit")).expectNext(true).verifyComplete();

        billingAnswers(null);
        StepVerifier.create(gate.allowed("22222222-2222-3333-4444-555555555555", "audit")).expectNext(true).verifyComplete();
    }

    // ------------------------------------------------------------ cache

    @Test
    @DisplayName("an answer is reused until its time is up, then billing is asked again")
    void answersAreCached() {
        billingAnswers(Map.of("allowed", false));
        StepVerifier.create(gate.allowed(TENANT, "audit")).expectNext(false).verifyComplete();
        nanos.addAndGet(Duration.ofSeconds(29).toNanos());
        StepVerifier.create(gate.allowed(TENANT, "audit")).expectNext(false).verifyComplete();
        verify(client, times(1)).exchange(any(HttpRequest.class), eq(Map.class));

        billingAnswers(Map.of("allowed", true));
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        StepVerifier.create(gate.allowed(TENANT, "audit")).expectNext(true).verifyComplete();
        verify(client, times(2)).exchange(any(HttpRequest.class), eq(Map.class));
    }

    @Test
    @DisplayName("the cache is bounded: when it is full it starts over instead of growing")
    void cacheIsBounded() {
        billingAnswers(Map.of("allowed", true));
        for (int i = 0; i <= EntitlementGate.MAX_CACHED; i++) {
            gate.allowed("tenant-" + i, "audit").block();
        }
        gate.allowed("tenant-0", "audit").block();
        // tenant-0 was dropped when the cache restarted, so it needed a fresh question
        verify(client, times(EntitlementGate.MAX_CACHED + 2)).exchange(any(HttpRequest.class), eq(Map.class));
    }

    // ------------------------------------------------------------ fail-open

    @Test
    @DisplayName("billing down: the request goes through, the failure is counted, and it is not cached")
    void failsOpen() {
        when(client.exchange(any(HttpRequest.class), eq(Map.class))).thenReturn(Mono.error(new IllegalStateException("billing down")));
        StepVerifier.create(gate.allowed(TENANT, "audit")).expectNext(true).verifyComplete();
        StepVerifier.create(gate.allowed(TENANT, "audit")).expectNext(true).verifyComplete();

        assertEquals(2.0, meters.counter("gateway.entitlement.unavailable").count());
        verify(client, times(2)).exchange(any(HttpRequest.class), eq(Map.class));
    }

    @Test
    @DisplayName("no answer at all is treated like an unreadable one")
    void emptyAnswerIsAllowed() {
        when(client.exchange(any(HttpRequest.class), eq(Map.class))).thenReturn(Mono.empty());
        StepVerifier.create(gate.allowed(TENANT, "audit")).expectNext(true).verifyComplete();
    }

    // ------------------------------------------------------------ the filter

    private static HttpRequest<?> request(String path, String tenant) {
        var request = HttpRequest.create(HttpMethod.GET, path);
        if (tenant != null) {
            request.header("X-Tenant-Id", tenant);
        }
        return request;
    }

    private ServerFilterChain chainAnswering(MutableHttpResponse<?> response) {
        ServerFilterChain chain = mock(ServerFilterChain.class);
        when(chain.proceed(any())).thenReturn((org.reactivestreams.Publisher) Mono.just(response));
        return chain;
    }

    @Test
    @DisplayName("a plan without the feature gets a 403 problem document and the request is never forwarded")
    void refusesWhenThePlanLacksTheFeature() {
        billingAnswers(Map.of("allowed", false));
        var filter = new EntitlementFilter(gate);
        ServerFilterChain chain = chainAnswering(HttpResponse.ok());

        assertEquals(ServerFilterPhase.SECURITY.order() + 3, filter.getOrder());
        StepVerifier.create(filter.doFilter(request("/compliance-audit-ledger/v1/retrieve", TENANT), chain))
                .assertNext(response -> {
                    assertEquals(HttpStatus.FORBIDDEN, response.getStatus());
                    Map<String, Object> body = (Map<String, Object>) response.body();
                    assertEquals("ERR-GTW-00403", body.get("error_code"));
                    assertEquals("Your plan does not include 'audit'.", body.get("detail"));
                })
                .verifyComplete();
        verify(chain, never()).proceed(any());
    }

    @Test
    @DisplayName("a plan with the feature passes through")
    void passesWhenAllowed() {
        billingAnswers(Map.of("allowed", true));
        var filter = new EntitlementFilter(gate);
        MutableHttpResponse<?> ok = HttpResponse.ok();

        StepVerifier.create(filter.doFilter(request("/compliance-audit-ledger/v1/retrieve", TENANT), chainAnswering(ok))).expectNext(ok).verifyComplete();
    }

    @Test
    @DisplayName("switched off, without a tenant, or for a domain that is not gated, billing is never asked")
    void doesNotBotherBilling() {
        var filter = new EntitlementFilter(gate);
        MutableHttpResponse<?> ok = HttpResponse.ok();

        StepVerifier.create(filter.doFilter(request("/compliance-audit-ledger/v1/retrieve", null), chainAnswering(ok))).expectNext(ok).verifyComplete();
        StepVerifier.create(filter.doFilter(request("/compliance-audit-ledger/v1/retrieve", ""), chainAnswering(ok))).expectNext(ok).verifyComplete();
        StepVerifier.create(filter.doFilter(request("/it-asset-registry/v1/retrieve", TENANT), chainAnswering(ok))).expectNext(ok).verifyComplete();
        properties.setEnabled(false);
        StepVerifier.create(filter.doFilter(request("/compliance-audit-ledger/v1/retrieve", TENANT), chainAnswering(ok))).expectNext(ok).verifyComplete();

        verify(client, never()).exchange(any(HttpRequest.class), eq(Map.class));
    }
}
