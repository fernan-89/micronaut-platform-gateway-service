package com.thinklab.gateway;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Binds {@code gateway.audit.*}: whether the gateway records every mutating request on the compliance ledger
 * (ADR-032 of that service). Off by default, so a stack without a ledger behaves exactly as before.
 */
@ConfigurationProperties("gateway.audit")
public class AuditProperties {

    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
