package com.thinklab.gateway;

import com.thinklab.domain.exception.InvestigationNotPermittedException;
import com.thinklab.domain.exception.InvestigationNotRecordedException;
import com.thinklab.domain.exception.InvestigationRateExceededException;
import com.thinklab.domain.exception.PseudonymKeyMissingException;
import com.thinklab.domain.exception.RouteNotFoundException;
import com.thinklab.gateway.AuditEntryDescriber.Described;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The investigation procedure (ADR-027): to find what a KNOWN person did, an administrator gives the gateway their email and a
 * reason; the gateway answers the keyed pseudonym that person's sign-ins were recorded under, and the administrator then reads the
 * ledger by that actor. It never goes the other way - there is no table from pseudonym to person - so it can only help to
 * investigate someone you already have a name for.
 *
 * <p>Guard rails: ADMIN only (when the platform runs with security on), a reason that carries no personal data, a per-person hourly
 * limit, and above all <b>the lookup is recorded on the compliance ledger BEFORE the pseudonym is handed over</b> (target pseudonym
 * and reason, never the email) - if the ledger will not take the record, nothing is disclosed.
 */
@Singleton
public class PseudonymInvestigation {

    private static final Logger log = LoggerFactory.getLogger(PseudonymInvestigation.class);
    static final String PATH = "/gateway/v1/investigation/pseudonym";
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{9,}");
    private static final Pattern TENANT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final InvestigationProperties properties;
    private final AuditProperties auditProperties;
    private final AuditRecorder recorder;
    private final boolean securityOn;
    private final RateLimiter limiter;

    @Inject
    public PseudonymInvestigation(InvestigationProperties properties, AuditProperties auditProperties, AuditRecorder recorder,
                                  @Value("${thinklab.security.enabled:false}") boolean securityOn) {
        this(properties, auditProperties, recorder, securityOn, Clock.systemUTC());
    }

    PseudonymInvestigation(InvestigationProperties properties, AuditProperties auditProperties, AuditRecorder recorder, boolean securityOn, Clock clock) {
        this.properties = properties;
        this.auditProperties = auditProperties;
        this.recorder = recorder;
        this.securityOn = securityOn;
        var bucket = new RateLimitProperties();
        bucket.setBurst(properties.getMaxPerHour());
        bucket.setRequestsPerSecond(properties.getMaxPerHour() / 3600.0);
        this.limiter = new RateLimiter(bucket, clock);
    }

    public Mono<Map<String, Object>> lookup(String tenantId, String executor, String role, String email, String reason) {
        if (!properties.isEnabled()) {
            // The gateway never reveals what exists behind it: a switched-off feature is simply not there.
            throw new RouteNotFoundException("No route is configured for " + PATH);
        }
        if (securityOn && !"ADMIN".equals(role)) {
            throw new InvestigationNotPermittedException();
        }
        if (!TENANT.matcher(tenantId.trim()).matches()) {
            throw new IllegalArgumentException("X-Tenant-Id must be the organisation id.");
        }
        if (EMAIL.matcher(reason).find() || LONG_NUMBER.matcher(reason).find()) {
            throw new IllegalArgumentException("The reason must not contain personal data (an email or a long number): refer to a ticket id instead.");
        }
        String pseudonym = AuditPseudonymizer.of(auditProperties.getPseudonymKey(), email);
        if (AuditPseudonymizer.UNKEYED.equals(pseudonym)) {
            throw new PseudonymKeyMissingException();
        }
        long wait = limiter.tryAcquire("investigator:" + executor);
        if (wait > 0) {
            throw new InvestigationRateExceededException(wait);
        }

        String actor = EMAIL.matcher(executor).find() ? "user:" + AuditPseudonymizer.of(auditProperties.getPseudonymKey(), executor) : executor.trim();
        Described entry = new Described(tenantId.trim(), actor, "POST " + PATH, "investigation", null,
                "target=login:" + pseudonym + "; reason=" + reason.trim());
        return recorder.appendRequired(entry)
                .onErrorMap(failure -> {
                    log.warn("[INVESTIGATION] A lookup was refused because it could not be recorded (organisation {}). Reason: {}", tenantId, failure.getMessage());
                    return new InvestigationNotRecordedException(failure);
                })
                .then(Mono.fromSupplier(() -> answer(pseudonym)));
    }

    private static Map<String, Object> answer(String pseudonym) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pseudonym", pseudonym);
        body.put("actor", "login:" + pseudonym);
        body.put("ledgerQuery", "/compliance-audit-ledger/v1/retrieve?actor=login:" + pseudonym);
        body.put("recorded", true);
        return body;
    }
}
