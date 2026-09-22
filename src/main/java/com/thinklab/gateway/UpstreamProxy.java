package com.thinklab.gateway;

import com.thinklab.domain.exception.PayloadTooLargeException;
import com.thinklab.domain.exception.UpstreamTimeoutException;
import com.thinklab.domain.exception.UpstreamUnavailableException;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.exceptions.ReadTimeoutException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Forwards a request to its upstream and relays the answer. Method, path, query, body and end-to-end headers are
 * preserved (including {@code Authorization}, so each upstream verifies the token itself); hop-by-hop headers are
 * dropped and {@code X-Forwarded-*} added. Upstream 4xx and 5xx answers are relayed unchanged; an unreachable upstream
 * is a 502 and a slow one a 504, both as RFC 7807 problems.
 */
@Singleton
public class UpstreamProxy {

    private static final Logger log = LoggerFactory.getLogger(UpstreamProxy.class);
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");

    private final HttpClient client;
    private final RouteTable routeTable;
    private final GatewayProperties properties;

    @Inject
    public UpstreamProxy(@Client("gateway-upstream") HttpClient client, RouteTable routeTable, GatewayProperties properties) {
        this.client = client;
        this.routeTable = routeTable;
        this.properties = properties;
    }

    public Mono<MutableHttpResponse<byte[]>> forward(HttpRequest<?> request, byte[] body) {
        if (body != null && body.length > properties.getMaxBodyBytes()) {
            return Mono.error(new PayloadTooLargeException(properties.getMaxBodyBytes()));
        }
        return Mono.defer(() -> {
            String upstream = routeTable.upstreamFor(request.getPath());
            URI target = URI.create(upstream + request.getUri().getRawPath() + (request.getUri().getRawQuery() != null ? "?" + request.getUri().getRawQuery() : ""));
            MutableHttpRequest<byte[]> outbound = HttpRequest.create(request.getMethod(), target.toString());
            request.getHeaders().forEach((name, values) -> {
                if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                    values.forEach(value -> outbound.getHeaders().add(name, value));
                }
            });
            String host = request.getHeaders().get("Host");
            if (host != null) {
                outbound.getHeaders().set("X-Forwarded-Host", host);
            }
            outbound.getHeaders().set("X-Forwarded-Proto", "http");
            String remote = remoteAddressOf(request);
            String previous = request.getHeaders().get("X-Forwarded-For");
            outbound.getHeaders().set("X-Forwarded-For", previous == null ? remote : previous + ", " + remote);
            if (body != null && body.length > 0) {
                outbound.body(body);
            }
            log.debug("[GATEWAY] {} {} -> {}", request.getMethod(), request.getPath(), target);
            return Mono.<HttpResponse<?>>from(client.exchange(outbound, Argument.of(byte[].class), Argument.of(byte[].class)))
                    .onErrorResume(HttpClientResponseException.class, e -> Mono.<HttpResponse<?>>just(e.getResponse()))
                    .onErrorMap(ReadTimeoutException.class, e -> new UpstreamTimeoutException(request.getPath()))
                    .onErrorMap(HttpClientException.class, e -> new UpstreamUnavailableException(request.getPath(), e))
                    .map(UpstreamProxy::relay);
        });
    }

    /** The caller's address for {@code X-Forwarded-For}, or {@code "unknown"} when the socket address cannot be resolved. */
    static String remoteAddressOf(HttpRequest<?> request) {
        java.net.InetSocketAddress socketAddress = request.getRemoteAddress();
        if (socketAddress == null) {
            return "unknown";
        }
        java.net.InetAddress address = socketAddress.getAddress();
        if (address == null) {
            return "unknown";
        }
        return address.getHostAddress();
    }

    private static MutableHttpResponse<byte[]> relay(HttpResponse<?> upstream) {
        byte[] content = upstream.getBody(byte[].class).orElse(new byte[0]);
        MutableHttpResponse<byte[]> response = HttpResponse.<byte[]>status(upstream.getStatus()).body(content);
        upstream.getHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> response.getHeaders().add(name, value));
            }
        });
        return response;
    }
}
