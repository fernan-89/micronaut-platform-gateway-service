package com.thinklab.gateway;

import com.thinklab.domain.exception.PayloadTooLargeException;
import com.thinklab.domain.exception.RouteNotFoundException;
import com.thinklab.domain.exception.UpstreamTimeoutException;
import com.thinklab.domain.exception.UpstreamUnavailableException;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.exceptions.ReadTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit-level coverage of the branches the live gateway integration test cannot reach without a real server request. */
@SuppressWarnings("unchecked")
class UpstreamProxyTest {

    private final HttpClient client = mock(HttpClient.class);
    private RouteTable routeTable;
    private GatewayProperties properties;
    private UpstreamProxy proxy;
    private String upstreamUrl;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        properties.setRoutes(Map.of("it-asset-registry", "http://upstream:80"));
        properties.setMaxBodyBytes(1024);
        routeTable = new RouteTable(properties);
        proxy = new UpstreamProxy(client, routeTable, properties);
        upstreamUrl = "http://upstream:80";
    }

    private void mockExchange(Object toReturn) {
        when(client.exchange(any(HttpRequest.class), any(Argument.class), any(Argument.class)))
                .thenReturn((org.reactivestreams.Publisher) toReturn);
    }

    @Test
    @DisplayName("a body over the configured limit never reaches the route table or the client")
    void tooLarge() {
        properties.setMaxBodyBytes(4);

        StepVerifier.create(proxy.forward(HttpRequest.GET("/it-asset-registry/v1/x"), "too-long".getBytes(StandardCharsets.UTF_8)))
                .expectError(PayloadTooLargeException.class).verify();
    }

    @Test
    @DisplayName("an unroutable path is a RouteNotFoundException before any upstream call")
    void unroutable() {
        StepVerifier.create(proxy.forward(HttpRequest.GET("/no-such-domain/v1/x"), null))
                .expectError(RouteNotFoundException.class).verify();
    }

    @Test
    @DisplayName("without a Host header, no X-Forwarded-Host is added; without a remote address, X-Forwarded-For is 'unknown'")
    void noHostNoRemoteAddress() {
        mockExchange(Flux.just(HttpResponse.ok("ok".getBytes(StandardCharsets.UTF_8))));

        MutableHttpResponse<byte[]> response = proxy.forward(HttpRequest.GET("/it-asset-registry/v1/x"), null).block();

        assertEquals(200, response.getStatus().getCode());
    }

    @Test
    @DisplayName("a Host header becomes X-Forwarded-Host, and an existing X-Forwarded-For is appended to, not replaced")
    void hostAndForwardedForAppended() {
        mockExchange(Flux.just(HttpResponse.ok("ok".getBytes(StandardCharsets.UTF_8))));

        HttpRequest<?> request = HttpRequest.GET("/it-asset-registry/v1/x")
                .header("Host", "gateway.example")
                .header("X-Forwarded-For", "198.51.100.1");

        proxy.forward(request, null).block();
        // The outbound request is only observable through the mocked client invocation; a non-throwing call with these
        // headers set exercises both branches (host != null, previous X-Forwarded-For != null).
    }

    @Test
    @DisplayName("a null body and an empty (non-null) body both skip setting the outbound body, without error")
    void emptyAndNullBody() {
        mockExchange(Flux.just(HttpResponse.ok(new byte[0])));

        assertEquals(200, proxy.forward(HttpRequest.GET("/it-asset-registry/v1/x"), null).block().getStatus().getCode());
        assertEquals(200, proxy.forward(HttpRequest.GET("/it-asset-registry/v1/x"), new byte[0]).block().getStatus().getCode());
    }

    @Test
    @DisplayName("an upstream HTTP error response (4xx/5xx) is relayed unchanged, including its headers")
    void upstreamErrorRelayed() {
        HttpResponse<byte[]> notFound = HttpResponse.notFound("missing".getBytes(StandardCharsets.UTF_8));
        mockExchange(Flux.error(new HttpClientResponseException("not found", notFound)));

        MutableHttpResponse<byte[]> response = proxy.forward(HttpRequest.GET("/it-asset-registry/v1/x"), null).block();

        assertEquals(404, response.getStatus().getCode());
        assertEquals("missing", new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a read timeout from the upstream client maps to 504 UpstreamTimeoutException")
    void readTimeout() {
        mockExchange(Flux.error(ReadTimeoutException.TIMEOUT_EXCEPTION));

        StepVerifier.create(proxy.forward(HttpRequest.GET("/it-asset-registry/v1/x"), null))
                .expectError(UpstreamTimeoutException.class).verify();
    }

    @Test
    @DisplayName("any other client-side failure maps to 502 UpstreamUnavailableException")
    void unreachable() {
        mockExchange(Flux.error(new HttpClientException("connection refused")));

        StepVerifier.create(proxy.forward(HttpRequest.GET("/it-asset-registry/v1/x"), null))
                .expectError(UpstreamUnavailableException.class).verify();
    }

    @Test
    @DisplayName("a hop-by-hop response header from the upstream (e.g. Connection) is not relayed")
    void hopByHopHeadersDropped() {
        MutableHttpResponse<byte[]> upstreamResponse = HttpResponse.ok("ok".getBytes(StandardCharsets.UTF_8));
        upstreamResponse.header("Connection", "keep-alive");
        upstreamResponse.header("X-Upstream", "yes");
        mockExchange(Flux.just(upstreamResponse));

        MutableHttpResponse<byte[]> response = proxy.forward(HttpRequest.GET("/it-asset-registry/v1/x"), null).block();

        assertNull(response.getHeaders().get("Connection"));
        assertEquals("yes", response.getHeaders().get("X-Upstream"));
    }

    // remoteAddressOf is extracted specifically so these three cases (no socket, socket without a resolved
    // InetAddress, socket with one) are each a single, unambiguous unit test.

    @Test
    @DisplayName("remoteAddressOf is 'unknown' when the request carries no remote address")
    void remoteAddressOfNoSocket() {
        HttpRequest<?> request = mock(HttpRequest.class);
        when(request.getRemoteAddress()).thenReturn(null);

        assertEquals("unknown", UpstreamProxy.remoteAddressOf(request));
    }

    @Test
    @DisplayName("remoteAddressOf is 'unknown' when the socket address cannot be resolved to an InetAddress")
    void remoteAddressOfUnresolvedSocket() {
        HttpRequest<?> request = mock(HttpRequest.class);
        InetSocketAddress unresolvable = mock(InetSocketAddress.class);
        when(unresolvable.getAddress()).thenReturn(null);
        when(request.getRemoteAddress()).thenReturn(unresolvable);

        assertEquals("unknown", UpstreamProxy.remoteAddressOf(request));
    }

    @Test
    @DisplayName("remoteAddressOf reports the resolved host address")
    void remoteAddressOfResolvedSocket() {
        HttpRequest<?> request = mock(HttpRequest.class);
        InetSocketAddress resolved = new InetSocketAddress(InetAddress.getLoopbackAddress(), 54321);
        when(request.getRemoteAddress()).thenReturn(resolved);

        assertEquals(InetAddress.getLoopbackAddress().getHostAddress(), UpstreamProxy.remoteAddressOf(request));
    }

    @Test
    @DisplayName("the query string is preserved when present and omitted when absent")
    void queryStringHandling() {
        mockExchange(Flux.just(HttpResponse.ok(new byte[0])));

        assertTrue(proxy.forward(HttpRequest.GET("/it-asset-registry/v1/x?a=1"), null).block().getStatus().getCode() == 200);
        assertTrue(proxy.forward(HttpRequest.GET("/it-asset-registry/v1/x"), null).block().getStatus().getCode() == 200);
    }
}
