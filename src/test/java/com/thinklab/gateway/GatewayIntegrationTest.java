package com.thinklab.gateway;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gateway against a real Netty server and a fake upstream: routing, relaying, limits and failure mapping. */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayIntegrationTest implements TestPropertyProvider {

    private FakeUpstream upstream;

    @Override
    public Map<String, String> getProperties() {
        try {
            upstream = new FakeUpstream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return Map.of(
                "gateway.routes.it-asset-registry", upstream.baseUrl(),
                "gateway.routes.dead-domain", "http://127.0.0.1:1",
                "gateway.denied-paths", "/it-asset-registry/v1/internal",
                "gateway.max-body-bytes", "64",
                "micronaut.http.services.gateway-upstream.read-timeout", "1s",
                "gateway.rate-limit.burst", "1000");
    }

    @AfterAll
    void stop() {
        upstream.close();
    }

    @Inject
    @Client("/")
    HttpClient client;

    private HttpClientResponseException failure(HttpRequest<?> request) {
        return assertThrows(HttpClientResponseException.class, () -> client.toBlocking().exchange(request));
    }

    private static String errorCode(HttpClientResponseException e) {
        return e.getResponse().getBody(Map.class).map(m -> String.valueOf(m.get("error_code"))).orElse("");
    }

    @Test
    @DisplayName("GET is routed by the first path segment, keeping path and query, and adds forwarding headers")
    void getIsRouted() {
        Map<?, ?> answer = client.toBlocking().retrieve(
                HttpRequest.GET("/it-asset-registry/v1/retrieve?status=READY&page=2").header("X-Forwarded-For", "203.0.113.5"), Map.class);

        assertEquals("GET", answer.get("method"));
        assertEquals("/it-asset-registry/v1/retrieve", answer.get("path"));
        assertEquals("status=READY&page=2", answer.get("query"));
        assertTrue(String.valueOf(answer.get("forwardedFor")).startsWith("203.0.113.5, "));
        assertEquals("http", answer.get("forwardedProto"));
    }

    @Test
    @DisplayName("POST, PUT, PATCH and DELETE are relayed with their bodies and the upstream response headers")
    void writes() {
        HttpResponse<Map> post = client.toBlocking().exchange(
                HttpRequest.POST("/it-asset-registry/v1/initiate", "{\"a\":1}").contentType("application/json"), Map.class);
        Map<?, ?> put = client.toBlocking().retrieve(
                HttpRequest.PUT("/it-asset-registry/v1/x/update", "{\"b\":2}").contentType("application/json"), Map.class);
        Map<?, ?> patch = client.toBlocking().retrieve(
                HttpRequest.PATCH("/it-asset-registry/v1/x", "{\"c\":3}").contentType("application/json"), Map.class);
        Map<?, ?> delete = client.toBlocking().retrieve(HttpRequest.DELETE("/it-asset-registry/v1/x"), Map.class);

        assertEquals("POST", post.body().get("method"));
        assertEquals("{'a':1}", post.body().get("body"));
        assertEquals("fake", post.getHeaders().get("X-Upstream"));
        assertEquals("{'b':2}", put.get("body"));
        assertEquals("{'c':3}", patch.get("body"));
        assertEquals("DELETE", delete.get("method"));
    }

    @Test
    @DisplayName("upstream 404 and 500 answers are relayed unchanged")
    void upstreamErrorsAreRelayed() {
        HttpClientResponseException notFound = failure(HttpRequest.GET("/it-asset-registry/v1/missing"));
        HttpClientResponseException boom = failure(HttpRequest.GET("/it-asset-registry/v1/boom"));

        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatus());
        assertEquals("/it-asset-registry/v1/missing", notFound.getResponse().getBody(Map.class).map(m -> m.get("path")).orElse(null));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, boom.getStatus());
    }

    @Test
    @DisplayName("unknown domains and denied paths are both a 404 RFC 7807 problem")
    void unknownAndDenied() {
        HttpClientResponseException unknown = failure(HttpRequest.GET("/no-such-domain/v1/x"));
        HttpClientResponseException denied = failure(HttpRequest.GET("/it-asset-registry/v1/internal/secret"));

        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatus());
        assertEquals("ERR-GTW-00404", errorCode(unknown));
        assertEquals("ERR-GTW-00404", errorCode(denied));
    }

    @Test
    @DisplayName("an unreachable upstream is 502 and a slow one is 504")
    void upstreamFailures() {
        HttpClientResponseException down = failure(HttpRequest.GET("/dead-domain/v1/x"));
        HttpClientResponseException slow = failure(HttpRequest.GET("/it-asset-registry/v1/slow"));

        assertEquals(HttpStatus.BAD_GATEWAY, down.getStatus());
        assertEquals("ERR-GTW-00502", errorCode(down));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, slow.getStatus());
        assertEquals("ERR-GTW-00504", errorCode(slow));
    }

    @Test
    @DisplayName("a body over the limit is refused with 413")
    void payloadTooLarge() {
        HttpClientResponseException tooLarge = failure(
                HttpRequest.POST("/it-asset-registry/v1/initiate", "x".repeat(200)).contentType("text/plain"));

        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, tooLarge.getStatus());
        assertEquals("ERR-GTW-00413", errorCode(tooLarge));
    }

    @Test
    @DisplayName("management endpoints are served by the gateway itself")
    void ownHealth() {
        assertNotNull(client.toBlocking().retrieve(HttpRequest.GET("/health/liveness")));
    }
}
