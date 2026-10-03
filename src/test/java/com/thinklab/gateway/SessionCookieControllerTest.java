package com.thinklab.gateway;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionCookieControllerTest {

    private final SessionBridge bridge = mock(SessionBridge.class);
    private final SessionCookieController controller = new SessionCookieController(bridge);
    private final HttpRequest<?> request = mock(HttpRequest.class);

    @Test
    @DisplayName("refresh and logout delegate to the session bridge")
    void delegates() {
        MutableHttpResponse<Map<String, Object>> refreshed = HttpResponse.ok(Map.of("accessToken", "a"));
        MutableHttpResponse<Void> loggedOut = HttpResponse.noContent();
        when(bridge.refresh(request)).thenReturn(Mono.just(refreshed));
        when(bridge.logout(request)).thenReturn(Mono.just(loggedOut));

        StepVerifier.create(controller.refresh(request)).expectNext(refreshed).verifyComplete();
        StepVerifier.create(controller.logout(request)).expectNext(loggedOut).verifyComplete();
    }

    @Test
    @DisplayName("the cookie settings default to the web app's /api prefix, Secure on and the standard pages")
    void propertyDefaults() {
        SessionCookieProperties properties = new SessionCookieProperties();

        assertEquals("thinklab_rt", properties.getName());
        assertEquals("/api/gateway/v1/session", properties.getPath());
        assertTrue(properties.isSecure());
        assertEquals("/sso/complete", properties.getCompleteUrl());
        assertEquals("/login", properties.getLoginUrl());
    }
}
