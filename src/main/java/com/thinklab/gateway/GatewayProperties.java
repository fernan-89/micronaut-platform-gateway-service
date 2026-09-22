package com.thinklab.gateway;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Binds {@code gateway.*}: the route table (BIAN domain to upstream base URL), body limit and denied paths. */
@ConfigurationProperties("gateway")
public class GatewayProperties {

    private Map<String, String> routes = new LinkedHashMap<>();
    private List<String> deniedPaths = List.of();
    private int maxBodyBytes = 1_048_576;

    public Map<String, String> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, String> routes) {
        this.routes = routes;
    }

    public List<String> getDeniedPaths() {
        return deniedPaths;
    }

    public void setDeniedPaths(List<String> deniedPaths) {
        this.deniedPaths = deniedPaths;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }
}
