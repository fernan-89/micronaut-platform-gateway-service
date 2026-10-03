package com.thinklab.gateway;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Binds {@code gateway.investigation.*}: the pseudonym lookup an administrator can run to find what a known person did (ADR-027).
 * Off by default, so a stack that never needs it does not even have the endpoint (it answers 404).
 */
@ConfigurationProperties("gateway.investigation")
public class InvestigationProperties {

    private boolean enabled = false;
    private int maxPerHour = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** How many lookups one administrator may run per hour (a token bucket that refills evenly over the hour). */
    public int getMaxPerHour() {
        return maxPerHour;
    }

    public void setMaxPerHour(int maxPerHour) {
        this.maxPerHour = maxPerHour;
    }
}
