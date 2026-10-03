package com.thinklab.gateway;

import com.thinklab.domain.exception.CsrfRejectedException;
import com.thinklab.domain.exception.SessionUnauthenticatedException;
import com.thinklab.domain.exception.UpstreamUnavailableException;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.json.JsonMapper;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.http.simple.cookies.SimpleCookies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class SessionBridgeTest {

    private final HttpClient client = mock(HttpClient.class);
    private final GatewayProperties gateway = new GatewayProperties();
    private final SessionCookieProperties cookies = new SessionCookieProperties();
    private SessionBridge bridge;

    @BeforeEach
    void setUp() {
        gateway.setRoutes(Map.of("party-authentication", "http://identity:8082"));
        cookies.setSecure(true);
        bridge = new SessionBridge(client, new RouteTable(gateway), cookies, JsonMapper.createDefault());
    }

    /** A server-side view of a request: the client request types do not implement cookies. */
    private static HttpRequest<?> request(String csrfHeader, String refreshToken) {
        HttpRequest<?> request = mock(HttpRequest.class);
        Map<String, String> headers = csrfHeader == null ? Map.of() : Map.of("X-Requested-With", csrfHeader);
        SimpleCookies cookies = new SimpleCookies(ConversionService.SHARED);
        if (refreshToken != null) {
            cookies.put("thinklab_rt", Cookie.of("thinklab_rt", refreshToken));
        }
        when(request.getHeaders()).thenReturn(new SimpleHttpHeaders(headers, ConversionService.SHARED));
        when(request.getCookies()).thenReturn(cookies);
        return request;
    }

    private HttpRequest<?> webApp(String refreshToken) {
        return request("thinklab-web", refreshToken);
    }

    private static MutableHttpResponse<byte[]> upstream(HttpStatus status, String json) {
        MutableHttpResponse<byte[]> response = HttpResponse.<byte[]>status(status);
        return json == null ? response : response.body(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> session(String access, String refresh) {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("accessToken", access);
        session.put("tokenType", "Bearer");
        session.put("expiresIn", 600);
        session.put("refreshToken", refresh);
        session.put("refreshExpiresIn", 3600);
        return session;
    }

    /** The raw Set-Cookie header: a server response does not expose its cookies as objects. */
    private static String setCookie(MutableHttpResponse<?> response) {
        return response.getHeaders().get("Set-Cookie");
    }

    // ------------------------------------------------------------------------------------ federated login

    @Test
    @DisplayName("a successful callback answer becomes an HttpOnly, Secure, SameSite=Strict cookie and a redirect to the web app, with no token in the URL or the body")
    void completesAFederatedLogin() {
        String body = "{\"accessToken\":\"a\",\"tokenType\":\"Bearer\",\"expiresIn\":600,\"refreshToken\":\"r-1\",\"refreshExpiresIn\":3600}";

        MutableHttpResponse<byte[]> response = bridge.completeFederatedLogin(upstream(HttpStatus.OK, body));

        assertEquals(HttpStatus.FOUND, response.getStatus());
        assertEquals("/sso/complete", response.getHeaders().get("Location"));
        String cookie = setCookie(response);
        assertTrue(cookie.startsWith("thinklab_rt=r-1;"), cookie);
        assertTrue(cookie.toLowerCase().contains("httponly") && cookie.toLowerCase().contains("secure") && cookie.toLowerCase().contains("samesite=strict"), cookie);
        assertTrue(cookie.contains("Path=/api/gateway/v1/session") && cookie.contains("Max-Age=3600"), cookie);
        assertFalse(response.getBody().isPresent());
        assertFalse(response.getHeaders().get("Location").contains("r-1"));
    }

    @Test
    @DisplayName("Secure can be turned off for plain-http development, and the cookie name and path are configurable")
    void cookieSettings() {
        cookies.setSecure(false);
        cookies.setName("other_rt");
        cookies.setPath("/gateway/v1/session");
        cookies.setCompleteUrl("/done");
        String body = "{\"refreshToken\":\"r-1\",\"refreshExpiresIn\":60}";

        MutableHttpResponse<byte[]> response = bridge.completeFederatedLogin(upstream(HttpStatus.OK, body));

        String cookie = setCookie(response);
        assertTrue(cookie.startsWith("other_rt=r-1;"), cookie);
        assertFalse(cookie.toLowerCase().contains("secure"), cookie);
        assertTrue(cookie.contains("Path=/gateway/v1/session"), cookie);
        assertEquals("/done", response.getHeaders().get("Location"));
    }

    @Test
    @DisplayName("a failed sign-in sends the browser to the login page with the error code only - never the provider's words")
    void failedFederatedLogin() {
        MutableHttpResponse<byte[]> notLinked = bridge.completeFederatedLogin(upstream(HttpStatus.FORBIDDEN, "{\"error_code\":\"ERR-FED-00403\",\"detail\":\"secret detail\"}"));
        assertEquals(HttpStatus.FOUND, notLinked.getStatus());
        assertEquals("/login?sso_error=ERR-FED-00403", notLinked.getHeaders().get("Location"));
        assertEquals(null, setCookie(notLinked));

        for (MutableHttpResponse<byte[]> odd : new MutableHttpResponse[]{
                upstream(HttpStatus.BAD_REQUEST, "{\"error_code\":\"<script>alert(1)</script>\"}"),
                upstream(HttpStatus.BAD_GATEWAY, "{\"error_code\":42}"),
                upstream(HttpStatus.BAD_GATEWAY, "not json at all"),
                upstream(HttpStatus.BAD_GATEWAY, null),
                HttpResponse.<byte[]>status(HttpStatus.BAD_GATEWAY).body(new byte[0]),
                upstream(HttpStatus.OK, "{\"accessToken\":\"a\"}"),
                upstream(HttpStatus.OK, "{\"refreshToken\":\"  \"}"),
                upstream(HttpStatus.OK, "{\"refreshToken\":7}")}) {
            MutableHttpResponse<byte[]> response = bridge.completeFederatedLogin(odd);
            assertEquals("/login?sso_error=ERR-FED-00401", response.getHeaders().get("Location"));
            assertEquals(null, setCookie(response));
        }
    }

    @Test
    @DisplayName("an OK answer without a numeric refresh lifetime still sets a (session-length) cookie")
    void missingLifetime() {
        MutableHttpResponse<byte[]> response = bridge.completeFederatedLogin(upstream(HttpStatus.OK, "{\"refreshToken\":\"r-1\"}"));

        assertTrue(setCookie(response).startsWith("thinklab_rt=r-1;"));
        assertTrue(setCookie(response).contains("Max-Age=0"));
    }

    // ------------------------------------------------------------------------------------ refresh

    @Test
    @DisplayName("refresh rotates the session behind the cookie, answers only the new access token, and sets the rotated cookie")
    void refreshes() {
        when(client.retrieve(any(HttpRequest.class), any(Argument.class))).thenReturn(Mono.just(session("access-2", "r-2")));

        StepVerifier.create(bridge.refresh(webApp("r-1"))).assertNext(response -> {
            assertEquals(HttpStatus.OK, response.getStatus());
            Map<String, Object> body = response.body();
            assertEquals("access-2", body.get("accessToken"));
            assertEquals("Bearer", body.get("tokenType"));
            assertEquals(600, body.get("expiresIn"));
            assertFalse(body.containsKey("refreshToken"));
            assertTrue(setCookie(response).startsWith("thinklab_rt=r-2;"));
            assertTrue(setCookie(response).toLowerCase().contains("httponly"));
        }).verifyComplete();

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).retrieve(sent.capture(), any(Argument.class));
        assertEquals("http://identity:8082/party-authentication/v1/session/refresh", sent.getValue().getUri().toString());
        assertEquals(Map.of("refreshToken", "r-1"), sent.getValue().getBody().orElseThrow());
    }

    @Test
    @DisplayName("refresh needs the web app's header and a cookie; without either nothing is called")
    void refreshGuards() {
        HttpRequest<?> noHeader = request(null, "r-1");
        HttpRequest<?> wrongHeader = request("XMLHttpRequest", "r-1");

        assertThrows(CsrfRejectedException.class, () -> bridge.refresh(noHeader));
        assertThrows(CsrfRejectedException.class, () -> bridge.refresh(wrongHeader));
        StepVerifier.create(bridge.refresh(webApp(null))).expectError(SessionUnauthenticatedException.class).verify();
        StepVerifier.create(bridge.refresh(webApp(" "))).expectError(SessionUnauthenticatedException.class).verify();
        verify(client, never()).retrieve(any(HttpRequest.class), any(Argument.class));
    }

    @Test
    @DisplayName("a refusal by the identity service is a 401 (sign in again); an unreachable one is a 502")
    void refreshFailures() {
        HttpClientResponseException refusal = new HttpClientResponseException("invalid", HttpResponse.status(HttpStatus.UNAUTHORIZED));
        when(client.retrieve(any(HttpRequest.class), any(Argument.class))).thenReturn(Mono.error(refusal)).thenReturn(Mono.error(new HttpClientException("down")));

        StepVerifier.create(bridge.refresh(webApp("r-1"))).expectError(SessionUnauthenticatedException.class).verify();
        StepVerifier.create(bridge.refresh(webApp("r-1"))).expectError(UpstreamUnavailableException.class).verify();
    }

    // ------------------------------------------------------------------------------------ logout

    @Test
    @DisplayName("logout revokes the session behind the cookie and clears the cookie")
    void logsOut() {
        when(client.retrieve(any(HttpRequest.class), any(Argument.class))).thenReturn(Mono.just(Map.of()));

        StepVerifier.create(bridge.logout(webApp("r-1"))).assertNext(response -> {
            assertEquals(HttpStatus.NO_CONTENT, response.getStatus());
            String cleared = setCookie(response);
            assertTrue(cleared.startsWith("thinklab_rt=;"), cleared);
            assertTrue(cleared.contains("Max-Age=0") && cleared.toLowerCase().contains("httponly"), cleared);
        }).verifyComplete();

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).retrieve(sent.capture(), any(Argument.class));
        assertEquals("http://identity:8082/party-authentication/v1/session/revoke", sent.getValue().getUri().toString());
        assertNotNull(sent.getValue().getBody().orElseThrow());
    }

    @Test
    @DisplayName("logout still clears the cookie when there is none, or when the identity service cannot be reached; it still needs the web app's header")
    void logoutAlwaysClears() {
        when(client.retrieve(any(HttpRequest.class), any(Argument.class))).thenReturn(Mono.error(new HttpClientException("down")));

        StepVerifier.create(bridge.logout(webApp(null))).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        verify(client, never()).retrieve(any(HttpRequest.class), any(Argument.class));
        StepVerifier.create(bridge.logout(webApp("r-1"))).assertNext(r -> assertTrue(setCookie(r).contains("Max-Age=0"))).verifyComplete();
        assertThrows(CsrfRejectedException.class, () -> bridge.logout(request(null, null)));
    }
}
