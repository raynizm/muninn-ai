package com.muninn.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * In-process OpenAI-compatible server: /v1/models, /v1/chat/completions, /v1/audio/transcriptions. Responses can
 * be overridden per path, and a request can be held mid-flight with {@link #hold} to observe in-progress state.
 */
public final class FakeOpenAi implements AutoCloseable {

    public record Received(String path, String authorization, String contentType, String body) {}

    private record Canned(int status, String body) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> DEFAULTS = Map.of(
            "/v1/models",
                    """
                    {"object":"list","data":[{"id":"gpt-4o-mini"},{"id":"whisper-1"},{"id":"text-embedding-3-small"}]}""",
            "/v1/chat/completions",
                    """
                    {"model":"gpt-4o-mini","choices":[{"index":0,"message":{"role":"assistant","content":"hello there"}}]}""",
            "/v1/audio/transcriptions",
                    """
                    {"text":"hi all","segments":[{"start":0.0,"end":1.5,"text":"hi"},{"start":1.5,"end":2.0,"text":"all"}]}""");

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Canned> overrides = new ConcurrentHashMap<>();
    private final Map<String, Gate> gates = new ConcurrentHashMap<>();
    private volatile String requiredKey;

    public FakeOpenAi() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        DEFAULTS.keySet().forEach(path -> server.createContext(path, this::respond));
        server.setExecutor(executor); // held requests must not block the others
        server.start();
    }

    /** Requests without this bearer key get a 401 whose body echoes the received Authorization header. */
    void requireKey(String key) {
        this.requiredKey = key;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Received> requests() {
        return requests;
    }

    public long count(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).count();
    }

    Received last() {
        return requests.get(requests.size() - 1);
    }

    /** Every later request to {@code path} gets this status and raw body. */
    public void respond(String path, int status, String body) {
        overrides.put(path, new Canned(status, body));
    }

    /** Chat completions answer with this assistant message content. */
    public void chatContent(String content) {
        var res = JSON.createObjectNode().put("model", "gpt-4o-mini");
        res.putArray("choices")
                .addObject()
                .put("index", 0)
                .putObject("message")
                .put("role", "assistant")
                .put("content", content);
        respond("/v1/chat/completions", 200, res.toString());
    }

    /** Back to the default responses, no held requests, no recorded requests, no key required. */
    public void reset() {
        overrides.clear();
        gates.values().forEach(Gate::release);
        gates.clear();
        requests.clear();
        requiredKey = null;
    }

    /** The next request to {@code path} waits (up to 30s) until the returned gate is released. */
    public Gate hold(String path) {
        Gate gate = new Gate();
        gates.put(path, gate);
        return gate;
    }

    public static final class Gate {
        private final CountDownLatch arrived = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        /** Waits until the held request has arrived at the server. */
        public void awaitArrived() throws InterruptedException {
            if (!arrived.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("held request never arrived");
            }
        }

        public void release() {
            released.countDown();
        }

        void pass() {
            arrived.countDown();
            try {
                released.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void respond(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
        requests.add(new Received(path, auth, ex.getRequestHeaders().getFirst("Content-Type"), body));
        Gate gate = gates.remove(path);
        if (gate != null) {
            gate.pass();
        }
        boolean denied = requiredKey != null && !("Bearer " + requiredKey).equals(auth);
        Canned canned = denied
                ? new Canned(401, "{\"error\":\"bad credentials: " + auth + "\"}")
                : overrides.getOrDefault(path, new Canned(200, DEFAULTS.get(path)));
        byte[] out = canned.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(canned.status(), out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @Override
    public void close() {
        gates.values().forEach(Gate::release);
        server.stop(0);
        executor.shutdownNow();
    }
}
