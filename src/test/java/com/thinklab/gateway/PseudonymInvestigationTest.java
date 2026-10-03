package com.thinklab.gateway;

import com.thinklab.domain.exception.InvestigationNotPermittedException;
import com.thinklab.domain.exception.InvestigationNotRecordedException;
import com.thinklab.domain.exception.InvestigationRateExceededException;
import com.thinklab.domain.exception.PseudonymKeyMissingException;
import com.thinklab.domain.exception.RouteNotFoundException;
import com.thinklab.gateway.AuditEntryDescriber.Described;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The pseudonym lookup (ADR-027): who may run it, what it refuses, and that it is recorded before anything is disclosed. */
class PseudonymInvestigationTest {

    private static final String TENANT = "11111111-2222-3333-4444-555555555555";
    private static final String EXECUTOR = "99999999-aaaa-bbbb-cccc-dddddddddddd";
    private static final String REASON = "Ticket SEC-1234: unusual night access";

    private final InvestigationProperties properties = new InvestigationProperties();
    private final AuditProperties audit = new AuditProperties();
    private final AuditRecorder recorder = mock(AuditRecorder.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        audit.setPseudonymKey("investigation-test-key");
        when(recorder.appendRequired(any())).thenReturn(Mono.empty());
    }

    private PseudonymInvestigation investigation(boolean securityOn) {
        return new PseudonymInvestigation(properties, audit, recorder, securityOn, clock);
    }

    @Test
    @DisplayName("the lookup answers the pseudonym a person's sign-ins are recorded under, and how to read the ledger by it - never the email")
    void answersThePseudonym() {
        String expected = AuditPseudonymizer.of("investigation-test-key", "Alice@Example.com");

        StepVerifier.create(investigation(false).lookup(TENANT, EXECUTOR, null, "Alice@Example.com", REASON))
                .assertNext(answer -> {
                    assertEquals(expected, answer.get("pseudonym"));
                    assertEquals("login:" + expected, answer.get("actor"));
                    assertEquals("/compliance-audit-ledger/v1/retrieve?actor=login:" + expected, answer.get("ledgerQuery"));
                    assertEquals(true, answer.get("recorded"));
                    assertFalse(answer.toString().toLowerCase().contains("alice"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("the lookup is recorded on the ledger with the target pseudonym and the reason - and never the email")
    void recordsTheLookup() {
        investigation(false).lookup(TENANT, EXECUTOR, null, "alice@example.com", REASON).block();

        ArgumentCaptor<Described> entry = ArgumentCaptor.forClass(Described.class);
        verify(recorder).appendRequired(entry.capture());
        String pseudonym = AuditPseudonymizer.of("investigation-test-key", "alice@example.com");
        assertEquals(TENANT, entry.getValue().tenantId());
        assertEquals(EXECUTOR, entry.getValue().actor());
        assertEquals("POST /gateway/v1/investigation/pseudonym", entry.getValue().action());
        assertEquals("investigation", entry.getValue().resourceType());
        assertNull(entry.getValue().resourceId());
        assertEquals("target=login:" + pseudonym + "; reason=" + REASON, entry.getValue().detail());
        assertFalse(entry.getValue().toString().contains("alice"));
    }

    @Test
    @DisplayName("an investigator whose own identity is an email is recorded as a pseudonym too")
    void emailExecutorIsPseudonymised() {
        investigation(false).lookup(TENANT, "admin@example.com", null, "alice@example.com", REASON).block();

        ArgumentCaptor<Described> entry = ArgumentCaptor.forClass(Described.class);
        verify(recorder).appendRequired(entry.capture());
        assertEquals("user:" + AuditPseudonymizer.of("investigation-test-key", "admin@example.com"), entry.getValue().actor());
    }

    @Test
    @DisplayName("if the ledger will not take the record, nothing is disclosed")
    void notRecordedMeansNotDisclosed() {
        when(recorder.appendRequired(any())).thenReturn(Mono.error(new IllegalStateException("ledger down")));

        StepVerifier.create(investigation(false).lookup(TENANT, EXECUTOR, null, "alice@example.com", REASON))
                .expectError(InvestigationNotRecordedException.class)
                .verify();
    }

    @Test
    @DisplayName("switched off, the endpoint is simply not there (404), and nothing is recorded")
    void disabledIsNotFound() {
        properties.setEnabled(false);

        assertThrows(RouteNotFoundException.class, () -> investigation(false).lookup(TENANT, EXECUTOR, null, "alice@example.com", REASON));
        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("with security on only an ADMIN may look someone up; with security off there is no role to check")
    void onlyAdminWhenSecured() {
        var secured = investigation(true);

        for (String role : new String[]{null, "OPERATOR", "VIEWER", "SERVICE", "REQUESTER"}) {
            assertThrows(InvestigationNotPermittedException.class, () -> secured.lookup(TENANT, EXECUTOR, role, "alice@example.com", REASON));
        }
        verify(recorder, never()).appendRequired(any());

        StepVerifier.create(secured.lookup(TENANT, EXECUTOR, "ADMIN", "alice@example.com", REASON)).expectNextCount(1).verifyComplete();
        StepVerifier.create(investigation(false).lookup(TENANT, EXECUTOR, null, "alice@example.com", REASON)).expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("the organisation must be a real organisation id, and the reason must carry no personal data")
    void refusesBadInput() {
        var open = investigation(false);

        assertThrows(IllegalArgumentException.class, () -> open.lookup("not-a-tenant", EXECUTOR, null, "alice@example.com", REASON));
        var email = assertThrows(IllegalArgumentException.class, () -> open.lookup(TENANT, EXECUTOR, null, "alice@example.com", "Because bob@example.com did it"));
        assertTrue(email.getMessage().contains("personal data"));
        assertThrows(IllegalArgumentException.class, () -> open.lookup(TENANT, EXECUTOR, null, "alice@example.com", "Card 4111111111111111 was used"));
        verify(recorder, never()).appendRequired(any());
    }

    @Test
    @DisplayName("without a pseudonymisation key there is no pseudonym to give, and nothing is recorded")
    void needsAKey() {
        audit.setPseudonymKey("");

        assertThrows(PseudonymKeyMissingException.class, () -> investigation(false).lookup(TENANT, EXECUTOR, null, "alice@example.com", REASON));
        verify(recorder, never()).appendRequired(any());
    }

    @Test
    @DisplayName("one person can run only so many lookups an hour; another person has their own allowance")
    void hourlyLimit() {
        properties.setMaxPerHour(2);
        var limited = investigation(false);

        limited.lookup(TENANT, EXECUTOR, null, "a@example.com", REASON).block();
        limited.lookup(TENANT, EXECUTOR, null, "b@example.com", REASON).block();
        var third = assertThrows(InvestigationRateExceededException.class, () -> limited.lookup(TENANT, EXECUTOR, null, "c@example.com", REASON));

        assertTrue(third.getMessage().contains("second(s)"));
        StepVerifier.create(limited.lookup(TENANT, "11111111-0000-0000-0000-000000000000", null, "c@example.com", REASON)).expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("the defaults: off, twenty an hour")
    void defaults() {
        var fresh = new InvestigationProperties();

        assertFalse(fresh.isEnabled());
        assertEquals(20, fresh.getMaxPerHour());
    }
}
