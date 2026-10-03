package com.thinklab.gateway;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.web.router.resource.StaticResourceResolver;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * The catch-all entry point: every method on every path outside the management endpoints is routed to its upstream
 * (see {@link RouteTable}). The gateway holds no business logic, and accepts any content type on the body since it
 * never interprets it — the upstream and its own callers do.
 *
 * <p><b>No {@code @Produces(MediaType.ALL)} anywhere in this class</b> (found live, bisected empirically):
 * declaring it — at class level or per method — made Micronaut's route resolution rank this catch-all
 * above every literal route in the application, including the built-in management endpoints
 * (health/metrics/prometheus/loggers/info all 404'd through this controller instead of their own
 * handlers). {@code @Consumes(MediaType.ALL)} alone, on the body-carrying methods, is enough to accept
 * a non-JSON request body (the original reason either annotation was added) without that side effect.
 *
 * <p><b>{@code /swagger-ui/**} and {@code /swagger/**} (found live, bisected empirically too):</b> Micronaut's
 * static-resource serving is a fallback that only runs when no controller route matches a request at all.
 * Since {@code /{+path}} matches literally every path, that fallback is never reached — this is not a route
 * ordering/specificity issue like the one above, it is a structural conflict between "catch-all controller"
 * and "static resource serving" that {@code @Produces} cannot fix. {@link #get} therefore checks the
 * injected {@link StaticResourceResolver} itself before forwarding, so the two configured mappings
 * (see {@code application.yml}'s {@code router.static-resources}) still get served directly.
 */
@Controller("/")
public class GatewayController {

    private static final String SIGN_IN = AuditEntryDescriber.SIGN_IN_PATH.substring(1);
    private static final String FEDERATION_CALLBACK = "identity-federation/v1/login/callback";

    private final UpstreamProxy proxy;
    private final StaticResourceResolver staticResourceResolver;
    private final AuditRecorder auditRecorder;
    private final SessionBridge sessionBridge;

    public GatewayController(UpstreamProxy proxy, StaticResourceResolver staticResourceResolver, AuditRecorder auditRecorder, SessionBridge sessionBridge) {
        this.proxy = proxy;
        this.staticResourceResolver = staticResourceResolver;
        this.auditRecorder = auditRecorder;
        this.sessionBridge = sessionBridge;
    }

    @Get("/{+path}")
    public Mono<MutableHttpResponse<byte[]>> get(HttpRequest<?> request, @PathVariable String path) {
        Mono<MutableHttpResponse<byte[]>> served = staticResource(path).switchIfEmpty(Mono.defer(() -> proxy.forward(request, null)));
        // The federation callback answers the session as JSON; the refresh token goes into an HttpOnly cookie before it can reach the page (ADR-025).
        return FEDERATION_CALLBACK.equals(path) ? served.map(sessionBridge::completeFederatedLogin) : served;
    }

    private Mono<MutableHttpResponse<byte[]>> staticResource(String path) {
        return Mono.fromCallable(() -> staticResourceResolver.resolve("/" + path))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(found -> found.map(this::readResource).orElseGet(Mono::empty));
    }

    private Mono<MutableHttpResponse<byte[]>> readResource(URL url) {
        return Mono.fromCallable(() -> {
            byte[] bytes;
            try (InputStream in = url.openStream()) {
                bytes = in.readAllBytes();
            }
            MutableHttpResponse<byte[]> response = HttpResponse.ok(bytes);
            int dot = url.getPath().lastIndexOf('.');
            if (dot >= 0) {
                MediaType.forExtension(url.getPath().substring(dot + 1)).ifPresent(response::contentType);
            }
            return response;
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(IOException.class, e -> Mono.empty());
    }

    @Delete("/{+path}")
    public Mono<MutableHttpResponse<byte[]>> delete(HttpRequest<?> request, @PathVariable String path) {
        return proxy.forward(request, null);
    }

    @Post("/{+path}")
    @Consumes(MediaType.ALL)
    public Mono<MutableHttpResponse<byte[]>> post(HttpRequest<?> request, @PathVariable String path, @Body @Nullable byte[] body) {
        Mono<MutableHttpResponse<byte[]>> forwarded = proxy.forward(request, body);
        if (!SIGN_IN.equals(path)) {
            return forwarded;
        }
        // Sign-in carries its tenant only in the body, so the audit filter cannot see it: record it here, with the data-minimisation
        // rules of AuditEntryDescriber.describeSignIn (never the password, the email only as a keyed pseudonym).
        return forwarded.doOnSuccess(response -> auditRecorder.recordSignIn(body, response.getStatus().getCode()));
    }

    @Put("/{+path}")
    @Consumes(MediaType.ALL)
    public Mono<MutableHttpResponse<byte[]>> put(HttpRequest<?> request, @PathVariable String path, @Body @Nullable byte[] body) {
        return proxy.forward(request, body);
    }

    @Patch("/{+path}")
    @Consumes(MediaType.ALL)
    public Mono<MutableHttpResponse<byte[]>> patch(HttpRequest<?> request, @PathVariable String path, @Body @Nullable byte[] body) {
        return proxy.forward(request, body);
    }
}
