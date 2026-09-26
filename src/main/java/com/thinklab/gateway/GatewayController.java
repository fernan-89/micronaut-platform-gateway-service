package com.thinklab.gateway;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
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
import reactor.core.publisher.Mono;

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
 * <p><b>Known residual gap:</b> the {@code /swagger-ui/**} static-resource mapping (configured in
 * {@code application.yml}, not a {@code @Endpoint} bean) is still shadowed by this catch-all for a
 * different, not-yet-diagnosed reason — it is documentation-only and does not affect health/metrics/
 * readiness, so it is tracked as a known gap rather than blocking on it.
 */
@Controller("/")
public class GatewayController {

    private final UpstreamProxy proxy;

    public GatewayController(UpstreamProxy proxy) {
        this.proxy = proxy;
    }

    @Get("/{+path}")
    public Mono<MutableHttpResponse<byte[]>> get(HttpRequest<?> request, @PathVariable String path) {
        return proxy.forward(request, null);
    }

    @Delete("/{+path}")
    public Mono<MutableHttpResponse<byte[]>> delete(HttpRequest<?> request, @PathVariable String path) {
        return proxy.forward(request, null);
    }

    @Post("/{+path}")
    @Consumes(MediaType.ALL)
    public Mono<MutableHttpResponse<byte[]>> post(HttpRequest<?> request, @PathVariable String path, @Body @Nullable byte[] body) {
        return proxy.forward(request, body);
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
