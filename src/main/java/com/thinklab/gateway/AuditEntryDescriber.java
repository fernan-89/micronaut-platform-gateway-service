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
    private static final Set<HttpMethod> MUTATIONS = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int MAX_FIELD = 200;

    private AuditEntryDescriber() {
    }

    /** What to append to the ledger, and under which tenant. */
    record Described(String tenantId, String actor, String action, String resourceType, String resourceId, String detail) {}

    static Optional<Described> describe(HttpRequest<?> request, int status) {
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
        if (domain.isBlank() || domain.equals(LEDGER_DOMAIN)) {
            return Optional.empty();
        }
        var matcher = UUID.matcher(path);
        String resourceId = matcher.find() ? matcher.group() : null;
        String executor = request.getHeaders().get("X-Executor");
        String actor = executor == null || executor.isBlank() ? "unknown" : executor.trim();
        String action = request.getMethod().name() + " " + UUID.matcher(path).replaceAll("{id}");
        return Optional.of(new Described(tenant.trim(), truncate(actor), truncate(action), truncate(domain), resourceId, "status=" + status));
    }

    private static String truncate(String value) {
        return value.length() <= MAX_FIELD ? value : value.substring(0, MAX_FIELD);
    }
}
