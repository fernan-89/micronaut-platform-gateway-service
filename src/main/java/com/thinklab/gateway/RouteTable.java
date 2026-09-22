package com.thinklab.gateway;

import com.thinklab.domain.exception.RouteNotFoundException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Maps a request path to its upstream. The first path segment is the BIAN Service Domain
 * ({@code /it-asset-registry/v1/...} goes to the {@code it-asset-registry} upstream). Unknown domains and denied
 * paths are indistinguishable: both are a 404, so the gateway never reveals what exists behind it.
 */
@Singleton
public class RouteTable {

    private final GatewayProperties properties;

    @Inject
    public RouteTable(GatewayProperties properties) {
        this.properties = properties;
    }

    /** The upstream base URL (no trailing slash) for the path, or a {@link RouteNotFoundException}. */
    public String upstreamFor(String path) {
        boolean denied = properties.getDeniedPaths().stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        String domain = slash < 0 ? trimmed : trimmed.substring(0, slash);
        String upstream = properties.getRoutes().get(domain);
        if (denied || upstream == null || upstream.isBlank()) {
            throw new RouteNotFoundException("No route is configured for " + path);
        }
        return upstream.endsWith("/") ? upstream.substring(0, upstream.length() - 1) : upstream;
    }
}
