package com.thinklab.gateway;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The gateway's own session endpoints (ADR-025): they live under {@code /gateway/v1/session}, outside every BIAN domain, because they
 * are not proxied - they authenticate with the refresh cookie the gateway itself set. See {@link SessionBridge}.
 */
@Controller("/gateway/v1/session")
public class SessionCookieController {

    private final SessionBridge bridge;

    public SessionCookieController(SessionBridge bridge) {
        this.bridge = bridge;
    }

    /** Exchanges the refresh cookie for a new access token (and rotates the cookie). */
    @Post(value = "/refresh", consumes = MediaType.ALL)
    public Mono<MutableHttpResponse<Map<String, Object>>> refresh(HttpRequest<?> request) {
        return bridge.refresh(request);
    }

    /** Revokes the session and clears the cookie. */
    @Post(value = "/logout", consumes = MediaType.ALL)
    public Mono<MutableHttpResponse<Void>> logout(HttpRequest<?> request) {
        return bridge.logout(request);
    }
}
