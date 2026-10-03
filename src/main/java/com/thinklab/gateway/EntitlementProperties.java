package com.thinklab.gateway;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds {@code gateway.entitlements.*}: whether the gateway asks the billing service if a tenant's plan includes the feature a
 * request needs (ADR-026). Off by default, so a stack without billing behaves exactly as before.
 */
@ConfigurationProperties("gateway.entitlements")
public class EntitlementProperties {

    private boolean enabled = false;
    private Map<String, String> features = new LinkedHashMap<>();
    private Duration cacheTtl = Duration.ofSeconds(30);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Service Domain (first path segment) to the plan feature its use requires, e.g. {@code compliance-audit-ledger: audit}. */
    public Map<String, String> getFeatures() {
        return features;
    }

    public void setFeatures(Map<String, String> features) {
        this.features = features;
    }

    /** How long an answer is reused per organisation and feature; a plan change is therefore visible within this time. */
    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }
}
