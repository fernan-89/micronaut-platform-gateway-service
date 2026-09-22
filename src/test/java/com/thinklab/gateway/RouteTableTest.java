package com.thinklab.gateway;

import com.thinklab.domain.exception.RouteNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouteTableTest {

    private RouteTable table(Map<String, String> routes, List<String> denied) {
        GatewayProperties properties = new GatewayProperties();
        properties.setRoutes(routes);
        properties.setDeniedPaths(denied);
        return new RouteTable(properties);
    }

    @Test
    @DisplayName("the first path segment selects the upstream, with or without a leading slash")
    void resolvesByFirstSegment() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("it-asset-registry", "http://up:80");
        RouteTable table = table(routes, List.of());

        assertEquals("http://up:80", table.upstreamFor("/it-asset-registry/v1/retrieve"));
        assertEquals("http://up:80", table.upstreamFor("it-asset-registry"));
    }

    @Test
    @DisplayName("a trailing slash on the configured upstream is stripped")
    void stripsTrailingSlash() {
        RouteTable table = table(Map.of("it-asset-registry", "http://up:80/"), List.of());

        assertEquals("http://up:80", table.upstreamFor("/it-asset-registry/v1/x"));
    }

    @Test
    @DisplayName("an unconfigured domain, a blank upstream and a denied path are all a RouteNotFoundException")
    void notFoundCases() {
        RouteTable table = table(Map.of("it-asset-registry", "", "party-authentication", "http://up:81"),
                List.of("/party-authentication/v1/token", "/party-authentication/v1/session/revoked"));

        assertThrows(RouteNotFoundException.class, () -> table.upstreamFor("/no-such-domain/v1/x"));
        assertThrows(RouteNotFoundException.class, () -> table.upstreamFor("/it-asset-registry/v1/x"));
        assertThrows(RouteNotFoundException.class, () -> table.upstreamFor("/party-authentication/v1/token"));
        assertThrows(RouteNotFoundException.class, () -> table.upstreamFor("/party-authentication/v1/token/service"));
        assertThrows(RouteNotFoundException.class, () -> table.upstreamFor("/party-authentication/v1/session/revoked"));
    }

    @Test
    @DisplayName("a path that is only the domain segment (no further slash) still resolves")
    void domainOnlyPath() {
        RouteTable table = table(Map.of("it-asset-registry", "http://up:80"), List.of());

        assertEquals("http://up:80", table.upstreamFor("/it-asset-registry"));
    }
}
