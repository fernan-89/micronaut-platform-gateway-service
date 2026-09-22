package com.thinklab.gateway;

import io.micronaut.context.annotation.ConfigurationProperties;

/** Binds {@code gateway.rate-limit.*}: a per-caller token bucket (sustained rate and burst size). */
@ConfigurationProperties("gateway.rate-limit")
public class RateLimitProperties {

    private double requestsPerSecond = 50;
    private int burst = 100;

    public double getRequestsPerSecond() {
        return requestsPerSecond;
    }

    public void setRequestsPerSecond(double requestsPerSecond) {
        this.requestsPerSecond = requestsPerSecond;
    }

    public int getBurst() {
        return burst;
    }

    public void setBurst(int burst) {
        this.burst = burst;
    }
}
