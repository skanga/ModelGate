package com.modelgate.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProviderClientTest {
  private HttpServer server;
  private final AtomicInteger requestCount = new AtomicInteger();

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void retriesConfiguredStatusCodesAndReturnsSuccessfulResponse() throws Exception {
    startServer(new QueuedHandler(
        new QueuedResponse(429, "{\"error\":\"rate limited\"}", 0),
        new QueuedResponse(200, "{\"ok\":true}", 0)));
    ProviderRequest request = new ProviderRequest(
        serverUrl("/v1/chat/completions"),
        "POST",
        Map.of("content-type", "application/json"),
        "{\"model\":\"gpt-4o-mini\"}",
        new RetryPolicy(1, java.util.List.of(429), false),
        Duration.ofSeconds(2));

    ProviderResponse response = new ProviderClient(HttpClient.newHttpClient()).send(request);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).contains("\"ok\":true");
    assertThat(response.attempts()).isEqualTo(1);
    assertThat(requestCount).hasValue(2);
  }

  @Test
  void ignoresMalformedRetryAfterHeaderAndStillRetries() throws Exception {
    startServer(new QueuedHandler(
        new QueuedResponse(429, "{\"error\":\"rate limited\"}", 0, Map.of("retry-after-ms", "not-a-number")),
        new QueuedResponse(200, "{\"ok\":true}", 0)));
    ProviderRequest request = new ProviderRequest(
        serverUrl("/v1/chat/completions"),
        "POST",
        Map.of("content-type", "application/json"),
        "{\"model\":\"gpt-4o-mini\"}",
        new RetryPolicy(1, java.util.List.of(429), true),
        Duration.ofSeconds(2));

    ProviderResponse response = new ProviderClient(HttpClient.newHttpClient()).send(request);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).contains("\"ok\":true");
    assertThat(response.attempts()).isEqualTo(1);
    assertThat(requestCount).hasValue(2);
  }

  @Test
  void retriesConfiguredStatusCodesBeforeReturningStreamingResponse() throws Exception {
    startServer(new QueuedHandler(
        new QueuedResponse(429, "{\"error\":\"rate limited\"}", 0),
        new QueuedResponse(200, "data: {\"delta\":\"ok\"}\n\ndata: [DONE]\n\n", 0)));
    ProviderRequest request = new ProviderRequest(
        serverUrl("/v1/chat/completions"),
        "POST",
        Map.of("content-type", "application/json"),
        "{\"model\":\"gpt-4o-mini\",\"stream\":true}",
        new RetryPolicy(1, java.util.List.of(429), false),
        Duration.ofSeconds(2));

    ProviderResponse response = new ProviderClient(HttpClient.newHttpClient()).sendStreaming(request);
    String body = new String(response.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.streaming()).isTrue();
    assertThat(response.attempts()).isEqualTo(1);
    assertThat(body).contains("data: {\"delta\":\"ok\"}");
    assertThat(body).contains("data: [DONE]");
    assertThat(requestCount).hasValue(2);
  }

  @Test
  void mapsRequestTimeoutTo408Response() throws Exception {
    startServer(new QueuedHandler(new QueuedResponse(200, "{}", 300)));
    ProviderRequest request = new ProviderRequest(
        serverUrl("/slow"),
        "GET",
        Map.of(),
        "",
        new RetryPolicy(0, java.util.List.of(), false),
        Duration.ofMillis(50));

    ProviderResponse response = new ProviderClient(HttpClient.newHttpClient()).send(request);

    assertThat(response.status()).isEqualTo(408);
    assertThat(response.body()).contains("timeout_error");
  }

  @Test
  void skipsRestrictedRuntimeHeadersWhenBuildingOutboundRequest() throws Exception {
    AtomicReference<String> capturedHost = new AtomicReference<>();
    startServer(exchange -> {
      requestCount.incrementAndGet();
      capturedHost.set(exchange.getRequestHeaders().getFirst("host"));
      respond(exchange, 200, "{\"ok\":true}");
    });
    ProviderRequest request = new ProviderRequest(
        serverUrl("/signed"),
        "POST",
        Map.of(
            "content-type", "application/json",
            "host", "runtime.sagemaker.eu-west-1.amazonaws.com",
            "authorization", "AWS4-HMAC-SHA256 Credential=AKIA_TEST/scope"),
        "{}",
        new RetryPolicy(0, java.util.List.of(), false),
        Duration.ofSeconds(2));

    ProviderResponse response = new ProviderClient(HttpClient.newHttpClient()).send(request);

    assertThat(response.status()).isEqualTo(200);
    assertThat(capturedHost.get()).startsWith("localhost:");
  }

  @Test
  void limitsConcurrentProviderRequests() throws Exception {
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    startServer(exchange -> {
      int active = inFlight.incrementAndGet();
      maxInFlight.accumulateAndGet(active, Math::max);
      int call = requestCount.incrementAndGet();
      if (call == 1) {
        firstStarted.countDown();
        await(releaseFirst);
      }
      respond(exchange, 200, "{\"ok\":true}");
      inFlight.decrementAndGet();
    });
    ProviderClient providerClient = new ProviderClient(HttpClient.newHttpClient(), 1);
    ProviderRequest request = request("/v1/chat/completions");

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<ProviderResponse> first = executor.submit(() -> providerClient.send(request));
      assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
      Future<ProviderResponse> second = executor.submit(() -> providerClient.send(request));

      Thread.sleep(75);
      assertThat(requestCount).hasValue(1);
      assertThat(maxInFlight).hasValue(1);

      releaseFirst.countDown();
      assertThat(first.get().status()).isEqualTo(200);
      assertThat(second.get().status()).isEqualTo(200);
      assertThat(requestCount).hasValue(2);
      assertThat(maxInFlight).hasValue(1);
    }
  }

  @Test
  void holdsStreamingConcurrencyPermitUntilResponseStreamCloses() throws Exception {
    CountDownLatch firstStreamStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstServerResponse = new CountDownLatch(1);
    startServer(exchange -> {
      int call = requestCount.incrementAndGet();
      if (call == 1) {
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
        firstStreamStarted.countDown();
        await(releaseFirstServerResponse);
        exchange.close();
        return;
      }
      respond(exchange, 200, "data: second\n\n");
    });
    ProviderClient providerClient = new ProviderClient(HttpClient.newHttpClient(), 1);
    ProviderRequest request = request("/v1/chat/completions");

    ProviderResponse first = providerClient.sendStreaming(request);
    assertThat(first.streaming()).isTrue();
    assertThat(firstStreamStarted.await(1, TimeUnit.SECONDS)).isTrue();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<ProviderResponse> second = executor.submit(() -> providerClient.sendStreaming(request));

      Thread.sleep(75);
      assertThat(requestCount).hasValue(1);

      first.bodyStream().close();
      releaseFirstServerResponse.countDown();
      ProviderResponse secondResponse = second.get(1, TimeUnit.SECONDS);
      assertThat(secondResponse.status()).isEqualTo(200);
      secondResponse.bodyStream().close();
      assertThat(requestCount).hasValue(2);
    }
  }

  private void startServer(QueuedHandler handler) throws IOException {
    startServer(handler::handle);
  }

  private void startServer(ThrowingHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/", handler::handle);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  private String serverUrl(String path) {
    return "http://localhost:" + server.getAddress().getPort() + path;
  }

  private ProviderRequest request(String path) {
    return new ProviderRequest(
        serverUrl(path),
        "POST",
        Map.of("content-type", "application/json"),
        "{\"model\":\"gpt-4o-mini\"}",
        new RetryPolicy(0, java.util.List.of(), false),
        Duration.ofSeconds(2));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(2, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private final class QueuedHandler {
    private final Deque<QueuedResponse> responses = new ArrayDeque<>();

    QueuedHandler(QueuedResponse... responses) {
      this.responses.addAll(java.util.List.of(responses));
    }

    void handle(HttpExchange exchange) throws IOException {
      requestCount.incrementAndGet();
      QueuedResponse response = responses.removeFirst();
      if (response.delayMillis() > 0) {
        try {
          Thread.sleep(response.delayMillis());
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
      }
      byte[] body = response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      response.headers().forEach(exchange.getResponseHeaders()::add);
      exchange.sendResponseHeaders(response.status(), body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    }
  }

  private record QueuedResponse(int status, String body, long delayMillis, Map<String, String> headers) {
    QueuedResponse(int status, String body, long delayMillis) {
      this(status, body, delayMillis, Map.of());
    }
  }

  @FunctionalInterface
  private interface ThrowingHandler {
    void handle(HttpExchange exchange) throws IOException;
  }
}
