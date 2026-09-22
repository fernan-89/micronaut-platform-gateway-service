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
 * (see {@link RouteTable}). The gateway holds no business logic, and accepts and returns any content type since it
 * never interprets the body — the upstream and its own callers do.
 */
@Controller("/")
@Consumes(MediaType.ALL)
@Produces(MediaType.ALL)
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
    public Mono<MutableHttpResponse<byte[]>> post(HttpRequest<?> request, @PathVariable String path, @Body @Nullable byte[] body) {
        return proxy.forward(request, body);
    }

    @Put("/{+path}")
    public Mono<MutableHttpResponse<byte[]>> put(HttpRequest<?> request, @PathVariable String path, @Body @Nullable byte[] body) {
        return proxy.forward(request, body);
    }

    @Patch("/{+path}")
    public Mono<MutableHttpResponse<byte[]>> patch(HttpRequest<?> request, @PathVariable String path, @Body @Nullable byte[] body) {
        return proxy.forward(request, body);
    }
}
