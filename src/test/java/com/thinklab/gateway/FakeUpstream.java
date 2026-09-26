package com.thinklab.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A tiny upstream for the gateway tests: echoes what it received, and can fail or stall on demand. */
final class FakeUpstream implements AutoCloseable {

    private final HttpServer server;
    // Without an executor HttpServer handles one exchange at a time, so a stalled /slow request would
    // keep blocking the next test's request long after the gateway has given up on it with a 504.
    private final ExecutorService executor = Executors.newCachedThreadPool();

    FakeUpstream() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/slow")) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int status = path.endsWith("/missing") ? 404 : path.endsWith("/boom") ? 500 : 200;
        String answer = "{\"method\":\"" + exchange.getRequestMethod() + "\",\"path\":\"" + path + "\",\"query\":\""
                + (exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery())
                + "\",\"body\":\"" + body.replace("\"", "'") + "\""
                + ",\"authorization\":\"" + header(exchange, "Authorization") + "\""
                + ",\"tenant\":\"" + header(exchange, "X-Tenant-Id") + "\""
                + ",\"executor\":\"" + header(exchange, "X-Executor") + "\""
                + ",\"forwardedFor\":\"" + header(exchange, "X-Forwarded-For") + "\""
                + ",\"forwardedProto\":\"" + header(exchange, "X-Forwarded-Proto") + "\"}";
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("X-Upstream", "fake");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String header(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "" : value;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
