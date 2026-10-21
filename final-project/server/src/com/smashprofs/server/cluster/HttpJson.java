package com.smashprofs.server.cluster;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;

/** Loopback prototype HTTP boundary with bounded bodies, executor, timeouts and explicit errors. */
public final class HttpJson implements AutoCloseable {
    public static final ObjectMapper JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                    .maxNestingDepth(32).maxStringLength(4096).maxNumberLength(32).build()).build());
    private static final int LIMIT = 1_048_576;
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    @FunctionalInterface public interface Handler { Object handle(String path, JsonNode body, String credential) throws Exception; }
    public record AfterResponse(Object response, Runnable action) { }
    public HttpJson(int port, Handler handler) throws IOException {
        if (System.getProperty("sun.net.httpserver.maxReqTime") == null) { System.setProperty("sun.net.httpserver.maxReqTime", "5"); }
        if (System.getProperty("sun.net.httpserver.maxRspTime") == null) { System.setProperty("sun.net.httpserver.maxRspTime", "5"); }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128),
                r -> { var t = new Thread(r, "cluster-http"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            Runnable afterResponse = null;
            try (exchange) {
                try {
                    String method = exchange.getRequestMethod();
                    if (!(method.equals("POST") || (method.equals("GET") && exchange.getRequestURI().getPath().equals("/metrics")))) {
                        throw new Failure(405, "METHOD_NOT_ALLOWED");
                    }
                    byte[] bytes = exchange.getRequestBody().readNBytes(LIMIT + 1);
                    if (bytes.length > LIMIT) { throw new Failure(413, "BODY_TOO_LARGE"); }
                    JsonNode body = bytes.length == 0 ? JSON.createObjectNode() : JSON.readTree(bytes);
                    if (body == null || !body.isObject()) { throw new Failure(400, "INVALID_BODY"); }
                    Object response = handler.handle(exchange.getRequestURI().getPath(), body,
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    if (response instanceof AfterResponse deferred) {
                        response = deferred.response(); afterResponse = deferred.action();
                    }
                    respond(exchange, 200, response);
                } catch (Failure failure) { respond(exchange, failure.status, java.util.Map.of("error", failure.getMessage())); }
                catch (com.fasterxml.jackson.core.JacksonException | IllegalArgumentException failure) {
                    respond(exchange, 400, java.util.Map.of("error", "INVALID_REQUEST"));
                } catch (Exception failure) {
                    respond(exchange, 500, java.util.Map.of("error", "INTERNAL_ERROR"));
                }
            }
            // Closing the exchange first flushes the fixed-length response before lifecycle teardown.
            if (afterResponse != null) { afterResponse.run(); }
        });
        server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    private static void respond(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    public static JsonNode call(String base, String path, Object body, String credential) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        connection.setConnectTimeout(1500); connection.setReadTimeout(2000); connection.setRequestMethod("POST");
        connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
        if (credential != null) { connection.setRequestProperty("Authorization", credential); }
        try {
            byte[] bytes = JSON.writeValueAsBytes(body); connection.setFixedLengthStreamingMode(bytes.length);
            try (var output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            try (var input = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                if (input == null) { throw new IOException("HTTP " + status); }
                byte[] response = input.readNBytes(LIMIT + 1);
                if (response.length > LIMIT) { throw new IOException("Response too large"); }
                JsonNode result = JSON.readTree(response);
                if (status >= 400) { throw new IOException("HTTP " + status + ": " + result.path("error").asText()); }
                return result;
            }
        } finally { connection.disconnect(); }
    }
    public static void requireSecret(String actual, String expected) {
        if (actual == null || expected == null || !MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throw new Failure(401, "UNAUTHORIZED");
        }
    }
    public static String text(JsonNode body, String name) {
        JsonNode node = body.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank() || node.asText().length() > 256) {
            throw new Failure(400, "INVALID_" + name);
        }
        return node.asText();
    }
    public static ObjectNode object() { return JSON.createObjectNode(); }
    public static final class Failure extends RuntimeException {
        public final int status;
        public Failure(int status, String code) { super(code); this.status = status; }
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
