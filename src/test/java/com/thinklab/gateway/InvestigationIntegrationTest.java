package com.thinklab.gateway;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The investigation endpoint over real HTTP (ADR-027): binding, validation, error mapping, and the ledger call (a fake ledger). */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvestigationIntegrationTest implements TestPropertyProvider {

    private static final String TENANT = "11111111-2222-3333-4444-555555555555";
    private FakeUpstream ledger;

    @Override
    public Map<String, String> getProperties() {
        try {
            ledger = new FakeUpstream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return Map.of(
                "gateway.investigation.enabled", "true",
                "gateway.investigation.max-per-hour", "2",
                "gateway.audit.pseudonym-key", "integration-test-key",
                "micronaut.http.services.gateway-audit.url", ledger.baseUrl(),
                "gateway.rate-limit.burst", "1000");
    }

    @AfterAll
    void stop() {
        ledger.close();
    }

    @Inject
    @Client("/")
    HttpClient client;

    private static HttpRequest<?> lookup(String body) {
        return HttpRequest.POST("/gateway/v1/investigation/pseudonym", body)
                .header("X-Tenant-Id", TENANT).header("X-Executor", "99999999-aaaa-bbbb-cccc-dddddddddddd").contentType("application/json");
    }

    private HttpClientResponseException failure(HttpRequest<?> request) {
        return assertThrows(HttpClientResponseException.class, () -> client.toBlocking().exchange(request));
    }

    private static String errorCode(HttpClientResponseException e) {
        return e.getResponse().getBody(Map.class).map(m -> String.valueOf(m.get("error_code"))).orElse("");
    }

    @Test
    @DisplayName("a valid request gets the pseudonym and the ledger query; the email is not in the answer; the third lookup in the hour is 429")
    void lookupAndLimit() {
        Map<?, ?> answer = client.toBlocking().retrieve(lookup("{\"email\":\"Alice@Example.com\",\"reason\":\"Ticket SEC-1234: night access\"}"), Map.class);

        String expected = AuditPseudonymizer.of("integration-test-key", "alice@example.com");
        assertEquals(expected, answer.get("pseudonym"));
        assertEquals("/compliance-audit-ledger/v1/retrieve?actor=login:" + expected, answer.get("ledgerQuery"));
        assertEquals(false, answer.toString().toLowerCase().contains("alice"));

        client.toBlocking().exchange(lookup("{\"email\":\"bob@example.com\",\"reason\":\"Ticket SEC-1234: night access\"}"));
        var limited = failure(lookup("{\"email\":\"carol@example.com\",\"reason\":\"Ticket SEC-1234: night access\"}"));
        assertEquals(429, limited.getStatus().getCode());
        assertEquals("ERR-GTW-00429", errorCode(limited));
    }

    @Test
    @DisplayName("a missing or too-short reason, or a blank email, is a 400 before anything happens")
    void validation() {
        assertEquals(400, failure(lookup("{\"email\":\"alice@example.com\",\"reason\":\"short\"}")).getStatus().getCode());
        assertEquals(400, failure(lookup("{\"email\":\"alice@example.com\"}")).getStatus().getCode());
        assertEquals(400, failure(lookup("{\"email\":\"\",\"reason\":\"Ticket SEC-1234: night access\"}")).getStatus().getCode());
    }

    @Test
    @DisplayName("a reason that carries personal data is refused with 400")
    void reasonWithPersonalData() {
        assertEquals(400, failure(lookup("{\"email\":\"alice@example.com\",\"reason\":\"Because bob@example.com did it\"}")).getStatus().getCode());
    }
}
