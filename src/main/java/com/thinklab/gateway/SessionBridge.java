package com.thinklab.gateway;

import com.thinklab.domain.exception.CsrfRejectedException;
import com.thinklab.domain.exception.SessionUnauthenticatedException;
import com.thinklab.domain.exception.UpstreamUnavailableException;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Moves the refresh token out of the page's reach (ADR-025). The browser only ever holds an HttpOnly, SameSite=Strict cookie with the
 * refresh token; its scripts hold the short-lived access token, in memory.
 *
 * <ul>
 *   <li>{@link #completeFederatedLogin}: the identity-federation callback answers the session as JSON; this takes the refresh token
 *       out of it into the cookie and sends the browser on to the web app (the refresh token and the access token never travel through
 *       the URL or the page). A failed sign-in sends the browser to the login page with the error code only.</li>
 *   <li>{@link #refresh}: reads the cookie, rotates the session at party-authentication (single-use refresh, theft detection),
 *       answers only the new access token and sets the rotated cookie.</li>
 *   <li>{@link #logout}: revokes the session and clears the cookie; always succeeds from the caller's point of view.</li>
 * </ul>
 *
 * <p><b>CSRF.</b> Besides SameSite=Strict, the two calls need {@code X-Requested-With: thinklab-web}, a header a cross-site page cannot
 * add without a CORS preflight the gateway never grants.
 */
@Singleton
public class SessionBridge {

    private static final Logger log = LoggerFactory.getLogger(SessionBridge.class);
    static final String CSRF_HEADER = "X-Requested-With";
    static final String CSRF_VALUE = "thinklab-web";
    private static final String REFRESH_PATH = "/party-authentication/v1/session/refresh";
    private static final String REVOKE_PATH = "/party-authentication/v1/session/revoke";
    private static final Pattern ERROR_CODE = Pattern.compile("ERR-[A-Z]{2,10}-\\d{5}");
    private static final Argument<Map<String, Object>> JSON_OBJECT = Argument.mapOf(String.class, Object.class);

    private final HttpClient client;
    private final RouteTable routeTable;
    private final SessionCookieProperties properties;
    private final JsonMapper json;

    public SessionBridge(@Client("gateway-upstream") HttpClient client, RouteTable routeTable, SessionCookieProperties properties, JsonMapper json) {
        this.client = client;
        this.routeTable = routeTable;
        this.properties = properties;
        this.json = json;
    }

    /** Turns the federation callback's answer into the cookie and a redirect to the web app (or to the login page on failure). */
    public MutableHttpResponse<byte[]> completeFederatedLogin(MutableHttpResponse<byte[]> upstream) {
        Map<String, Object> body = parse(upstream.body());
        if (upstream.getStatus() == HttpStatus.OK && body.get("refreshToken") instanceof String token && !token.isBlank()) {
            return redirect(properties.getCompleteUrl()).cookie(refreshCookie(token, seconds(body.get("refreshExpiresIn"))));
        }
        Object code = body.get("error_code");
        String safe = code instanceof String text && ERROR_CODE.matcher(text).matches() ? text : "ERR-FED-00401";
        return redirect(properties.getLoginUrl() + "?sso_error=" + safe);
    }

    /** Rotates the session behind the cookie and answers the new access token. */
    public Mono<MutableHttpResponse<Map<String, Object>>> refresh(HttpRequest<?> request) {
        requireWebApp(request);
        String token = cookieValue(request);
        if (token == null) {
            return Mono.error(new SessionUnauthenticatedException());
        }
        return call(REFRESH_PATH, token)
                .map(session -> {
                    Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put("accessToken", session.get("accessToken"));
                    answer.put("tokenType", session.get("tokenType"));
                    answer.put("expiresIn", session.get("expiresIn"));
                    return HttpResponse.ok(answer).cookie(refreshCookie(String.valueOf(session.get("refreshToken")), seconds(session.get("refreshExpiresIn"))));
                })
                .onErrorResume(HttpClientResponseException.class, refusal -> {
                    log.info("[GATEWAY] The identity service refused a session refresh");
                    return Mono.<MutableHttpResponse<Map<String, Object>>>error(new SessionUnauthenticatedException());
                })
                .onErrorMap(HttpClientException.class, failure -> new UpstreamUnavailableException(REFRESH_PATH, failure));
    }

    /** Revokes the session behind the cookie (best effort) and clears the cookie. */
    public Mono<MutableHttpResponse<Void>> logout(HttpRequest<?> request) {
        requireWebApp(request);
        String token = cookieValue(request);
        Mono<Void> revoked = token == null
                ? Mono.empty()
                : call(REVOKE_PATH, token).onErrorResume(failure -> Mono.empty()).then();
        return revoked.then(Mono.fromSupplier(() -> HttpResponse.<Void>noContent().cookie(clearingCookie())));
    }

    private Mono<Map<String, Object>> call(String path, String refreshToken) {
        String url = routeTable.upstreamFor(path) + path;
        return Mono.from(client.retrieve(HttpRequest.POST(url, Map.of("refreshToken", refreshToken)), JSON_OBJECT));
    }

    private void requireWebApp(HttpRequest<?> request) {
        if (!CSRF_VALUE.equals(request.getHeaders().get(CSRF_HEADER))) {
            throw new CsrfRejectedException();
        }
    }

    private String cookieValue(HttpRequest<?> request) {
        return request.getCookies().findCookie(properties.getName()).map(Cookie::getValue).filter(value -> !value.isBlank()).orElse(null);
    }

    private Cookie refreshCookie(String token, long maxAgeSeconds) {
        return Cookie.of(properties.getName(), token).httpOnly(true).secure(properties.isSecure()).sameSite(SameSite.Strict)
                .path(properties.getPath()).maxAge(maxAgeSeconds);
    }

    private Cookie clearingCookie() {
        return refreshCookie("", 0);
    }

    private static MutableHttpResponse<byte[]> redirect(String location) {
        return HttpResponse.<byte[]>status(HttpStatus.FOUND).headers(headers -> headers.location(URI.create(location)));
    }

    private Map<String, Object> parse(byte[] body) {
        if (body == null || body.length == 0) {
            return Map.of();
        }
        try {
            return json.readValue(body, JSON_OBJECT);
        } catch (IOException | RuntimeException unreadable) {
            return Map.of();
        }
    }

    private static long seconds(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
