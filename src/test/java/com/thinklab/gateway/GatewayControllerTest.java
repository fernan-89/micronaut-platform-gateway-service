package com.thinklab.gateway;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.web.router.resource.StaticResourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage of the delegation itself: every HTTP method forwards to {@link UpstreamProxy} with
 * the right body nullability - GET/DELETE never carry one, POST/PUT/PATCH pass whatever the caller sent
 * through unchanged, even {@code null}. {@link GatewayIntegrationTest} proves the same contract end to end
 * through a real server; this test isolates the controller's own five one-line methods, plus GET's static
 * resource seam (see the class Javadoc: static resources are never reachable through a real server request
 * here, since the catch-all itself has to be the one checking for them first).
 */
@SuppressWarnings("unchecked")
class GatewayControllerTest {

    private final UpstreamProxy proxy = mock(UpstreamProxy.class);
    private final StaticResourceResolver staticResourceResolver = mock(StaticResourceResolver.class);
    private final HttpRequest<?> request = mock(HttpRequest.class);
    private GatewayController controller;

    @BeforeEach
    void setUp() {
        lenient().when(staticResourceResolver.resolve(any())).thenReturn(Optional.empty());
        controller = new GatewayController(proxy, staticResourceResolver);
    }

    @Test
    @DisplayName("GET forwards with a null body when no static resource matches")
    void get() {
        MutableHttpResponse<byte[]> response = mock(MutableHttpResponse.class);
        when(proxy.forward(request, null)).thenReturn(Mono.just(response));

        StepVerifier.create(controller.get(request, "any/path")).expectNext(response).verifyComplete();
        verify(proxy).forward(request, null);
    }

    @Test
    @DisplayName("GET serves a matching static resource directly, with a content type derived from its extension, and never calls the proxy")
    void getServesStaticResource(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("swagger-ui.css");
        Files.writeString(file, "body { color: red; }");
        when(staticResourceResolver.resolve("/swagger-ui/res/swagger-ui.css")).thenReturn(Optional.of(file.toUri().toURL()));

        StepVerifier.create(controller.get(request, "swagger-ui/res/swagger-ui.css"))
                .assertNext(response -> {
                    assertArrayEquals("body { color: red; }".getBytes(), response.body());
                    org.junit.jupiter.api.Assertions.assertEquals("text/css", response.getContentType().orElseThrow().toString());
                })
                .verifyComplete();
        verify(proxy, never()).forward(any(), any());
    }

    @Test
    @DisplayName("GET serves a matching static resource with no content type when its name has no extension")
    void getServesStaticResourceWithoutExtension(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("LICENSE");
        Files.writeString(file, "license text");
        when(staticResourceResolver.resolve("/swagger-ui/LICENSE")).thenReturn(Optional.of(file.toUri().toURL()));

        StepVerifier.create(controller.get(request, "swagger-ui/LICENSE"))
                .assertNext(response -> assertArrayEquals("license text".getBytes(), response.body()))
                .verifyComplete();
    }

    @Test
    @DisplayName("GET falls through to the proxy when the resolved resource fails to be read")
    void getFallsThroughWhenStaticResourceReadFails() throws IOException {
        URL unreadable = new URL("file:/this/path/does/not/exist/on/disk.css");
        when(staticResourceResolver.resolve("/swagger-ui/broken.css")).thenReturn(Optional.of(unreadable));
        MutableHttpResponse<byte[]> response = mock(MutableHttpResponse.class);
        when(proxy.forward(request, null)).thenReturn(Mono.just(response));

        StepVerifier.create(controller.get(request, "swagger-ui/broken.css")).expectNext(response).verifyComplete();
        verify(proxy).forward(request, null);
    }

    @Test
    @DisplayName("DELETE forwards with a null body")
    void delete() {
        MutableHttpResponse<byte[]> response = mock(MutableHttpResponse.class);
        when(proxy.forward(request, null)).thenReturn(Mono.just(response));

        StepVerifier.create(controller.delete(request, "any/path")).expectNext(response).verifyComplete();
        verify(proxy).forward(request, null);
    }

    @Test
    @DisplayName("POST forwards the given body unchanged")
    void post() {
        byte[] body = "{\"a\":1}".getBytes();
        MutableHttpResponse<byte[]> response = mock(MutableHttpResponse.class);
        when(proxy.forward(eq(request), eq(body))).thenReturn(Mono.just(response));

        StepVerifier.create(controller.post(request, "any/path", body)).expectNext(response).verifyComplete();
        verify(proxy).forward(request, body);
    }

    @Test
    @DisplayName("POST forwards a null body when the caller sent none")
    void postWithNullBody() {
        MutableHttpResponse<byte[]> response = mock(MutableHttpResponse.class);
        when(proxy.forward(eq(request), isNull())).thenReturn(Mono.just(response));

        StepVerifier.create(controller.post(request, "any/path", null)).expectNext(response).verifyComplete();
        verify(proxy).forward(request, null);
    }

    @Test
    @DisplayName("PUT forwards the given body unchanged")
    void put() {
        byte[] body = "{\"a\":1}".getBytes();
        MutableHttpResponse<byte[]> response = mock(MutableHttpResponse.class);
        when(proxy.forward(eq(request), eq(body))).thenReturn(Mono.just(response));

        StepVerifier.create(controller.put(request, "any/path", body)).expectNext(response).verifyComplete();
        verify(proxy).forward(request, body);
    }

    @Test
    @DisplayName("PATCH forwards the given body unchanged")
    void patch() {
        byte[] body = "{\"a\":1}".getBytes();
        MutableHttpResponse<byte[]> response = mock(MutableHttpResponse.class);
        when(proxy.forward(eq(request), eq(body))).thenReturn(Mono.just(response));

        StepVerifier.create(controller.patch(request, "any/path", body)).expectNext(response).verifyComplete();
        verify(proxy).forward(request, body);
    }
}
