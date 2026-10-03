package com.thinklab.gateway;

import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a request is audit-worthy and, if so, turns it into the ledger entry that describes it: who (the
 * {@code X-Executor}), what (method and path with every identifier masked as {@code {id}}), which resource (the
 * Service Domain and the first identifier in the path) and how it ended (the status).
 *
 * <p>Only mutations are audit-worthy ({@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE}), only when the request
 * names a tenant (the ledger is per tenant), and never the ledger's own domain.
 */
final class AuditEntryDescriber {

    static final String SOURCE = "platform-gateway";
    static final String LEDGER_DOMAIN = "compliance-audit-ledger";
    /** The gateway's own endpoints (session bridge, investigation): not proxied, and the investigation records itself with more detail. */
    static final String GATEWAY_DOMAIN = "gateway";
    static final String SIGN_IN_PATH = "/party-authentication/v1/session/initiate";
    private static final Set<HttpMethod> MUTATIONS = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int MAX_FIELD = 200;
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private AuditEntryDescriber() {
    }

    /** What to append to the ledger, and under which tenant. */
    record Described(String tenantId, String actor, String action, String resourceType, String resourceId, String detail) {}

    static Optional<Described> describe(HttpRequest<?> request, int status, String pseudonymKey) {
        if (!MUTATIONS.contains(request.getMethod())) {
            return Optional.empty();
        }
        String tenant = request.getHeaders().get("X-Tenant-Id");
        if (tenant == null || !UUID.matcher(tenant.trim()).matches()) {
            return Optional.empty();
        }
        String path = request.getPath();
        String trimmed = path.replaceFirst("^/", "");
        int slash = trimmed.indexOf('/');
        String domain = slash < 0 ? trimmed : trimmed.substring(0, slash);
        if (domain.isBlank() || domain.equals(LEDGER_DOMAIN) || domain.equals(GATEWAY_DOMAIN)) {
            return Optional.empty();
        }
        var matcher = UUID.matcher(path);
        String resourceId = matcher.find() ? matcher.group() : null;
        String executor = request.getHeaders().get("X-Executor");
        String actor = executor == null || executor.isBlank() ? "unknown" : executor.trim();
        // An email is personal data: even when a caller sends one as its identity, only a keyed pseudonym is recorded.
        if (EMAIL.matcher(actor).find()) {
            actor = "user:" + AuditPseudonymizer.of(pseudonymKey, actor);
        }
        String action = request.getMethod().name() + " " + UUID.matcher(path).replaceAll("{id}");
        return Optional.of(new Described(tenant.trim(), truncate(actor), truncate(action), truncate(domain), resourceId, "status=" + status));
    }

    /**
     * Describes a sign-in attempt. Sign-in carries the tenant only in its body, so it is read here - but of that body ONLY the
     * organisation id is kept (a UUID is not personal data) and the email survives solely as a keyed pseudonym; the password and
     * the rest of the body are never read into the entry, logged or forwarded to the ledger.
     */
    static Optional<Described> describeSignIn(Object organisationId, Object email, int status, String pseudonymKey) {
        if (!(organisationId instanceof String org) || !UUID.matcher(org.trim()).matches()) {
            return Optional.empty();
        }
        String actor = email instanceof String address && !address.isBlank() ? "login:" + AuditPseudonymizer.of(pseudonymKey, address) : "login:unknown";
        return Optional.of(new Described(org.trim(), actor, "POST " + SIGN_IN_PATH, "party-authentication", null, "status=" + status));
    }

    private static String truncate(String value) {
        return value.length() <= MAX_FIELD ? value : value.substring(0, MAX_FIELD);
    }
}
