package com.modelgate.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.gateway.cache.RedisCacheStore;
import com.modelgate.gateway.cache.RedisResponseCache;
import com.modelgate.gateway.cache.ResponseCacheFactory;
import com.modelgate.gateway.cache.SimpleResponseCache;
import com.modelgate.gateway.config.GatewayRuntimeConfig;
import com.modelgate.gateway.observability.OpenTelemetrySettings;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GatewayAppTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private Javalin app;
  private Javalin realtimeProviderApp;
  private HttpServer providerServer;
  private final List<HttpServer> providerServers = new ArrayList<>();
  private final HttpClient client = HttpClient.newHttpClient();

  @AfterEach
  void stopApp() {
    if (app != null) {
      app.stop();
    }
    if (realtimeProviderApp != null) {
      realtimeProviderApp.stop();
    }
    if (providerServer != null) {
      providerServer.stop(0);
    }
    for (HttpServer server : providerServers) {
      server.stop(0);
    }
    providerServers.clear();
  }

  @Test
  void exposesRootModelsAndJsonNotFoundResponses() throws Exception {
    app = GatewayApp.create().start(0);
    String baseUrl = "http://localhost:" + app.port();

    HttpResponse<String> root = get(baseUrl + "/");
    HttpResponse<String> models = get(baseUrl + "/v1/models");
    HttpResponse<String> missing = get(baseUrl + "/does-not-exist");

    assertThat(root.statusCode()).isEqualTo(200);
    assertThat(root.body()).isEqualTo("AI Gateway says hey!");
    assertThat(models.statusCode()).isEqualTo(200);
    assertThat(models.body()).contains("\"object\":\"list\"");
    assertThat(models.body()).contains("\"id\":\"gpt-4o-mini\"");
    assertThat(models.body()).contains("\"provider\":{\"id\":\"openai\"}");
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(missing.body()).contains("\"message\":\"Not Found\"");
  }

  @Test
  void modelsRouteProxiesToConfiguredProviderWhenProviderContextIsPresent() throws Exception {
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerMethod = new AtomicReference<>();
    AtomicReference<String> providerAuthorization = new AtomicReference<>();
    AtomicReference<Integer> providerBodyLength = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getPath());
      providerMethod.set(exchange.getRequestMethod());
      providerAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      providerBodyLength.set(exchange.getRequestBody().readAllBytes().length);
      respond(exchange, 200, "{\"object\":\"list\",\"data\":[{\"id\":\"model-a\",\"object\":\"model\"}]}");
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "provider": "openai",
          "api_key": "sk-models",
          "custom_host": "%s"
        }
        """.formatted(providerBaseUrl()).replace("\n", "");
    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/models"))
            .header("x-modelgate-config", config)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"model-a\"");
    assertThat(response.headers().firstValue("x-modelgate-provider")).contains("openai");
    assertThat(providerPath).hasValue("/v1/models");
    assertThat(providerMethod).hasValue("GET");
    assertThat(providerAuthorization).hasValue("Bearer sk-models");
    assertThat(providerBodyLength).hasValue(0);
  }

  @Test
  void proxyPrefixIsStrippedForGetRequestsWithQueryStrings() throws Exception {
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerQuery = new AtomicReference<>();
    AtomicReference<String> providerMethod = new AtomicReference<>();
    AtomicReference<Integer> providerBodyLength = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getPath());
      providerQuery.set(exchange.getRequestURI().getRawQuery());
      providerMethod.set(exchange.getRequestMethod());
      providerBodyLength.set(exchange.getRequestBody().readAllBytes().length);
      respond(exchange, 200, "{\"object\":\"list\",\"data\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "provider": "openai",
          "api_key": "sk-files",
          "custom_host": "%s"
        }
        """.formatted(providerBaseUrl()).replace("\n", "");
    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create(
                "http://localhost:" + app.port() + "/v1/proxy/files?purpose=fine-tune&limit=10"))
            .header("x-modelgate-config", config)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(providerPath).hasValue("/files");
    assertThat(providerQuery).hasValue("purpose=fine-tune&limit=10");
    assertThat(providerMethod).hasValue("GET");
    assertThat(providerBodyLength).hasValue(0);
  }

  @Test
  void proxyPrefixIsStrippedForDeletePatchAndPutRequests() throws Exception {
    List<String> providerPaths = new ArrayList<>();
    List<String> providerMethods = new ArrayList<>();
    List<String> providerBodies = new ArrayList<>();
    startProviderServer(exchange -> {
      providerPaths.add(exchange.getRequestURI().getPath());
      providerMethods.add(exchange.getRequestMethod());
      providerBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"ok\":true}");
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "provider": "openai",
          "api_key": "sk-proxy",
          "custom_host": "%s"
        }
        """.formatted(providerBaseUrl()).replace("\n", "");
    String baseUrl = "http://localhost:" + app.port();

    HttpResponse<String> deleteResponse = client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/v1/proxy/files/file-1"))
            .header("x-modelgate-config", config)
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> patchResponse = client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/v1/proxy/responses/resp-1"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"metadata\":{\"tier\":\"gold\"}}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> putResponse = client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/v1/proxy/files/file-1"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .PUT(HttpRequest.BodyPublishers.ofString("{\"purpose\":\"fine-tune\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(deleteResponse.statusCode()).isEqualTo(200);
    assertThat(patchResponse.statusCode()).isEqualTo(200);
    assertThat(putResponse.statusCode()).isEqualTo(200);
    assertThat(providerPaths).containsExactly("/files/file-1", "/responses/resp-1", "/files/file-1");
    assertThat(providerMethods).containsExactly("DELETE", "PATCH", "PUT");
    assertThat(providerBodies).containsExactly(
        "",
        "{\"metadata\":{\"tier\":\"gold\"}}",
        "{\"purpose\":\"fine-tune\"}");
  }

  @Test
  void proxyPrefixIsStrippedForHeadAndOptionsRequests() throws Exception {
    List<String> providerPaths = new ArrayList<>();
    List<String> providerMethods = new ArrayList<>();
    startProviderServer(exchange -> {
      providerPaths.add(exchange.getRequestURI().getPath());
      providerMethods.add(exchange.getRequestMethod());
      exchange.getResponseHeaders().put("allow", java.util.List.of("GET,POST,OPTIONS,HEAD"));
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "provider": "openai",
          "api_key": "sk-proxy",
          "custom_host": "%s"
        }
        """.formatted(providerBaseUrl()).replace("\n", "");
    String baseUrl = "http://localhost:" + app.port();

    HttpResponse<String> headResponse = client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/v1/proxy/files/file-1/content"))
            .header("x-modelgate-config", config)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> optionsResponse = client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/v1/proxy/responses"))
            .header("x-modelgate-config", config)
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(headResponse.statusCode()).isEqualTo(204);
    assertThat(optionsResponse.statusCode()).isEqualTo(204);
    assertThat(optionsResponse.headers().firstValue("allow")).contains("GET,POST,OPTIONS,HEAD");
    assertThat(providerPaths).containsExactly("/files/file-1/content", "/responses");
    assertThat(providerMethods).containsExactly("HEAD", "OPTIONS");
  }

  @Test
  void exposesOperationalHealthAndReadinessEndpoints() throws Exception {
    app = GatewayApp.create().start(0);
    String baseUrl = "http://localhost:" + app.port();

    HttpResponse<String> health = get(baseUrl + "/health");
    HttpResponse<String> ready = get(baseUrl + "/ready");

    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body()).contains("\"status\":\"ok\"");
    assertThat(health.body()).contains("\"service\":\"modelgate\"");
    assertThat(ready.statusCode()).isEqualTo(200);
    assertThat(ready.body()).contains("\"status\":\"ready\"");
    assertThat(ready.body()).contains("\"service\":\"modelgate\"");
  }

  @Test
  void metricsEndpointStartsWithZeroedGatewayCounters() throws Exception {
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = get("http://localhost:" + app.port() + "/metrics");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("content-type")).contains("application/json");
    assertThat(response.body()).contains("\"service\":\"modelgate\"");
    assertThat(response.body()).contains("\"total_requests\":0");
    assertThat(response.body()).contains("\"validation_failures\":0");
    assertThat(response.body()).contains("\"provider_requests\":0");
    assertThat(response.body()).contains("\"cache_hits\":0");
    assertThat(response.body()).contains("\"cache_misses\":0");
    assertThat(response.body()).contains("\"duration_total_ms\":0");
    assertThat(response.body()).contains("\"virtual_threads_enabled\":true");
    assertThat(response.body()).contains("\"available_processors\":");
    assertThat(response.body()).contains("\"jvm_thread_count\":");
    assertThat(response.body()).contains("\"jvm_peak_thread_count\":");
    assertThat(response.body()).contains("\"uptime_ms\":");
  }

  @Test
  void metricsEndpointExposesProviderConcurrencyState() throws Exception {
    app = GatewayApp.create(new GatewayRuntimeConfig(3)).start(0);

    HttpResponse<String> response = get("http://localhost:" + app.port() + "/metrics");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"provider_concurrency_limit\":3");
    assertThat(response.body()).contains("\"provider_concurrency_available\":3");
    assertThat(response.body()).contains("\"provider_concurrency_in_flight\":0");
  }

  @Test
  void metricsEndpointExposesPluginConcurrencyState() throws Exception {
    app = GatewayApp.create(new GatewayRuntimeConfig(3, 5)).start(0);

    HttpResponse<String> response = get("http://localhost:" + app.port() + "/metrics");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"plugin_concurrency_limit\":5");
    assertThat(response.body()).contains("\"plugin_concurrency_available\":5");
    assertThat(response.body()).contains("\"plugin_concurrency_in_flight\":0");
  }

  @Test
  void gatewayBoundsConcurrentGuardrailPluginExecutions() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    AtomicInteger webhookCalls = new AtomicInteger();
    AtomicInteger activeWebhooks = new AtomicInteger();
    AtomicInteger maxActiveWebhooks = new AtomicInteger();
    CountDownLatch firstWebhookEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstWebhook = new CountDownLatch(1);
    startProviderServer(exchange -> {
      providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-plugin-limit\",\"choices\":[]}");
    });
    HttpServer webhookServer = startAdditionalProviderServer(exchange -> {
      int call = webhookCalls.incrementAndGet();
      int active = activeWebhooks.incrementAndGet();
      maxActiveWebhooks.accumulateAndGet(active, Math::max);
      if (call == 1) {
        firstWebhookEntered.countDown();
        if (!releaseFirstWebhook.await(2, TimeUnit.SECONDS)) {
          throw new TimeoutException("timed out waiting to release webhook");
        }
      }
      try {
        respond(exchange, 200, "{\"verdict\":true,\"data\":{\"ok\":true}}");
      } finally {
        activeWebhooks.decrementAndGet();
      }
    });
    app = GatewayApp.create(new GatewayRuntimeConfig(256, 1)).start(0);

    String config = """
        {
          "input_guardrails": [
            {
              "default.webhook": {
                "webhookURL": "%s/hook"
              }
            }
          ]
        }
        """.formatted(serverBaseUrl(webhookServer)).replace("\n", "");

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(
          () -> sendChatUnchecked(chatRequest(config, "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"first\"}]}")),
          executor);
      assertThat(firstWebhookEntered.await(1, TimeUnit.SECONDS)).isTrue();

      CompletableFuture<HttpResponse<String>> second = CompletableFuture.supplyAsync(
          () -> sendChatUnchecked(chatRequest(config, "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"second\"}]}")),
          executor);
      Thread.sleep(150);

      assertThat(webhookCalls).hasValue(1);
      assertThat(providerCalls).hasValue(0);

      releaseFirstWebhook.countDown();

      assertThat(first.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      assertThat(second.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      assertThat(webhookCalls).hasValue(2);
      assertThat(providerCalls).hasValue(2);
      assertThat(maxActiveWebhooks).hasValue(1);
    }
  }

  @Test
  void metricsEndpointTracksProviderValidationAndCacheOutcomes() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    startProviderServer(exchange -> {
      providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-metrics\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = "{\"cache\":{\"mode\":\"simple\",\"max_age\":60000}}";
    HttpRequest firstRequest = chatRequest(config, "{\"model\":\"gpt-4o-mini\"}");
    HttpRequest secondRequest = chatRequest(config, "{\"model\":\"gpt-4o-mini\"}");
    HttpResponse<String> first = client.send(firstRequest, HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> second = client.send(secondRequest, HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> invalid = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> metrics = get("http://localhost:" + app.port() + "/metrics");

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(invalid.statusCode()).isEqualTo(400);
    assertThat(providerCalls).hasValue(1);
    assertThat(metrics.body()).contains("\"total_requests\":3");
    assertThat(metrics.body()).contains("\"status_2xx\":2");
    assertThat(metrics.body()).contains("\"status_4xx\":1");
    assertThat(metrics.body()).contains("\"validation_failures\":1");
    assertThat(metrics.body()).contains("\"provider_requests\":1");
    assertThat(metrics.body()).contains("\"cache_hits\":1");
    assertThat(metrics.body()).contains("\"cache_misses\":1");
    assertThat(metrics.body()).contains("\"duration_total_ms\":");
    assertThat(metrics.body()).contains("\"duration_max_ms\":");
  }

  @Test
  void metricsEndpointSupportsPrometheusScrapeFormat() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, "{\"id\":\"chatcmpl-prometheus\",\"choices\":[]}"));
    app = GatewayApp.create(new GatewayRuntimeConfig(7)).start(0);

    HttpResponse<String> providerResponse = client.send(
        chatRequest("{}", "{\"model\":\"gpt-4o-mini\"}"),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> metrics = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/metrics"))
            .header("accept", "text/plain")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(providerResponse.statusCode()).isEqualTo(200);
    assertThat(metrics.statusCode()).isEqualTo(200);
    assertThat(metrics.headers().firstValue("content-type").orElse("")).contains("text/plain");
    assertThat(metrics.body()).contains("# TYPE modelgate_requests_total counter");
    assertThat(metrics.body()).contains("modelgate_requests_total");
    assertThat(metrics.body()).contains("modelgate_provider_requests_total");
    assertThat(metrics.body()).contains("modelgate_provider_concurrency_limit");
    assertThat(metrics.body()).contains("modelgate_plugin_concurrency_limit");
    assertThat(metrics.body()).contains("modelgate_jvm_thread_count");
  }

  @Test
  void metricsEndpointExposesDimensionalProviderModelEndpointLabels() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, "{\"id\":\"chatcmpl-dimensional\",\"choices\":[]}"));
    app = GatewayApp.create().start(0);

    HttpResponse<String> providerResponse = client.send(
        chatRequest("{}", "{\"model\":\"gpt-4o-mini\"}"),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> metrics = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/metrics"))
            .header("accept", "text/plain")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(providerResponse.statusCode()).isEqualTo(200);
    assertThat(metrics.body()).contains("modelgate_provider_request_duration_seconds_count{");
    assertThat(metrics.body()).contains("provider=\"openai\"");
    assertThat(metrics.body()).contains("model=\"gpt-4o-mini\"");
    assertThat(metrics.body()).contains("endpoint=\"/v1/chat/completions\"");
    assertThat(metrics.body()).contains("status_class=\"2xx\"");
    assertThat(metrics.body()).contains("cache_status=\"DISABLED\"");
  }

  @Test
  void metricsEndpointTracksActualProviderAttemptsAcrossRetries() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    startProviderServer(exchange -> {
      int call = providerCalls.incrementAndGet();
      if (call == 1) {
        respond(exchange, 500, "{\"error\":\"retry\"}");
        return;
      }
      respond(exchange, 200, "{\"id\":\"chatcmpl-retried\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = "{\"retry\":{\"attempts\":1,\"on_status_codes\":[500]}}";
    HttpResponse<String> response = client.send(
        chatRequest(config, "{\"model\":\"gpt-4o-mini\"}"),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> metrics = get("http://localhost:" + app.port() + "/metrics");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(providerCalls).hasValue(2);
    assertThat(metrics.body()).contains("\"provider_requests\":1");
    assertThat(metrics.body()).contains("\"provider_attempts\":2");
  }

  @Test
  void metricsEndpointTracksProviderAttemptsAcrossFallbackTargets() throws Exception {
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger backupCalls = new AtomicInteger();
    HttpServer primaryServer = startAdditionalProviderServer(exchange -> {
      primaryCalls.incrementAndGet();
      respond(exchange, 500, "{\"error\":\"primary\"}");
    });
    HttpServer backupServer = startAdditionalProviderServer(exchange -> {
      backupCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-backup\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "retry": {"attempts": 1, "on_status_codes": [500]},
          "strategy": {"mode": "fallback", "on_status_codes": [500]},
          "targets": [
            {"name": "primary", "provider": "openai", "api_key": "sk-primary", "custom_host": "%s"},
            {"name": "backup", "provider": "openai", "api_key": "sk-backup", "custom_host": "%s"}
          ]
        }
        """.formatted(serverBaseUrl(primaryServer), serverBaseUrl(backupServer)).replace("\n", "");
    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> metrics = get("http://localhost:" + app.port() + "/metrics");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(primaryCalls).hasValue(2);
    assertThat(backupCalls).hasValue(1);
    assertThat(metrics.body()).contains("\"provider_requests\":1");
    assertThat(metrics.body()).contains("\"provider_attempts\":3");
  }

  @Test
  void metricsEndpointTracksRoutedOutputGuardrailFinalStatus() throws Exception {
    HttpServer providerServer = startAdditionalProviderServer(
        exchange -> respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"Apple\"}}]}"));
    app = GatewayApp.create().start(0);

    String config = """
        {
          "strategy": {"mode": "loadbalance"},
          "output_guardrails": [
            {"default.contains": {"operator": "none", "words": ["Apple"]}, "deny": true}
          ],
          "targets": [
            {"name": "active", "provider": "openai", "api_key": "sk-active", "custom_host": "%s", "weight": 1}
          ]
        }
        """.formatted(serverBaseUrl(providerServer)).replace("\n", "");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> metrics = get("http://localhost:" + app.port() + "/metrics");

    assertThat(response.statusCode()).isEqualTo(446);
    assertThat(response.body()).contains("after_request_hooks");
    assertThat(metrics.body()).contains("\"total_requests\":1");
    assertThat(metrics.body()).contains("\"status_2xx\":0");
    assertThat(metrics.body()).contains("\"status_4xx\":1");
    assertThat(metrics.body()).contains("\"provider_requests\":1");
  }

  @Test
  void invalidLoadbalanceConfigReturnsBadRequestAndMetrics4xxWithoutProviderCall() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    startProviderServer(exchange -> {
      providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-invalid-loadbalance\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "strategy": {"mode": "loadbalance"},
          "targets": [
            {"name": "primary", "provider": "openai", "api_key": "sk-primary", "custom_host": "%s", "weight": 0}
          ]
        }
        """.formatted(providerBaseUrl()).replace("\n", "");
    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> metrics = get("http://localhost:" + app.port() + "/metrics");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("loadbalance");
    assertThat(providerCalls).hasValue(0);
    assertThat(metrics.body()).contains("\"total_requests\":1");
    assertThat(metrics.body()).contains("\"status_4xx\":1");
    assertThat(metrics.body()).contains("\"validation_failures\":1");
    assertThat(metrics.body()).contains("\"provider_requests\":0");
  }

  @Test
  void exposesPublicUiRoutesAndRedirectsPublicWithoutTrailingSlash() throws Exception {
    app = GatewayApp.create().start(0);
    String baseUrl = "http://localhost:" + app.port();

    HttpResponse<String> publicRedirect = get(baseUrl + "/public");
    HttpResponse<String> publicIndex = get(baseUrl + "/public/");
    HttpResponse<String> publicLogs = get(baseUrl + "/public/logs");

    assertThat(publicRedirect.statusCode()).isEqualTo(302);
    assertThat(publicRedirect.headers().firstValue("location")).contains("/public/");
    assertThat(publicIndex.statusCode()).isEqualTo(200);
    assertThat(publicIndex.headers().firstValue("content-type").orElse("")).contains("text/html");
    assertThat(publicIndex.body()).contains("ModelGate");
    assertThat(publicIndex.body()).contains("/log/stream");
    assertThat(publicLogs.statusCode()).isEqualTo(200);
    assertThat(publicLogs.body()).isEqualTo(publicIndex.body());
  }

  @Test
  void logStreamExposesServerSentEventHandshake() throws Exception {
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = get("http://localhost:" + app.port() + "/log/stream");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("content-type")).contains("text/event-stream");
    assertThat(response.headers().firstValue("cache-control")).contains("no-cache");
    assertThat(response.headers().firstValue("x-accel-buffering")).contains("no");
    assertThat(response.body()).contains("event: connected");
    assertThat(response.body()).contains("data:");
  }

  @Test
  void logStreamIncludesRecentProviderRequestLogEvents() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, "{\"id\":\"chatcmpl-log\",\"choices\":[]}"));
    app = GatewayApp.create().start(0);

    HttpResponse<String> providerResponse = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> logStream = get("http://localhost:" + app.port() + "/log/stream");

    assertThat(providerResponse.statusCode()).isEqualTo(200);
    assertThat(logStream.statusCode()).isEqualTo(200);
    assertThat(logStream.body()).contains("event: connected");
    assertThat(logStream.body()).contains("event: log");
    assertThat(logStream.body()).contains("\"method\":\"POST\"");
    assertThat(logStream.body()).contains("\"endpoint\":\"/v1/chat/completions\"");
    assertThat(logStream.body()).contains("\"status\":200");
    assertThat(logStream.body()).contains("\"duration\":");
    assertThat(logStream.body()).contains("\"requestOptions\":[");
    assertThat(logStream.body()).contains("\"lastUsedOptionIndex\":0");
    assertThat(logStream.body()).contains("\"response\":{\"id\":\"chatcmpl-log\"");
  }

  @Test
  void logStreamFollowModeReceivesFutureProviderRequestLogEvents() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, "{\"id\":\"chatcmpl-live-log\",\"choices\":[]}"));
    app = GatewayApp.create().start(0);

    CompletableFuture<HttpResponse<InputStream>> streamFuture = client.sendAsync(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/log/stream?follow=true"))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofInputStream());
    HttpResponse<InputStream> stream = streamFuture.get(2, TimeUnit.SECONDS);
    String connected = readUntilContains(stream.body(), "event: connected", Duration.ofSeconds(2));

    HttpResponse<String> providerResponse = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    String liveEvent = readUntilContains(stream.body(), "chatcmpl-live-log", Duration.ofSeconds(2));
    stream.body().close();

    assertThat(stream.statusCode()).isEqualTo(200);
    assertThat(stream.headers().firstValue("content-type")).contains("text/event-stream");
    assertThat(connected).contains("event: connected");
    assertThat(providerResponse.statusCode()).isEqualTo(200);
    assertThat(liveEvent).contains("event: log");
    assertThat(liveEvent).contains("\"endpoint\":\"/v1/chat/completions\"");
  }

  @Test
  void logStreamIncludesTraceCorrelatedOtlpSpanMetadata() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, "{\"id\":\"chatcmpl-span\",\"choices\":[]}"));
    app = GatewayApp.create().start(0);

    HttpResponse<String> providerResponse = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .header("x-modelgate-trace-id", "trace-observe-1")
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> logStream = get("http://localhost:" + app.port() + "/log/stream");

    assertThat(providerResponse.statusCode()).isEqualTo(200);
    assertThat(logStream.body()).contains("\"traceId\":\"trace-observe-1\"");
    assertThat(logStream.body()).contains("\"type\":\"otlp_span\"");
    assertThat(logStream.body()).contains("\"name\":\"provider_request openai\"");
    assertThat(logStream.body()).contains("\"gen_ai.operation.name\"");
    assertThat(logStream.body()).contains("\"http.route\"");
  }

  @Test
  void logStreamAndMetricsIncludeNormalizedProviderResponseMetadata() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, """
        {
          "id": "chatcmpl-observe",
          "model": "gpt-observe",
          "provider": "openai",
          "usage": {
            "prompt_tokens": 9,
            "completion_tokens": 4,
            "total_tokens": 13,
            "prompt_tokens_details": {"cached_tokens": 2},
            "completion_tokens_details": {"reasoning_tokens": 3}
          },
          "choices": [
            {
              "finish_reason": "tool_calls",
              "message": {
                "role": "assistant",
                "content": "",
                "refusal": "cannot comply",
                "tool_calls": [{"id": "tool-1", "type": "function", "function": {"name": "lookup", "arguments": "{}"}}]
              },
              "safetyRatings": [{"category": "policy", "blocked": true}]
            }
          ]
        }
        """));
    app = GatewayApp.create().start(0);
    String config = """
        {
          "pricing": {
            "currency": "USD",
            "models": {
              "gpt-observe": {
                "input_per_million": 1000000,
                "cached_input_per_million": 1000000,
                "output_per_million": 1000000,
                "reasoning_per_million": 1000000
              }
            }
          }
        }
        """.replaceAll("\\s+", " ");

    HttpResponse<String> providerResponse = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-observe\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> logStream = get("http://localhost:" + app.port() + "/log/stream");
    HttpResponse<String> metrics = get("http://localhost:" + app.port() + "/metrics");

    assertThat(providerResponse.statusCode()).isEqualTo(200);
    assertThat(logStream.body()).contains("\"tokenUsage\":{\"prompt_tokens\":9,\"completion_tokens\":4,\"total_tokens\":13");
    assertThat(logStream.body()).contains("\"cached_tokens\":2");
    assertThat(logStream.body()).contains("\"reasoning_tokens\":3");
    assertThat(logStream.body()).contains("\"finishReason\":\"tool_calls\"");
    assertThat(logStream.body()).contains("\"toolCallCount\":1");
    assertThat(logStream.body()).contains("\"refused\":true");
    assertThat(logStream.body()).contains("\"refusal\":\"cannot comply\"");
    assertThat(logStream.body()).contains("\"safetyFlagged\":true");
    assertThat(logStream.body()).contains("\"cost\":{\"currency\":\"USD\"");
    assertThat(logStream.body()).contains("\"input_cost_usd\":\"7\"");
    assertThat(logStream.body()).contains("\"cached_input_cost_usd\":\"2\"");
    assertThat(logStream.body()).contains("\"output_cost_usd\":\"1\"");
    assertThat(logStream.body()).contains("\"reasoning_cost_usd\":\"3\"");
    assertThat(logStream.body()).contains("\"total_cost_usd\":\"13\"");
    assertThat(logStream.body()).contains("\"source\":\"config\"");
    assertThat(logStream.body()).contains("\"latency\":{\"total_ms\":");
    assertThat(logStream.body()).contains("\"provider_ms\":");
    assertThat(logStream.body()).contains("\"request_read_ms\":");
    assertThat(logStream.body()).contains("\"input_guardrails_ms\":");
    assertThat(logStream.body()).contains("\"cache_lookup_ms\":");
    assertThat(logStream.body()).contains("\"output_guardrails_ms\":");
    assertThat(logStream.body()).contains("\"response_write_ms\":");
    assertThat(metrics.body()).contains("\"provider_prompt_tokens\":9");
    assertThat(metrics.body()).contains("\"provider_completion_tokens\":4");
    assertThat(metrics.body()).contains("\"provider_total_tokens\":13");
  }

  @Test
  void gatewayExportsProviderTelemetryToConfiguredOtlpCollector() throws Exception {
    List<String> collectorPaths = new java.util.concurrent.CopyOnWriteArrayList<>();
    List<Headers> collectorHeaders = new java.util.concurrent.CopyOnWriteArrayList<>();
    List<byte[]> collectorBodies = new java.util.concurrent.CopyOnWriteArrayList<>();
    HttpServer collector = startAdditionalProviderServer(exchange -> {
      collectorPaths.add(exchange.getRequestURI().getPath());
      collectorHeaders.add(exchange.getRequestHeaders());
      collectorBodies.add(exchange.getRequestBody().readAllBytes());
      exchange.sendResponseHeaders(200, 0);
      exchange.close();
    });
    startProviderServer(exchange -> respond(exchange, 200, "{\"id\":\"chatcmpl-otlp\",\"choices\":[]}"));
    app = GatewayApp.create(new GatewayRuntimeConfig(
        256,
        Set.of(),
        new OpenTelemetrySettings(
            true,
            URI.create(serverBaseUrl(collector) + "/v1/traces"),
            "http/protobuf",
            Map.of("x-collector-auth", "test-token"),
            "modelgate-test",
            Duration.ofSeconds(2)))).start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .header("x-modelgate-trace-id", "modelgate-trace-otlp")
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-otlp\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(awaitPaths(
        collectorPaths,
        List.of("/v1/traces", "/v1/metrics", "/v1/logs"),
        Duration.ofSeconds(5))).isTrue();
    assertThat(collectorPaths).contains("/v1/traces", "/v1/metrics", "/v1/logs");
    assertThat(collectorHeaders).allSatisfy(headers -> {
      assertThat(headers.getFirst("x-collector-auth")).isEqualTo("test-token");
      assertThat(headers.getFirst("content-type")).contains("application/x-protobuf");
    });
    assertThat(collectorBodies).allSatisfy(body -> assertThat(body).isNotEmpty());
    List<String> bodyText = collectorBodies.stream()
        .map(body -> new String(body, StandardCharsets.ISO_8859_1))
        .toList();
    assertThat(bodyText).anySatisfy(body -> assertThat(body).contains("modelgate-test"));
    assertThat(bodyText).anySatisfy(body -> assertThat(body).contains("openai"));
    assertThat(bodyText).anySatisfy(body -> assertThat(body).contains("modelgate.gateway.request"));
  }

  @Test
  void logStreamRedactsProviderCredentialHeadersBeyondAuthorization() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, "{\"id\":\"chatcmpl-redacted\",\"choices\":[]}"));
    app = GatewayApp.create().start(0);
    String config = """
        {
          "provider": "azure-openai",
          "api_key": "sk-azure-secret",
          "custom_host": "%s"
        }
        """.formatted(providerBaseUrl()).replaceAll("\\s+", " ");

    HttpResponse<String> providerResponse = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> logStream = get("http://localhost:" + app.port() + "/log/stream");

    assertThat(providerResponse.statusCode()).isEqualTo(200);
    assertThat(logStream.body()).contains("\"id\":\"chatcmpl-redacted\"");
    assertThat(logStream.body()).doesNotContain("sk-azure-secret");
    assertThat(logStream.body()).doesNotContain("\"api-key\"");
  }

  @Test
  void logStreamRedactsXApiKeyProviderCredentialHeaders() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, "{\"id\":\"chatcmpl-x-api-redacted\",\"choices\":[]}"));
    app = GatewayApp.create().start(0);
    String config = """
        {
          "provider": "azure-ai",
          "api_key": "sk-x-api-secret",
          "custom_host": "%s/anthropic",
          "azure_foundry_url": "%s/anthropic"
        }
        """.formatted(providerBaseUrl(), providerBaseUrl()).replaceAll("\\s+", " ");

    HttpResponse<String> providerResponse = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> logStream = get("http://localhost:" + app.port() + "/log/stream");

    assertThat(providerResponse.statusCode()).isEqualTo(200);
    assertThat(logStream.body()).contains("\"id\":\"chatcmpl-x-api-redacted\"");
    assertThat(logStream.body()).doesNotContain("sk-x-api-secret");
    assertThat(logStream.body()).doesNotContain("\"x-api-key\"");
  }

  @Test
  void streamingChatCompletionForwardsProviderSseBeforeProviderCompletes() throws Exception {
    CountDownLatch firstChunkWritten = new CountDownLatch(1);
    CountDownLatch allowProviderToFinish = new CountDownLatch(1);
    startProviderServer(exchange -> {
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write("data: {\"choices\":[{\"delta\":{\"content\":\"delta-1\"}}]}\n\n"
            .getBytes(StandardCharsets.UTF_8));
        output.flush();
        firstChunkWritten.countDown();
        assertThat(allowProviderToFinish.await(2, TimeUnit.SECONDS)).isTrue();
        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
      }
    });
    app = GatewayApp.create().start(0);

    CompletableFuture<HttpResponse<InputStream>> responseFuture = client.sendAsync(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\",\"stream\":true}"))
            .build(),
        HttpResponse.BodyHandlers.ofInputStream());

    assertThat(firstChunkWritten.await(2, TimeUnit.SECONDS)).isTrue();
    HttpResponse<InputStream> response = awaitStreamingResponse(responseFuture, allowProviderToFinish);
    String firstChunk = readUntilContains(response.body(), "delta-1", Duration.ofSeconds(2));
    allowProviderToFinish.countDown();
    String remainingBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("content-type")).contains("text/event-stream");
    assertThat(response.headers().firstValue("x-modelgate-retry-attempt-count")).contains("0");
    assertThat(firstChunk).contains("delta-1");
    assertThat(remainingBody).contains("[DONE]");
  }

  @Test
  void streamingChatCompletionSuppressesHookResultsWhenStrictOpenAiComplianceDefaultsTrue() throws Exception {
    CountDownLatch firstChunkWritten = new CountDownLatch(1);
    CountDownLatch allowProviderToFinish = new CountDownLatch(1);
    startProviderServer(exchange -> {
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write("data: {\"choices\":[{\"delta\":{\"content\":\"provider-delta\"}}]}\n\n"
            .getBytes(StandardCharsets.UTF_8));
        output.flush();
        firstChunkWritten.countDown();
        assertThat(allowProviderToFinish.await(2, TimeUnit.SECONDS)).isTrue();
        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
      }
    });
    app = GatewayApp.create().start(0);
    String config = """
        {
          "before_request_hooks": [
            {
              "id": "stream-input",
              "checks": [
                {"id": "contains", "parameters": {"operator": "any", "words": ["hello"]}}
              ]
            }
          ]
        }
        """.replaceAll("\\s+", " ");

    CompletableFuture<HttpResponse<InputStream>> responseFuture = client.sendAsync(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"model\":\"gpt-4o-mini\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
            .build(),
        HttpResponse.BodyHandlers.ofInputStream());

    assertThat(firstChunkWritten.await(2, TimeUnit.SECONDS)).isTrue();
    HttpResponse<InputStream> response = awaitStreamingResponse(responseFuture, allowProviderToFinish);
    String providerChunk = readUntilContains(response.body(), "provider-delta", Duration.ofSeconds(2));
    allowProviderToFinish.countDown();
    String remainingBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("content-type")).contains("text/event-stream");
    assertThat(providerChunk).doesNotContain("hook_results", "stream-input", "default.contains");
    assertThat(providerChunk).contains("provider-delta");
    assertThat(remainingBody).contains("[DONE]");
  }

  @Test
  void streamingChatCompletionEmitsBeforeRequestHookResultsWhenStrictOpenAiComplianceFalse() throws Exception {
    CountDownLatch firstChunkWritten = new CountDownLatch(1);
    CountDownLatch allowProviderToFinish = new CountDownLatch(1);
    startProviderServer(exchange -> {
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write("data: {\"choices\":[{\"delta\":{\"content\":\"provider-delta\"}}]}\n\n"
            .getBytes(StandardCharsets.UTF_8));
        output.flush();
        firstChunkWritten.countDown();
        assertThat(allowProviderToFinish.await(2, TimeUnit.SECONDS)).isTrue();
        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
      }
    });
    app = GatewayApp.create().start(0);
    String config = """
        {
          "strict_open_ai_compliance": false,
          "before_request_hooks": [
            {
              "id": "stream-input",
              "checks": [
                {"id": "contains", "parameters": {"operator": "any", "words": ["hello"]}}
              ]
            }
          ]
        }
        """.replaceAll("\\s+", " ");

    CompletableFuture<HttpResponse<InputStream>> responseFuture = client.sendAsync(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"model\":\"gpt-4o-mini\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
            .build(),
        HttpResponse.BodyHandlers.ofInputStream());

    assertThat(firstChunkWritten.await(2, TimeUnit.SECONDS)).isTrue();
    HttpResponse<InputStream> response = awaitStreamingResponse(responseFuture, allowProviderToFinish);
    String hookChunk = readUntilContains(response.body(), "default.contains", Duration.ofSeconds(2));
    String providerChunk = readUntilContains(response.body(), "provider-delta", Duration.ofSeconds(2));
    allowProviderToFinish.countDown();
    String remainingBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("content-type")).contains("text/event-stream");
    assertThat(hookChunk).contains("\"before_request_hooks\"", "stream-input", "default.contains");
    assertThat(hookChunk).doesNotContain("provider-delta");
    assertThat(providerChunk).contains("provider-delta");
    assertThat(remainingBody).contains("[DONE]");
  }

  @Test
  void streamingMessagesEmitsHookResultsEventWhenStrictOpenAiComplianceHeaderIsFalse() throws Exception {
    CountDownLatch firstChunkWritten = new CountDownLatch(1);
    CountDownLatch allowProviderToFinish = new CountDownLatch(1);
    startProviderServer(exchange -> {
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write("data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"provider-message\"}}\n\n"
            .getBytes(StandardCharsets.UTF_8));
        output.flush();
        firstChunkWritten.countDown();
        assertThat(allowProviderToFinish.await(2, TimeUnit.SECONDS)).isTrue();
        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
      }
    });
    app = GatewayApp.create().start(0);
    String config = """
        {
          "provider": "anthropic",
          "api_key": "sk-test",
          "custom_host": "%s",
          "before_request_hooks": [
            {
              "id": "messages-input",
              "checks": [
                {"id": "contains", "parameters": {"operator": "any", "words": ["hello"]}}
              ]
            }
          ]
        }
        """.formatted(providerBaseUrl()).replaceAll("\\s+", " ");

    CompletableFuture<HttpResponse<InputStream>> responseFuture = client.sendAsync(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/messages"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .header("x-modelgate-strict-open-ai-compliance", "false")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"model\":\"claude-3-5-sonnet-latest\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
            .build(),
        HttpResponse.BodyHandlers.ofInputStream());

    assertThat(firstChunkWritten.await(2, TimeUnit.SECONDS)).isTrue();
    HttpResponse<InputStream> response = awaitStreamingResponse(responseFuture, allowProviderToFinish);
    String hookChunk = readUntilContains(response.body(), "default.contains", Duration.ofSeconds(2));
    String providerChunk = readUntilContains(response.body(), "provider-message", Duration.ofSeconds(2));
    allowProviderToFinish.countDown();
    String remainingBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(hookChunk).startsWith("event: hook_results\n");
    assertThat(hookChunk).contains("\"before_request_hooks\"", "messages-input", "default.contains");
    assertThat(providerChunk).contains("provider-message");
    assertThat(remainingBody).contains("[DONE]");
  }

  @Test
  void streamingChatCompletionRetriesInitialFailureBeforeForwardingProviderSse() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    CountDownLatch firstChunkWritten = new CountDownLatch(1);
    CountDownLatch allowProviderToFinish = new CountDownLatch(1);
    startProviderServer(exchange -> {
      int call = providerCalls.incrementAndGet();
      if (call == 1) {
        respond(exchange, 429, "{\"error\":\"rate limited\"}");
        return;
      }
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write("data: {\"choices\":[{\"delta\":{\"content\":\"retry-delta\"}}]}\n\n"
            .getBytes(StandardCharsets.UTF_8));
        output.flush();
        firstChunkWritten.countDown();
        assertThat(allowProviderToFinish.await(2, TimeUnit.SECONDS)).isTrue();
        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
      }
    });
    app = GatewayApp.create().start(0);
    String config = "{\"retry\":{\"attempts\":1,\"on_status_codes\":[429]}}";

    CompletableFuture<HttpResponse<InputStream>> responseFuture = client.sendAsync(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\",\"stream\":true}"))
            .build(),
        HttpResponse.BodyHandlers.ofInputStream());

    assertThat(firstChunkWritten.await(2, TimeUnit.SECONDS)).isTrue();
    HttpResponse<InputStream> response = awaitStreamingResponse(responseFuture, allowProviderToFinish);
    String firstChunk = readUntilContains(response.body(), "retry-delta", Duration.ofSeconds(2));
    allowProviderToFinish.countDown();
    String remainingBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("content-type")).contains("text/event-stream");
    assertThat(response.headers().firstValue("x-modelgate-retry-attempt-count")).contains("1");
    assertThat(response.headers().firstValue("x-portkey-retry-attempt-count")).contains("1");
    assertThat(response.headers().firstValue("x-modelgate-last-used-option-index")).contains("0");
    assertThat(response.headers().firstValue("x-portkey-last-used-option-index")).contains("0");
    assertThat(firstChunk).contains("retry-delta");
    assertThat(firstChunk).doesNotContain("rate limited");
    assertThat(remainingBody).contains("[DONE]");
    assertThat(providerCalls).hasValue(2);
  }

  @Test
  void loadbalanceStreamingChatCompletionForwardsSelectedProviderSseBeforeProviderCompletes() throws Exception {
    AtomicInteger disabledCalls = new AtomicInteger();
    HttpServer disabledServer = startAdditionalProviderServer(exchange -> {
      disabledCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"disabled\",\"choices\":[]}");
    });
    CountDownLatch firstChunkWritten = new CountDownLatch(1);
    CountDownLatch allowProviderToFinish = new CountDownLatch(1);
    HttpServer activeServer = startAdditionalProviderServer(exchange -> {
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write("data: {\"choices\":[{\"delta\":{\"content\":\"routed-delta\"}}]}\n\n"
            .getBytes(StandardCharsets.UTF_8));
        output.flush();
        firstChunkWritten.countDown();
        assertThat(allowProviderToFinish.await(2, TimeUnit.SECONDS)).isTrue();
        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
      }
    });
    app = GatewayApp.create().start(0);
    String config = """
        {
          "strategy": {"mode": "loadbalance"},
          "targets": [
            {"name": "disabled", "provider": "openai", "custom_host": "%s", "api_key": "sk-disabled", "weight": 0},
            {"name": "active", "provider": "openai", "custom_host": "%s", "api_key": "sk-active", "weight": 1}
          ]
        }
        """.formatted(serverBaseUrl(disabledServer), serverBaseUrl(activeServer)).replaceAll("\\s+", " ");

    CompletableFuture<HttpResponse<InputStream>> responseFuture = client.sendAsync(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\",\"stream\":true}"))
            .build(),
        HttpResponse.BodyHandlers.ofInputStream());

    assertThat(firstChunkWritten.await(2, TimeUnit.SECONDS)).isTrue();
    HttpResponse<InputStream> response = awaitStreamingResponse(responseFuture, allowProviderToFinish);
    String firstChunk = readUntilContains(response.body(), "routed-delta", Duration.ofSeconds(2));
    allowProviderToFinish.countDown();
    String remainingBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("content-type")).contains("text/event-stream");
    assertThat(response.headers().firstValue("x-modelgate-last-used-option-index")).contains("1");
    assertThat(response.headers().firstValue("x-portkey-last-used-option-index")).contains("1");
    assertThat(response.headers().firstValue("x-modelgate-retry-attempt-count")).contains("0");
    assertThat(firstChunk).contains("routed-delta");
    assertThat(remainingBody).contains("[DONE]");
    assertThat(disabledCalls).hasValue(0);
  }

  @Test
  void conditionalStreamingChatCompletionForwardsSelectedProviderSseBeforeProviderCompletes() throws Exception {
    AtomicInteger standardCalls = new AtomicInteger();
    AtomicInteger premiumCalls = new AtomicInteger();
    HttpServer standardServer = startAdditionalProviderServer(exchange -> {
      standardCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-standard\",\"choices\":[]}");
    });
    CountDownLatch firstChunkWritten = new CountDownLatch(1);
    CountDownLatch allowProviderToFinish = new CountDownLatch(1);
    HttpServer premiumServer = startAdditionalProviderServer(exchange -> {
      premiumCalls.incrementAndGet();
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write("data: {\"choices\":[{\"delta\":{\"content\":\"conditional-delta\"}}]}\n\n"
            .getBytes(StandardCharsets.UTF_8));
        output.flush();
        firstChunkWritten.countDown();
        assertThat(allowProviderToFinish.await(2, TimeUnit.SECONDS)).isTrue();
        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
      }
    });
    app = GatewayApp.create().start(0);
    String config = """
        {
          "strategy": {
            "mode": "conditional",
            "conditions": [
              {"query": {"metadata.tier": {"$eq": "gold"}, "params.model": {"$regex": "gpt-4o"}}, "then": "premium"}
            ],
            "default": "standard"
          },
          "targets": [
            {"name": "standard", "provider": "openai", "api_key": "sk-standard", "custom_host": "%s"},
            {"name": "premium", "provider": "openai", "api_key": "sk-premium", "custom_host": "%s"}
          ]
        }
        """.formatted(serverBaseUrl(standardServer), serverBaseUrl(premiumServer)).replaceAll("\\s+", " ");

    CompletableFuture<HttpResponse<InputStream>> responseFuture = client.sendAsync(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-modelgate-metadata", "{\"tier\":\"gold\"}")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\",\"stream\":true}"))
            .build(),
        HttpResponse.BodyHandlers.ofInputStream());

    assertThat(firstChunkWritten.await(2, TimeUnit.SECONDS)).isTrue();
    HttpResponse<InputStream> response = awaitStreamingResponse(responseFuture, allowProviderToFinish);
    String firstChunk = readUntilContains(response.body(), "conditional-delta", Duration.ofSeconds(2));
    allowProviderToFinish.countDown();
    String remainingBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("content-type")).contains("text/event-stream");
    assertThat(response.headers().firstValue("x-modelgate-last-used-option-index")).contains("1");
    assertThat(response.headers().firstValue("x-portkey-last-used-option-index")).contains("1");
    assertThat(response.headers().firstValue("x-modelgate-retry-attempt-count")).contains("0");
    assertThat(firstChunk).contains("conditional-delta");
    assertThat(remainingBody).contains("[DONE]");
    assertThat(standardCalls).hasValue(0);
    assertThat(premiumCalls).hasValue(1);
  }

  @Test
  void chatCompletionsRouteAppliesPortkeyRequestValidation() throws Exception {
    app = GatewayApp.create().start(0);
    String baseUrl = "http://localhost:" + app.port();

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("x-modelgate-config/x-modelgate-provider");
  }

  @Test
  void chatCompletionsRouteForwardsToConfiguredProvider() throws Exception {
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerAuthorization = new AtomicReference<>();
    AtomicReference<String> providerBody = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getPath());
      providerAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"id\":\"chatcmpl-test\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);
    String baseUrl = "http://localhost:" + app.port();

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-portkey-provider", "openai")
            .header("x-portkey-custom-host", providerBaseUrl())
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"chatcmpl-test\"");
    assertThat(response.headers().firstValue("x-portkey-retry-attempt-count")).contains("0");
    assertThat(providerPath).hasValue("/v1/chat/completions");
    assertThat(providerAuthorization).hasValue("Bearer sk-test");
    assertThat(providerBody).hasValue("{\"model\":\"gpt-4o-mini\"}");
  }

  @Test
  void chatCompletionsRouteTransformsAnthropicMessagesRequestAndResponse() throws Exception {
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerApiKey = new AtomicReference<>();
    AtomicReference<String> providerVersion = new AtomicReference<>();
    AtomicReference<String> providerBody = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getPath());
      providerApiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
      providerVersion.set(exchange.getRequestHeaders().getFirst("Anthropic-Version"));
      providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, """
          {
            "id": "msg_123",
            "type": "message",
            "role": "assistant",
            "model": "claude-3-5-sonnet-latest",
            "content": [{"type": "text", "text": "Hello from Claude"}],
            "stop_reason": "end_turn",
            "usage": {"input_tokens": 5, "output_tokens": 7}
          }
          """);
    });
    app = GatewayApp.create().start(0);
    String config = """
        {
          "provider": "anthropic",
          "api_key": "sk-anthropic",
          "custom_host": "%s"
        }
        """.formatted(providerBaseUrl()).replace("\n", "");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("""
                {
                  "model": "claude-3-5-sonnet-latest",
                  "messages": [
                    {"role": "system", "content": "Be terse."},
                    {"role": "user", "content": "Hello"}
                  ],
                  "max_tokens": 64
                }
                """))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"object\":\"chat.completion\"");
    assertThat(response.body()).contains("\"provider\":\"anthropic\"");
    assertThat(response.body()).contains("\"content\":\"Hello from Claude\"");
    assertThat(response.body()).contains("\"finish_reason\":\"stop\"");
    assertThat(response.body()).contains("\"total_tokens\":12");
    assertThat(providerPath).hasValue("/messages");
    assertThat(providerApiKey).hasValue("sk-anthropic");
    assertThat(providerVersion).hasValue("2023-06-01");
    assertThat(providerBody.get()).contains("\"system\":[{\"type\":\"text\",\"text\":\"Be terse.\"}]");
    assertThat(providerBody.get()).contains("\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]");
  }

  @Test
  void messagesCountTokensRouteUsesAnthropicPathTransformAndRequestTypeForHooks() throws Exception {
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerApiKey = new AtomicReference<>();
    AtomicReference<String> providerVersion = new AtomicReference<>();
    AtomicReference<String> providerBody = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getPath());
      providerApiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
      providerVersion.set(exchange.getRequestHeaders().getFirst("Anthropic-Version"));
      providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"input_tokens\":12}");
    });
    app = GatewayApp.create().start(0);
    String config = """
        {
          "provider": "anthropic",
          "api_key": "sk-anthropic",
          "custom_host": "%s",
          "input_guardrails": [
            {
              "id": "count-tokens-type",
              "checks": [
                {"id": "allowedRequestTypes", "parameters": {"allowedTypes": ["messagesCountTokens"]}}
              ]
            }
          ]
        }
        """.formatted(providerBaseUrl()).replace("\n", "");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/messages/count_tokens"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("""
                {
                  "model": "claude-3-5-sonnet-latest",
                  "system": "Be terse.",
                  "messages": [
                    {"role": "user", "content": "Hello"}
                  ],
                  "stop_sequences": ["END"],
                  "top_k": 5,
                  "thinking": {"type": "enabled", "budget_tokens": 256},
                  "metadata": {"user_id": "user-a"}
                }
                """))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"input_tokens\":12");
    assertThat(response.body()).contains("\"currentRequestType\":\"messagesCountTokens\"");
    assertThat(providerPath).hasValue("/messages/count_tokens");
    assertThat(providerApiKey).hasValue("sk-anthropic");
    assertThat(providerVersion).hasValue("2023-06-01");
    assertThat(providerBody.get()).contains("\"system\":\"Be terse.\"");
    assertThat(providerBody.get()).contains("\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]");
    assertThat(providerBody.get()).contains("\"stop_sequences\":[\"END\"]");
    assertThat(providerBody.get()).contains("\"top_k\":5");
    assertThat(providerBody.get()).contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":256}");
    assertThat(providerBody.get()).contains("\"metadata\":{\"user_id\":\"user-a\"}");
  }

  @Test
  void messagesCountTokensRouteSupportsVertexAnthropicCountTokens() throws Exception {
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerBody = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getPath());
      providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"input_tokens\":9}");
    });
    app = GatewayApp.create().start(0);
    String config = """
        {
          "provider": "vertex-ai",
          "api_key": "vertex-token",
          "custom_host": "%s",
          "vertex_project_id": "project-a",
          "vertex_region": "us-east5",
          "input_guardrails": [
            {
              "id": "vertex-count-tokens-type",
              "checks": [
                {"id": "allowedRequestTypes", "parameters": {"allowedTypes": ["messagesCountTokens"]}}
              ]
            }
          ]
        }
        """.formatted(providerBaseUrl()).replace("\n", "");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/messages/count_tokens"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("""
                {
                  "model": "anthropic.claude-3-5-sonnet-v2@20241022",
                  "system": "Be concise.",
                  "messages": [{"role": "user", "content": "Count this"}],
                  "max_tokens": 32
                }
                """))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"input_tokens\":9");
    assertThat(response.body()).contains("\"currentRequestType\":\"messagesCountTokens\"");
    assertThat(providerPath).hasValue("/v1/projects/project-a/locations/us-east5/publishers/anthropic/models/count-tokens:rawPredict");
    assertThat(providerBody.get()).contains("\"model\":\"claude-3-5-sonnet-v2@20241022\"");
    assertThat(providerBody.get()).contains("\"system\":\"Be concise.\"");
  }

  @Test
  void messagesCountTokensRouteSupportsBedrockAnthropicCountTokens() throws Exception {
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerAuthorization = new AtomicReference<>();
    AtomicReference<String> providerBody = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getRawPath());
      providerAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"inputTokens\":9}");
    });
    app = GatewayApp.create().start(0);
    String config = """
        {
          "provider": "bedrock",
          "api_key": "bedrock-key",
          "custom_host": "%s",
          "aws_region": "us-west-2",
          "aws_auth_type": "apiKey",
          "input_guardrails": [
            {
              "id": "bedrock-count-tokens-type",
              "checks": [
                {"id": "allowedRequestTypes", "parameters": {"allowedTypes": ["messagesCountTokens"]}}
              ]
            }
          ]
        }
        """.formatted(providerBaseUrl()).replace("\n", "");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/messages/count_tokens"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("""
                {
                  "model": "us.anthropic.claude-3-sonnet-20240229-v1:0",
                  "system": "Be concise.",
                  "messages": [{"role": "user", "content": "Count this"}],
                  "max_tokens": 32,
                  "anthropic_version": "2023-06-01"
                }
                """))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    String decodedInvokeBody = new String(
        Base64.getDecoder()
            .decode(readTree(providerBody.get()).at("/input/invokeModel/body").asText()),
        StandardCharsets.UTF_8);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"input_tokens\":9");
    assertThat(response.body()).contains("\"currentRequestType\":\"messagesCountTokens\"");
    assertThat(providerPath).hasValue("/model/anthropic.claude-3-sonnet-20240229-v1%3A0/count-tokens");
    assertThat(providerAuthorization).hasValue("Bearer bedrock-key");
    assertThat(decodedInvokeBody).contains("\"system\":\"Be concise.\"");
    assertThat(decodedInvokeBody).contains("\"messages\":[{\"role\":\"user\",\"content\":\"Count this\"}]");
    assertThat(decodedInvokeBody).contains("\"anthropic_version\":\"2023-06-01\"");
    assertThat(decodedInvokeBody).contains("\"max_tokens\":32");
    assertThat(decodedInvokeBody).doesNotContain("\"model\"");
  }

  @Test
  void chatCompletionsRouteTransformsAnthropicErrorResponseAndPreservesStatus() throws Exception {
    startProviderServer(exchange -> respond(exchange, 400, """
        {
          "type": "error",
          "error": {
            "type": "invalid_request_error",
            "message": "bad request"
          }
        }
        """));
    app = GatewayApp.create().start(0);
    String config = """
        {
          "provider": "anthropic",
          "api_key": "sk-anthropic",
          "custom_host": "%s"
        }
        """.formatted(providerBaseUrl()).replace("\n", "");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("""
                {
                  "model": "claude-3-5-sonnet-latest",
                  "messages": [{"role": "user", "content": "Hello"}],
                  "max_tokens": 64
                }
                """))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"provider\":\"anthropic\"");
    assertThat(response.body()).contains("\"message\":\"anthropic error: bad request\"");
    assertThat(response.body()).contains("\"type\":\"invalid_request_error\"");
  }

  @Test
  void chatCompletionsRouteForwardsHeadersNamedByConfig() throws Exception {
    AtomicReference<String> providerTenant = new AtomicReference<>();
    AtomicReference<String> providerMetadata = new AtomicReference<>();
    AtomicReference<String> providerModelgateConfig = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerTenant.set(exchange.getRequestHeaders().getFirst("X-Client-Tenant"));
      providerMetadata.set(exchange.getRequestHeaders().getFirst("X-Modelgate-Metadata"));
      providerModelgateConfig.set(exchange.getRequestHeaders().getFirst("X-Modelgate-Config"));
      respond(exchange, 200, "{\"id\":\"chatcmpl-forward-config\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);
    String config = """
        {
          "provider": "openai",
          "api_key": "sk-config",
          "custom_host": "%s",
          "forward_headers": ["x-client-tenant", "x-modelgate-metadata"]
        }
        """.formatted(providerBaseUrl()).replaceAll("\\s+", " ");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-client-tenant", "acme")
            .header("x-modelgate-metadata", "{\"tier\":\"gold\"}")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"chatcmpl-forward-config\"");
    assertThat(providerTenant).hasValue("acme");
    assertThat(providerMetadata).hasValue("{\"tier\":\"gold\"}");
    assertThat(providerModelgateConfig).hasValue(null);
  }

  @Test
  void chatCompletionsRouteAcceptsModelgateProviderHeadersAndEmitsModelgateResponseHeaders() throws Exception {
    AtomicReference<String> providerPath = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getPath());
      respond(exchange, 200, "{\"id\":\"chatcmpl-modelgate\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"chatcmpl-modelgate\"");
    assertThat(response.headers().firstValue("x-modelgate-retry-attempt-count")).contains("0");
    assertThat(response.headers().firstValue("x-portkey-retry-attempt-count")).contains("0");
    assertThat(providerPath).hasValue("/v1/chat/completions");
  }

  @Test
  void chatCompletionsRouteEchoesModelgateTraceIdInCompatibilityResponseHeaders() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, "{\"id\":\"chatcmpl-trace\",\"choices\":[]}"));
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .header("x-modelgate-trace-id", "trace-modelgate-123")
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("x-modelgate-trace-id")).contains("trace-modelgate-123");
    assertThat(response.headers().firstValue("x-portkey-trace-id")).contains("trace-modelgate-123");
    assertThat(response.headers().firstValue("x-modelgate-last-used-option-index")).contains("0");
    assertThat(response.headers().firstValue("x-portkey-last-used-option-index")).contains("0");
    assertThat(response.headers().firstValue("x-modelgate-provider")).contains("openai");
    assertThat(response.headers().firstValue("x-portkey-provider")).contains("openai");
  }

  @Test
  void modelgateTraceIdTakesPrecedenceOverPortkeyCompatibilityTraceId() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, "{\"id\":\"chatcmpl-trace-precedence\",\"choices\":[]}"));
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .header("x-portkey-trace-id", "trace-legacy")
            .header("x-modelgate-trace-id", "trace-modelgate")
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("x-modelgate-trace-id")).contains("trace-modelgate");
    assertThat(response.headers().firstValue("x-portkey-trace-id")).contains("trace-modelgate");
    assertThat(response.headers().firstValue("x-modelgate-last-used-option-index")).contains("0");
    assertThat(response.headers().firstValue("x-portkey-last-used-option-index")).contains("0");
  }

  @Test
  void modelgateHeadersTakePrecedenceOverPortkeyCompatibilityHeaders() throws Exception {
    AtomicInteger legacyCalls = new AtomicInteger();
    AtomicInteger modelgateCalls = new AtomicInteger();
    HttpServer legacyServer = startAdditionalProviderServer(exchange -> {
      legacyCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"legacy\"}");
    });
    HttpServer modelgateServer = startAdditionalProviderServer(exchange -> {
      modelgateCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"modelgate\"}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-portkey-provider", "openai")
            .header("x-portkey-custom-host", serverBaseUrl(legacyServer))
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", serverBaseUrl(modelgateServer))
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"modelgate\"");
    assertThat(legacyCalls).hasValue(0);
    assertThat(modelgateCalls).hasValue(1);
  }

  @Test
  void embeddingsRouteForwardsToConfiguredProviderPath() throws Exception {
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerAuthorization = new AtomicReference<>();
    AtomicReference<String> providerBody = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getPath());
      providerAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"object\":\"list\",\"data\":[{\"embedding\":[0.1]}]}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/embeddings"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-embed")
            .header("x-portkey-provider", "openai")
            .header("x-portkey-custom-host", providerBaseUrl())
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"text-embedding-3-small\",\"input\":\"hello\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"embedding\":[0.1]");
    assertThat(providerPath).hasValue("/v1/embeddings");
    assertThat(providerAuthorization).hasValue("Bearer sk-embed");
    assertThat(providerBody).hasValue("{\"model\":\"text-embedding-3-small\",\"input\":\"hello\"}");
  }

  @Test
  void completionsRouteForwardsToConfiguredProviderPath() throws Exception {
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerBody = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getPath());
      providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"id\":\"cmpl-test\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-complete")
            .header("x-portkey-provider", "openai")
            .header("x-portkey-custom-host", providerBaseUrl())
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-3.5-turbo-instruct\",\"prompt\":\"hello\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"cmpl-test\"");
    assertThat(providerPath).hasValue("/v1/completions");
    assertThat(providerBody).hasValue("{\"model\":\"gpt-3.5-turbo-instruct\",\"prompt\":\"hello\"}");
  }

  @Test
  void genericV1PostRouteForwardsUnknownJsonEndpointToConfiguredProviderPath() throws Exception {
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerQuery = new AtomicReference<>();
    AtomicReference<String> providerBody = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getPath());
      providerQuery.set(exchange.getRequestURI().getRawQuery());
      providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"id\":\"resp-test\",\"output\":[]}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/responses?include=usage"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-response")
            .header("x-portkey-provider", "openai")
            .header("x-portkey-custom-host", providerBaseUrl())
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\",\"input\":\"hello\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"resp-test\"");
    assertThat(providerPath).hasValue("/v1/responses");
    assertThat(providerQuery).hasValue("include=usage");
    assertThat(providerBody).hasValue("{\"model\":\"gpt-4o-mini\",\"input\":\"hello\"}");
  }

  @Test
  void proxyRouteStripsProxyPrefixAndPreservesQueryWhenForwarding() throws Exception {
    AtomicReference<String> providerMethod = new AtomicReference<>();
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerQuery = new AtomicReference<>();
    AtomicReference<String> providerBody = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerMethod.set(exchange.getRequestMethod());
      providerPath.set(exchange.getRequestURI().getPath());
      providerQuery.set(exchange.getRequestURI().getRawQuery());
      providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"id\":\"proxy-test\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create(
                "http://localhost:" + app.port() + "/v1/proxy/chat/completions?api-version=2024-02-01"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-proxy")
            .header("x-portkey-provider", "openai")
            .header("x-portkey-custom-host", providerBaseUrl() + "/v1")
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"proxy-test\"");
    assertThat(providerMethod).hasValue("POST");
    assertThat(providerPath).hasValue("/v1/chat/completions");
    assertThat(providerQuery).hasValue("api-version=2024-02-01");
    assertThat(providerBody).hasValue("{\"model\":\"gpt-4o-mini\"}");
  }

  @Test
  void proxyRouteForwardsSafeHeadersWithoutPortkeyControlHeaders() throws Exception {
    AtomicReference<String> providerTrace = new AtomicReference<>();
    AtomicReference<String> providerPortkeyProvider = new AtomicReference<>();
    AtomicReference<String> providerPortkeyCustomHost = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerTrace.set(exchange.getRequestHeaders().getFirst("X-Client-Trace"));
      providerPortkeyProvider.set(exchange.getRequestHeaders().getFirst("X-Portkey-Provider"));
      providerPortkeyCustomHost.set(exchange.getRequestHeaders().getFirst("X-Portkey-Custom-Host"));
      respond(exchange, 200, "{\"id\":\"proxy-headers\"}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/proxy/responses"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-proxy")
            .header("x-client-trace", "trace-123")
            .header("x-portkey-provider", "openai")
            .header("x-portkey-custom-host", providerBaseUrl() + "/v1")
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(providerTrace).hasValue("trace-123");
    assertThat(providerPortkeyProvider).hasValue(null);
    assertThat(providerPortkeyCustomHost).hasValue(null);
  }

  @Test
  void proxyRouteStripsModelgateControlHeadersUnlessExplicitlyForwarded() throws Exception {
    AtomicReference<String> providerModelgateMetadata = new AtomicReference<>();
    AtomicReference<String> providerModelgateProvider = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerModelgateMetadata.set(exchange.getRequestHeaders().getFirst("X-Modelgate-Metadata"));
      providerModelgateProvider.set(exchange.getRequestHeaders().getFirst("X-Modelgate-Provider"));
      respond(exchange, 200, "{\"id\":\"proxy-modelgate-headers\"}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/proxy/responses"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-proxy")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl() + "/v1")
            .header("x-modelgate-metadata", "{\"tenant\":\"acme\"}")
            .header("x-modelgate-forward-headers", "x-modelgate-metadata")
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(providerModelgateMetadata).hasValue("{\"tenant\":\"acme\"}");
    assertThat(providerModelgateProvider).hasValue(null);
  }

  @Test
  void proxyRouteCanExplicitlyForwardNamedPortkeyHeaders() throws Exception {
    AtomicReference<String> providerMetadata = new AtomicReference<>();
    AtomicReference<String> providerPortkeyProvider = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerMetadata.set(exchange.getRequestHeaders().getFirst("X-Portkey-Metadata"));
      providerPortkeyProvider.set(exchange.getRequestHeaders().getFirst("X-Portkey-Provider"));
      respond(exchange, 200, "{\"id\":\"proxy-forward-headers\"}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/proxy/responses"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-proxy")
            .header("x-portkey-provider", "openai")
            .header("x-portkey-custom-host", providerBaseUrl() + "/v1")
            .header("x-portkey-metadata", "{\"tenant\":\"acme\"}")
            .header("x-portkey-forward-headers", " x-portkey-metadata ")
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(providerMetadata).hasValue("{\"tenant\":\"acme\"}");
    assertThat(providerPortkeyProvider).hasValue(null);
  }

  @Test
  void genericV1GetRouteForwardsUnknownEndpointAndQueryToConfiguredProviderPath() throws Exception {
    AtomicReference<String> providerMethod = new AtomicReference<>();
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerQuery = new AtomicReference<>();
    AtomicReference<String> providerAuthorization = new AtomicReference<>();
    AtomicReference<String> providerContentType = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerMethod.set(exchange.getRequestMethod());
      providerPath.set(exchange.getRequestURI().getPath());
      providerQuery.set(exchange.getRequestURI().getRawQuery());
      providerAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      providerContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      respond(exchange, 200, "{\"object\":\"list\",\"data\":[]}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/files?purpose=assistants"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-list")
            .header("x-portkey-provider", "openai")
            .header("x-portkey-custom-host", providerBaseUrl())
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"object\":\"list\"");
    assertThat(providerMethod).hasValue("GET");
    assertThat(providerPath).hasValue("/v1/files");
    assertThat(providerQuery).hasValue("purpose=assistants");
    assertThat(providerAuthorization).hasValue("Bearer sk-list");
    assertThat(providerContentType).hasValue(null);
  }

  @Test
  void vertexListFilesReturnsUnsupportedForVertexWithoutProviderCall() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    startProviderServer(exchange -> {
      providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"unexpected\":true}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/files"))
            .header("x-modelgate-config",
                """
                    {
                      "provider": "vertex-ai",
                      "api_key": "vertex-token",
                      "custom_host": "%s",
                      "vertex_project_id": "project-a",
                      "vertex_region": "us-central1"
                    }
                    """.formatted(providerBaseUrl()).replace("\n", ""))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(response.body()).contains("\"message\":\"listFiles is not supported by Google Vertex AI\"");
    assertThat(response.body()).contains("\"status\":\"failure\"");
    assertThat(response.body()).contains("\"provider\":\"google-vertex-ai\"");
    assertThat(providerCalls).hasValue(0);
  }

  @Test
  void vertexRetrieveFileUsesStorageHeadAndReturnsOpenAiFileMetadata() throws Exception {
    AtomicReference<String> providerMethod = new AtomicReference<>();
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerAuthorization = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerMethod.set(exchange.getRequestMethod());
      providerPath.set(exchange.getRequestURI().getPath());
      providerAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.getResponseHeaders().put("Content-Length", java.util.List.of("1234"));
      exchange.getResponseHeaders().put("Last-Modified", java.util.List.of("Wed, 01 Jan 2025 00:00:00 GMT"));
      exchange.getResponseHeaders().put("Date", java.util.List.of("Wed, 01 Jan 2025 00:00:01 GMT"));
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    app = GatewayApp.create().start(0);

    String fileId = "gs%3A%2F%2Fbucket-a%2Ffolder%2Ffile.jsonl";
    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/files/" + fileId))
            .header("x-modelgate-config",
                """
                    {
                      "provider": "vertex-ai",
                      "api_key": "vertex-token",
                      "custom_host": "%s",
                      "vertex_project_id": "project-a",
                      "vertex_region": "us-central1"
                    }
                    """.formatted(providerBaseUrl()).replace("\n", ""))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    JsonNode body = readTree(response.body());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(providerMethod).hasValue("HEAD");
    assertThat(providerPath).hasValue("/bucket-a/folder/file.jsonl");
    assertThat(providerAuthorization).hasValue("Bearer vertex-token");
    assertThat(body.path("bytes").asInt()).isEqualTo(1234);
    assertThat(body.path("id").asText()).isEqualTo(fileId);
    assertThat(body.path("filename").asText()).isEqualTo("file.jsonl");
    assertThat(body.path("purpose").isNull()).isTrue();
    assertThat(body.path("status").asText()).isEqualTo("processed");
    assertThat(body.path("updatedAt").asLong()).isGreaterThan(0);
    assertThat(body.path("createdAt").asLong()).isGreaterThan(0);
  }

  @Test
  void vertexBatchOutputFetchesBatchThenStoragePredictionsFile() throws Exception {
    List<String> providerMethods = new ArrayList<>();
    List<String> providerPaths = new ArrayList<>();
    List<String> providerAuthorizations = new ArrayList<>();
    startProviderServer(exchange -> {
      providerMethods.add(exchange.getRequestMethod());
      providerPaths.add(exchange.getRequestURI().getPath());
      providerAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
      if (exchange.getRequestURI().getPath().endsWith("/batchPredictionJobs/batch-1")) {
        respond(exchange, 200, """
            {
              "name": "projects/project-a/locations/us-central1/batchPredictionJobs/batch-1",
              "model": "publishers/google/models/gemini-1.5-pro",
              "state": "JOB_STATE_SUCCEEDED",
              "inputConfig": {"gcsSource": {"uris": ["gs://bucket-a/input/requests.jsonl"]}},
              "outputInfo": {"gcsOutputDirectory": "gs://bucket-a/output/job/"}
            }
            """);
        return;
      }
      respond(exchange, 200, """
          {"requestId":"row-1","response":{"modelVersion":"gemini-1.5-pro","candidates":[{"content":{"parts":[{"text":"batch ok"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":3,"totalTokenCount":5}}}
          """);
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/batches/batch-1/output"))
            .header("x-modelgate-config",
                """
                    {
                      "provider": "vertex-ai",
                      "api_key": "vertex-token",
                      "custom_host": "%s",
                      "vertex_project_id": "project-a",
                      "vertex_region": "us-central1"
                    }
                    """.formatted(providerBaseUrl()).replace("\n", ""))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"custom_id\":\"row-1\"");
    assertThat(response.body()).contains("\"status_code\":200");
    assertThat(response.body()).contains("\"content\":\"batch ok\"");
    assertThat(response.body()).contains("\"error\":null");
    assertThat(providerMethods).containsExactly("GET", "GET");
    assertThat(providerPaths).containsExactly(
        "/v1/projects/project-a/locations/us-central1/batchPredictionJobs/batch-1",
        "/bucket-a/output/job/predictions.jsonl");
    assertThat(providerAuthorizations).containsExactly("Bearer vertex-token", "Bearer vertex-token");
  }

  @Test
  void vertexEmbeddingBatchOutputFetchesEmbeddingShardAndReturnsEmbeddingRows() throws Exception {
    List<String> providerMethods = new ArrayList<>();
    List<String> providerPaths = new ArrayList<>();
    startProviderServer(exchange -> {
      providerMethods.add(exchange.getRequestMethod());
      providerPaths.add(exchange.getRequestURI().getPath());
      if (exchange.getRequestURI().getPath().endsWith("/batchPredictionJobs/embed-batch-1")) {
        respond(exchange, 200, """
            {
              "name": "projects/project-a/locations/us-central1/batchPredictionJobs/embed-batch-1",
              "model": "publishers/google/models/text-embedding-005",
              "state": "JOB_STATE_SUCCEEDED",
              "inputConfig": {"gcsSource": {"uris": ["gs://bucket-a/input/embeddings.jsonl"]}},
              "outputInfo": {"gcsOutputDirectory": "gs://bucket-a/output/embed-job/"}
            }
            """);
        return;
      }
      respond(exchange, 200, """
          {"requestId":"embed-row-1","response":{"predictions":[{"embeddings":{"values":[0.1,0.2],"statistics":{"token_count":4}}}],"model":"text-embedding-005"}}
          """);
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/batches/embed-batch-1/output"))
            .header("x-modelgate-config",
                """
                    {
                      "provider": "vertex-ai",
                      "api_key": "vertex-token",
                      "custom_host": "%s",
                      "vertex_project_id": "project-a",
                      "vertex_region": "us-central1"
                    }
                    """.formatted(providerBaseUrl()).replace("\n", ""))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"custom_id\":\"embed-row-1\"");
    assertThat(response.body()).contains("\"object\":\"embedding\"");
    assertThat(response.body()).contains("\"embedding\":[0.1,0.2]");
    assertThat(response.body()).contains("\"total_tokens\":4");
    assertThat(providerMethods).containsExactly("GET", "GET");
    assertThat(providerPaths).containsExactly(
        "/v1/projects/project-a/locations/us-central1/batchPredictionJobs/embed-batch-1",
        "/bucket-a/output/embed-job/000000000000.jsonl");
  }

  @Test
  void vertexBatchOutputPreservesFailedRowsAsOpenAiBatchErrors() throws Exception {
    startProviderServer(exchange -> {
      if (exchange.getRequestURI().getPath().endsWith("/batchPredictionJobs/batch-errors")) {
        respond(exchange, 200, """
            {
              "name": "projects/project-a/locations/us-central1/batchPredictionJobs/batch-errors",
              "model": "publishers/google/models/gemini-1.5-pro",
              "state": "JOB_STATE_SUCCEEDED",
              "inputConfig": {"gcsSource": {"uris": ["gs://bucket-a/input/requests.jsonl"]}},
              "outputInfo": {"gcsOutputDirectory": "gs://bucket-a/output/error-job/"}
            }
            """);
        return;
      }
      respond(exchange, 200, """
          {"requestId":"bad-row","status":"{\\"code\\":400,\\"message\\":\\"bad input\\"}"}
          """);
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/batches/batch-errors/output"))
            .header("x-modelgate-config",
                """
                    {
                      "provider": "vertex-ai",
                      "api_key": "vertex-token",
                      "custom_host": "%s",
                      "vertex_project_id": "project-a",
                      "vertex_region": "us-central1"
                    }
                    """.formatted(providerBaseUrl()).replace("\n", ""))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"custom_id\":\"bad-row\"");
    assertThat(response.body()).contains("\"status_code\":400");
    assertThat(response.body()).contains("\"body\":null");
    assertThat(response.body()).contains("\"error\":{\"code\":400,\"message\":\"bad input\"}");
  }

  @Test
  void azureAiBatchOutputFetchesBatchThenOutputFileContent() throws Exception {
    List<String> providerMethods = new ArrayList<>();
    List<String> providerPaths = new ArrayList<>();
    List<String> providerQueries = new ArrayList<>();
    List<String> providerAuthorizations = new ArrayList<>();
    startProviderServer(exchange -> {
      providerMethods.add(exchange.getRequestMethod());
      providerPaths.add(exchange.getRequestURI().getPath());
      providerQueries.add(exchange.getRequestURI().getRawQuery());
      providerAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
      if (exchange.getRequestURI().getPath().equals("/openai/batches/batch-azure-1")) {
        respond(exchange, 200, "{\"id\":\"batch-azure-1\",\"object\":\"batch\",\"status\":\"completed\",\"output_file_id\":\"file-azure-output\"}");
        return;
      }
      respond(exchange, 200, "{\"custom_id\":\"row-1\",\"response\":{\"status_code\":200,\"body\":{\"choices\":[]}},\"error\":null}\n");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/batches/batch-azure-1/output"))
            .header("x-modelgate-config",
                """
                    {
                      "provider": "azure-ai",
                      "api_key": "sk-azure-ai",
                      "custom_host": "%s/openai",
                      "azure_api_version": "2024-05-01-preview"
                    }
                    """.formatted(providerBaseUrl()).replace("\n", ""))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"custom_id\":\"row-1\"");
    assertThat(providerMethods).containsExactly("GET", "GET");
    assertThat(providerPaths).containsExactly(
        "/openai/batches/batch-azure-1",
        "/openai/files/file-azure-output/content");
    assertThat(providerQueries).containsExactly(
        "api-version=2024-05-01-preview",
        "api-version=2024-05-01-preview");
    assertThat(providerAuthorizations).containsExactly("Bearer sk-azure-ai", "Bearer sk-azure-ai");
  }

  @Test
  void azureAiBatchOutputFallsBackToErrorFileContent() throws Exception {
    List<String> providerPaths = new ArrayList<>();
    startProviderServer(exchange -> {
      providerPaths.add(exchange.getRequestURI().getPath());
      if (exchange.getRequestURI().getPath().equals("/openai/batches/batch-azure-error")) {
        respond(exchange, 200, "{\"id\":\"batch-azure-error\",\"object\":\"batch\",\"status\":\"failed\",\"error_file_id\":\"file-azure-errors\"}");
        return;
      }
      respond(exchange, 200, "{\"custom_id\":\"bad-row\",\"response\":null,\"error\":{\"message\":\"bad input\"}}\n");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/batches/batch-azure-error/output"))
            .header("x-modelgate-config",
                """
                    {
                      "provider": "azure-ai",
                      "api_key": "sk-azure-ai",
                      "custom_host": "%s/openai",
                      "azure_api_version": "2024-05-01-preview"
                    }
                    """.formatted(providerBaseUrl()).replace("\n", ""))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"custom_id\":\"bad-row\"");
    assertThat(response.body()).contains("\"message\":\"bad input\"");
    assertThat(providerPaths).containsExactly(
        "/openai/batches/batch-azure-error",
        "/openai/files/file-azure-errors/content");
  }

  @Test
  void openAiBatchOutputFetchesBatchThenOutputFileContent() throws Exception {
    List<String> providerPaths = new ArrayList<>();
    List<String> providerAuthorizations = new ArrayList<>();
    startProviderServer(exchange -> {
      providerPaths.add(exchange.getRequestURI().getPath());
      providerAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
      if (exchange.getRequestURI().getPath().equals("/v1/batches/batch-openai-1")) {
        respond(exchange, 200, "{\"id\":\"batch-openai-1\",\"object\":\"batch\",\"status\":\"completed\",\"output_file_id\":\"file-openai-output\"}");
        return;
      }
      respond(exchange, 200, "{\"custom_id\":\"row-1\",\"response\":{\"status_code\":200,\"body\":{\"choices\":[]}},\"error\":null}\n");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/batches/batch-openai-1/output"))
            .header("x-modelgate-config",
                """
                    {
                      "provider": "openai",
                      "api_key": "sk-openai",
                      "custom_host": "%s/v1"
                    }
                    """.formatted(providerBaseUrl()).replace("\n", ""))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"custom_id\":\"row-1\"");
    assertThat(providerPaths).containsExactly(
        "/v1/batches/batch-openai-1",
        "/v1/files/file-openai-output/content");
    assertThat(providerAuthorizations).containsExactly("Bearer sk-openai", "Bearer sk-openai");
  }

  @Test
  void openAiBatchOutputFallsBackToErrorFileContent() throws Exception {
    List<String> providerPaths = new ArrayList<>();
    startProviderServer(exchange -> {
      providerPaths.add(exchange.getRequestURI().getPath());
      if (exchange.getRequestURI().getPath().equals("/v1/batches/batch-openai-error")) {
        respond(exchange, 200, "{\"id\":\"batch-openai-error\",\"object\":\"batch\",\"status\":\"failed\",\"error_file_id\":\"file-openai-errors\"}");
        return;
      }
      respond(exchange, 200, "{\"custom_id\":\"bad-row\",\"response\":null,\"error\":{\"message\":\"bad input\"}}\n");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/batches/batch-openai-error/output"))
            .header("x-modelgate-config",
                """
                    {
                      "provider": "openai",
                      "api_key": "sk-openai",
                      "custom_host": "%s/v1"
                    }
                    """.formatted(providerBaseUrl()).replace("\n", ""))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"custom_id\":\"bad-row\"");
    assertThat(response.body()).contains("\"message\":\"bad input\"");
    assertThat(providerPaths).containsExactly(
        "/v1/batches/batch-openai-error",
        "/v1/files/file-openai-errors/content");
  }

  @Test
  void openAiBatchOutputReturnsErrorsObjectWhenNoOutputFileExists() throws Exception {
    List<String> providerPaths = new ArrayList<>();
    startProviderServer(exchange -> {
      providerPaths.add(exchange.getRequestURI().getPath());
      respond(exchange, 200, "{\"id\":\"batch-openai-errors\",\"object\":\"batch\",\"status\":\"failed\",\"errors\":{\"object\":\"list\",\"data\":[{\"message\":\"validation failed\"}]}}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/batches/batch-openai-errors/output"))
            .header("x-modelgate-config",
                """
                    {
                      "provider": "openai",
                      "api_key": "sk-openai",
                      "custom_host": "%s/v1"
                    }
                    """.formatted(providerBaseUrl()).replace("\n", ""))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"object\":\"list\"");
    assertThat(response.body()).contains("\"message\":\"validation failed\"");
    assertThat(providerPaths).containsExactly("/v1/batches/batch-openai-errors");
  }

  @Test
  void azureOpenAiBatchOutputFetchesBatchThenOutputFileContentWithoutDeploymentPrefix() throws Exception {
    List<String> providerPaths = new ArrayList<>();
    List<String> providerQueries = new ArrayList<>();
    startProviderServer(exchange -> {
      providerPaths.add(exchange.getRequestURI().getPath());
      providerQueries.add(exchange.getRequestURI().getRawQuery());
      if (exchange.getRequestURI().getPath().equals("/openai/batches/batch-azure-openai-1")) {
        respond(exchange, 200, "{\"id\":\"batch-azure-openai-1\",\"object\":\"batch\",\"status\":\"completed\",\"output_file_id\":\"file-azure-openai-output\"}");
        return;
      }
      respond(exchange, 200, "{\"custom_id\":\"row-1\",\"response\":{\"status_code\":200,\"body\":{\"choices\":[]}},\"error\":null}\n");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/batches/batch-azure-openai-1/output"))
            .header("x-modelgate-config",
                """
                    {
                      "provider": "azure-openai",
                      "api_key": "sk-azure-openai",
                      "custom_host": "%s/openai",
                      "deployment_id": "gpt-4o",
                      "azure_api_version": "2024-02-01"
                    }
                    """.formatted(providerBaseUrl()).replace("\n", ""))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"custom_id\":\"row-1\"");
    assertThat(providerPaths).containsExactly(
        "/openai/batches/batch-azure-openai-1",
        "/openai/files/file-azure-openai-output/content");
    assertThat(providerQueries).containsExactly(
        "api-version=2024-02-01",
        "api-version=2024-02-01");
  }

  @Test
  void genericV1DeleteRouteForwardsUnknownEndpointToConfiguredProviderPath() throws Exception {
    AtomicReference<String> providerMethod = new AtomicReference<>();
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerAuthorization = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerMethod.set(exchange.getRequestMethod());
      providerPath.set(exchange.getRequestURI().getPath());
      providerAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      respond(exchange, 200, "{\"id\":\"file-abc\",\"deleted\":true}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/files/file-abc"))
            .header("authorization", "Bearer sk-delete")
            .header("x-portkey-provider", "openai")
            .header("x-portkey-custom-host", providerBaseUrl())
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"deleted\":true");
    assertThat(providerMethod).hasValue("DELETE");
    assertThat(providerPath).hasValue("/v1/files/file-abc");
    assertThat(providerAuthorization).hasValue("Bearer sk-delete");
  }

  @Test
  void genericV1DeleteRoutePreservesJsonRequestBodyWhenPresent() throws Exception {
    AtomicReference<String> providerMethod = new AtomicReference<>();
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerContentType = new AtomicReference<>();
    AtomicReference<String> providerBody = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerMethod.set(exchange.getRequestMethod());
      providerPath.set(exchange.getRequestURI().getPath());
      providerContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"id\":\"batch-abc\",\"deleted\":true}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/batches/batch-abc"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-delete")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .method("DELETE", HttpRequest.BodyPublishers.ofString("{\"reason\":\"cleanup\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(providerMethod).hasValue("DELETE");
    assertThat(providerPath).hasValue("/v1/batches/batch-abc");
    assertThat(providerContentType).hasValue("application/json");
    assertThat(providerBody).hasValue("{\"reason\":\"cleanup\"}");
  }

  @Test
  void genericV1HeadAndOptionsRoutesForwardUnknownEndpointsToConfiguredProviderPath() throws Exception {
    List<String> providerMethods = new ArrayList<>();
    List<String> providerPaths = new ArrayList<>();
    startProviderServer(exchange -> {
      providerMethods.add(exchange.getRequestMethod());
      providerPaths.add(exchange.getRequestURI().getPath());
      exchange.getResponseHeaders().put("allow", java.util.List.of("GET,POST,OPTIONS,HEAD"));
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    });
    app = GatewayApp.create().start(0);

    String baseUrl = "http://localhost:" + app.port();
    HttpResponse<String> headResponse = client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/v1/files/file-abc/content"))
            .header("authorization", "Bearer sk-head")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> optionsResponse = client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/v1/files/file-abc/content"))
            .header("authorization", "Bearer sk-options")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(headResponse.statusCode()).isEqualTo(204);
    assertThat(optionsResponse.statusCode()).isEqualTo(204);
    assertThat(optionsResponse.headers().firstValue("allow")).contains("GET,POST,OPTIONS,HEAD");
    assertThat(providerMethods).containsExactly("HEAD", "OPTIONS");
    assertThat(providerPaths).containsExactly("/v1/files/file-abc/content", "/v1/files/file-abc/content");
  }

  @Test
  void audioRouteForwardsRawRequestBytesWithoutUtf8Corruption() throws Exception {
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerContentType = new AtomicReference<>();
    AtomicReference<byte[]> providerBody = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getPath());
      providerContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      providerBody.set(exchange.getRequestBody().readAllBytes());
      respond(exchange, 200, "{\"text\":\"transcribed\"}");
    });
    app = GatewayApp.create().start(0);
    byte[] audioBytes = new byte[] {0x52, 0x49, 0x46, 0x46, 0x00, (byte) 0xff, (byte) 0xfe, 0x7f};

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/audio/transcriptions"))
            .header("content-type", "audio/wav")
            .header("authorization", "Bearer sk-audio")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .POST(HttpRequest.BodyPublishers.ofByteArray(audioBytes))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(providerPath).hasValue("/v1/audio/transcriptions");
    assertThat(providerContentType).hasValue("audio/wav");
    assertThat(providerBody).hasValue(audioBytes);
  }

  @Test
  void multipartImageRouteForwardsRawRequestBytesAndBoundary() throws Exception {
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerContentType = new AtomicReference<>();
    AtomicReference<byte[]> providerBody = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getPath());
      providerContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      providerBody.set(exchange.getRequestBody().readAllBytes());
      respond(exchange, 200, "{\"created\":1,\"data\":[]}");
    });
    app = GatewayApp.create().start(0);
    byte[] multipartBody = new byte[] {
        '-', '-', 'm', 'g', '\r', '\n',
        'C', 'o', 'n', 't', 'e', 'n', 't', '-', 'D', 'i', 's', 'p', 'o', 's', 'i', 't', 'i', 'o', 'n',
        ':', ' ', 'f', 'o', 'r', 'm', '-', 'd', 'a', 't', 'a', ';', ' ', 'n', 'a', 'm', 'e', '=',
        '"', 'i', 'm', 'a', 'g', 'e', '"', ';', ' ', 'f', 'i', 'l', 'e', 'n', 'a', 'm', 'e', '=',
        '"', 'i', 'm', 'a', 'g', 'e', '.', 'p', 'n', 'g', '"', '\r', '\n', '\r', '\n',
        (byte) 0x89, 'P', 'N', 'G', 0x00, (byte) 0xff, '\r', '\n',
        '-', '-', 'm', 'g', '-', '-'
    };

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/images/edits"))
            .header("content-type", "multipart/form-data; boundary=mg")
            .header("authorization", "Bearer sk-image")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(providerPath).hasValue("/v1/images/edits");
    assertThat(providerContentType).hasValue("multipart/form-data; boundary=mg");
    assertThat(providerBody).hasValue(multipartBody);
  }

  @Test
  void binaryFileContentRouteForwardsRawResponseBytesWithoutUtf8Corruption() throws Exception {
    byte[] providerBytes = new byte[] {0x25, 0x50, 0x44, 0x46, 0x00, (byte) 0xff, (byte) 0xfe, 0x7f};
    AtomicReference<String> providerPath = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getPath());
      exchange.getResponseHeaders().put("content-type", java.util.List.of("application/octet-stream"));
      exchange.getResponseHeaders().put("content-disposition", java.util.List.of("attachment; filename=\"file-abc.pdf\""));
      exchange.getResponseHeaders().put("x-request-id", java.util.List.of("provider-request-123"));
      exchange.sendResponseHeaders(200, providerBytes.length);
      exchange.getResponseBody().write(providerBytes);
      exchange.close();
    });
    app = GatewayApp.create().start(0);

    HttpResponse<byte[]> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/files/file-abc/content"))
            .header("authorization", "Bearer sk-file")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(providerPath).hasValue("/v1/files/file-abc/content");
    assertThat(response.headers().firstValue("content-type")).contains("application/octet-stream");
    assertThat(response.headers().firstValue("content-disposition"))
        .contains("attachment; filename=\"file-abc.pdf\"");
    assertThat(response.headers().firstValue("x-request-id")).contains("provider-request-123");
    assertThat(response.body()).isEqualTo(providerBytes);
  }

  @Test
  void promptPatchRouteForwardsJsonBodyToConfiguredProviderPath() throws Exception {
    AtomicReference<String> providerMethod = new AtomicReference<>();
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerBody = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerMethod.set(exchange.getRequestMethod());
      providerPath.set(exchange.getRequestURI().getPath());
      providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"id\":\"prompt-123\",\"object\":\"prompt\"}");
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/prompts/prompt-123"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-prompt")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"metadata\":{\"team\":\"evals\"}}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"prompt-123\"");
    assertThat(providerMethod).hasValue("PATCH");
    assertThat(providerPath).hasValue("/v1/prompts/prompt-123");
    assertThat(providerBody).hasValue("{\"metadata\":{\"team\":\"evals\"}}");
  }

  @Test
  void endpointFamiliesForwardExplicitMethodsAndPathsToConfiguredProvider() throws Exception {
    List<String> providerRequests = new ArrayList<>();
    startProviderServer(exchange -> {
      providerRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
      respond(exchange, 200, "{\"ok\":true}");
    });
    app = GatewayApp.create().start(0);
    String gatewayBaseUrl = "http://localhost:" + app.port();

    HttpResponse<String> image = sendJsonEndpoint(gatewayBaseUrl, "/v1/images/generations", "POST");
    HttpResponse<String> audioSpeech = sendJsonEndpoint(gatewayBaseUrl, "/v1/audio/speech", "POST");
    HttpResponse<String> batchCreate = sendJsonEndpoint(gatewayBaseUrl, "/v1/batches", "POST");
    HttpResponse<String> batchCancel = sendJsonEndpoint(gatewayBaseUrl, "/v1/batches/batch-123/cancel", "POST");
    HttpResponse<String> fineTuneCreate = sendJsonEndpoint(gatewayBaseUrl, "/v1/fine_tuning/jobs", "POST");
    HttpResponse<String> fineTuneCancel = sendJsonEndpoint(gatewayBaseUrl, "/v1/fine_tuning/jobs/ft-123/cancel", "POST");
    HttpResponse<String> promptCreate = sendJsonEndpoint(gatewayBaseUrl, "/v1/prompts", "POST");
    HttpResponse<String> promptDelete = client.send(
        HttpRequest.newBuilder(URI.create(gatewayBaseUrl + "/v1/prompts/prompt-123"))
            .header("authorization", "Bearer sk-family")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(List.of(
        image.statusCode(),
        audioSpeech.statusCode(),
        batchCreate.statusCode(),
        batchCancel.statusCode(),
        fineTuneCreate.statusCode(),
        fineTuneCancel.statusCode(),
        promptCreate.statusCode(),
        promptDelete.statusCode()))
        .containsOnly(200);
    assertThat(providerRequests).containsExactly(
        "POST /v1/images/generations",
        "POST /v1/audio/speech",
        "POST /v1/batches",
        "POST /v1/batches/batch-123/cancel",
        "POST /v1/fine_tuning/jobs",
        "POST /v1/fine_tuning/jobs/ft-123/cancel",
        "POST /v1/prompts",
        "DELETE /v1/prompts/prompt-123");
  }

  @Test
  void realtimeWebSocketRouteProxiesClientMessagesToConfiguredProvider() throws Exception {
    AtomicReference<String> providerAuthorization = new AtomicReference<>();
    AtomicReference<String> providerQuery = new AtomicReference<>();
    AtomicReference<String> providerMessage = new AtomicReference<>();
    realtimeProviderApp = Javalin.create(config -> {
      config.startup.showJavalinBanner = false;
      config.routes.ws("/v1/realtime", ws -> ws.onMessage(ctx -> {
        providerAuthorization.set(ctx.header("Authorization"));
        providerQuery.set(ctx.session.getUpgradeRequest().getQueryString());
        providerMessage.set(ctx.message());
        ctx.send("{\"type\":\"provider.response\",\"echo\":" + ctx.message() + "}");
      }));
    }).start(0);
    app = GatewayApp.create().start(0);
    String config = """
        {
          "provider": "openai",
          "api_key": "sk-realtime",
          "custom_host": "%s"
        }
        """.formatted("http://localhost:" + realtimeProviderApp.port()).replaceAll("\\s+", " ");
    TextQueueWebSocketListener listener = new TextQueueWebSocketListener();

    WebSocket webSocket = client.newWebSocketBuilder()
        .header("x-modelgate-config", config)
        .buildAsync(
            URI.create("ws://localhost:" + app.port() + "/v1/realtime?model=gpt-4o-realtime-preview"),
            listener)
        .get(2, TimeUnit.SECONDS);
    webSocket.sendText("{\"type\":\"session.update\"}", true).get(2, TimeUnit.SECONDS);
    String response = listener.nextMessage();

    assertThat(response)
        .withFailMessage(
            "No gateway response. providerMessage=%s providerAuthorization=%s providerQuery=%s",
            providerMessage.get(),
            providerAuthorization.get(),
            providerQuery.get())
        .isNotNull();
    assertThat(response).contains("\"provider.response\"");
    assertThat(response).contains("\"session.update\"");
    assertThat(providerAuthorization).hasValue("Bearer sk-realtime");
    assertThat(providerQuery).hasValue("model=gpt-4o-realtime-preview");
    assertThat(providerMessage).hasValue("{\"type\":\"session.update\"}");
    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
  }

  @Test
  void metricsEndpointTracksRealtimeWebSocketConnectionsAndMessages() throws Exception {
    realtimeProviderApp = Javalin.create(config -> {
      config.startup.showJavalinBanner = false;
      config.routes.ws("/v1/realtime", ws -> ws.onMessage(ctx -> ctx.send("{\"type\":\"provider.response\"}")));
    }).start(0);
    app = GatewayApp.create().start(0);
    String config = """
        {
          "provider": "openai",
          "api_key": "sk-realtime",
          "custom_host": "%s"
        }
        """.formatted("http://localhost:" + realtimeProviderApp.port()).replaceAll("\\s+", " ");
    TextQueueWebSocketListener listener = new TextQueueWebSocketListener();

    WebSocket webSocket = client.newWebSocketBuilder()
        .header("x-modelgate-config", config)
        .buildAsync(URI.create("ws://localhost:" + app.port() + "/v1/realtime"), listener)
        .get(2, TimeUnit.SECONDS);
    webSocket.sendText("{\"type\":\"session.update\"}", true).get(2, TimeUnit.SECONDS);
    assertThat(listener.nextMessage()).contains("provider.response");
    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
    HttpResponse<String> metrics = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/metrics"))
            .header("accept", "text/plain")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(metrics.body()).contains("modelgate_realtime_connections_total");
    assertThat(metrics.body()).contains("modelgate_realtime_messages_total{direction=\"client_to_provider\",message_type=\"text\"}");
    assertThat(metrics.body()).contains("modelgate_realtime_messages_total{direction=\"provider_to_client\",message_type=\"text\"}");
  }

  @Test
  void realtimeWebSocketRouteProxiesBinaryMessagesBothDirections() throws Exception {
    AtomicReference<byte[]> providerMessage = new AtomicReference<>();
    realtimeProviderApp = Javalin.create(config -> {
      config.startup.showJavalinBanner = false;
      config.routes.ws("/v1/realtime", ws -> ws.onBinaryMessage(ctx -> {
        byte[] received = new byte[ctx.data().remaining()];
        ctx.data().get(received);
        providerMessage.set(received);
        ctx.send(ByteBuffer.wrap(new byte[] {9, 8, 7, 6}));
      }));
    }).start(0);
    app = GatewayApp.create().start(0);
    String config = """
        {
          "provider": "openai",
          "api_key": "sk-realtime",
          "custom_host": "%s"
        }
        """.formatted("http://localhost:" + realtimeProviderApp.port()).replaceAll("\\s+", " ");
    TextQueueWebSocketListener listener = new TextQueueWebSocketListener();

    WebSocket webSocket = client.newWebSocketBuilder()
        .header("x-modelgate-config", config)
        .buildAsync(URI.create("ws://localhost:" + app.port() + "/v1/realtime"), listener)
        .get(2, TimeUnit.SECONDS);
    byte[] clientBytes = new byte[] {1, 2, 3, 4};
    webSocket.sendBinary(ByteBuffer.wrap(clientBytes), true).get(2, TimeUnit.SECONDS);
    byte[] responseBytes = listener.nextBinaryMessage();

    assertThat(providerMessage).hasValue(clientBytes);
    assertThat(responseBytes).isEqualTo(new byte[] {9, 8, 7, 6});
    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
  }

  @Test
  void realtimeWebSocketRouteClosesWithPolicyViolationForInvalidConfig() throws Exception {
    app = GatewayApp.create().start(0);
    TextQueueWebSocketListener listener = new TextQueueWebSocketListener();

    WebSocket webSocket = client.newWebSocketBuilder()
        .buildAsync(URI.create("ws://localhost:" + app.port() + "/v1/realtime"), listener)
        .get(2, TimeUnit.SECONDS);
    String closeMessage = listener.nextMessage();

    assertThat(closeMessage).startsWith("closed:1008:");
    assertThat(closeMessage).contains("Missing x-modelgate-config/x-modelgate-provider");
    webSocket.abort();
  }

  @Test
  void inputGuardrailCanDenyBeforeProviderCall() throws Exception {
    java.util.concurrent.atomic.AtomicInteger providerCalls = new java.util.concurrent.atomic.AtomicInteger();
    startProviderServer(exchange -> {
      providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = "{\"input_guardrails\":[{\"default.contains\":{\"operator\":\"none\",\"words\":[\"secret\"]},\"deny\":true}]}";
    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-portkey-provider", "openai")
            .header("x-portkey-custom-host", providerBaseUrl())
            .header("x-portkey-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"secret\"}]}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(446);
    assertThat(response.body()).contains("hooks_failed");
    assertThat(providerCalls).hasValue(0);
  }

  @Test
  void defaultInputGuardrailCanDenyBeforeProviderCall() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    startProviderServer(exchange -> {
      providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "default_input_guardrails": [
            {"id": "default-input-deny", "default.contains": {"operator": "none", "words": ["secret"]}, "deny": true}
          ]
        }
        """.replace("\n", "");
    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"secret\"}]}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(446);
    assertThat(response.body()).contains("before_request_hooks", "default-input-deny");
    assertThat(providerCalls).hasValue(0);
  }

  @Test
  void outputGuardrailCanDenyAfterProviderCall() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"Apple\"}}]}"));
    app = GatewayApp.create().start(0);

    String config = "{\"output_guardrails\":[{\"default.contains\":{\"operator\":\"none\",\"words\":[\"Apple\"]},\"deny\":true}]}";
    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-portkey-provider", "openai")
            .header("x-portkey-custom-host", providerBaseUrl())
            .header("x-portkey-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(446);
    assertThat(response.body()).contains("after_request_hooks");
  }

  @Test
  void defaultOutputGuardrailCanDenyAfterProviderCall() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"Apple\"}}]}"));
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .header(
                "x-modelgate-default-output-guardrails",
                "[{\"id\":\"default-output-deny\",\"default.contains\":{\"operator\":\"none\",\"words\":[\"Apple\"]},\"deny\":true}]")
            .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(446);
    assertThat(response.body()).contains("after_request_hooks", "default-output-deny");
  }

  @Test
  void successfulGuardedResponseIncludesHookResults() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"Apple\"}}]}"));
    app = GatewayApp.create().start(0);

    String config = "{\"input_guardrails\":[{\"id\":\"input-pass\",\"default.contains\":{\"operator\":\"any\",\"words\":[\"hello\"]}}],"
        + "\"output_guardrails\":[{\"id\":\"output-pass\",\"default.contains\":{\"operator\":\"any\",\"words\":[\"Apple\"]}}]}";
    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-portkey-provider", "openai")
            .header("x-portkey-custom-host", providerBaseUrl())
            .header("x-portkey-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"hook_results\"");
    assertThat(response.body()).contains("input-pass");
    assertThat(response.body()).contains("output-pass");
    assertThat(response.body()).contains("default.contains");
  }

  @Test
  void failedNonDenyingInputGuardrailReturnsHooksFailedStatusAfterProviderCall() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    startProviderServer(exchange -> {
      providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"Apple\"}}]}");
    });
    app = GatewayApp.create().start(0);

    String config = "{\"input_guardrails\":[{\"id\":\"input-warn\",\"default.contains\":{\"operator\":\"none\",\"words\":[\"secret\"]}}]}";
    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"secret\"}]}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(246);
    assertThat(response.body()).contains("\"hook_results\"", "before_request_hooks", "input-warn", "\"verdict\":false");
    assertThat(providerCalls).hasValue(1);
  }

  @Test
  void failedNonDenyingOutputGuardrailReturnsHooksFailedStatus() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"Apple\"}}]}"));
    app = GatewayApp.create().start(0);

    String config = "{\"output_guardrails\":[{\"id\":\"output-warn\",\"default.contains\":{\"operator\":\"none\",\"words\":[\"Apple\"]}}]}";
    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(246);
    assertThat(response.body()).contains("\"hook_results\"", "after_request_hooks", "output-warn", "\"verdict\":false");
  }

  @Test
  void explicitGuardrailsRunBeforeDefaultGuardrails() throws Exception {
    startProviderServer(exchange -> respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"Apple\"}}]}"));
    app = GatewayApp.create().start(0);

    String config = """
        {
          "input_guardrails": [
            {"id": "explicit-input", "default.contains": {"operator": "any", "words": ["hello"]}}
          ],
          "default_input_guardrails": [
            {"id": "default-input", "default.contains": {"operator": "any", "words": ["hello"]}}
          ],
          "output_guardrails": [
            {"id": "explicit-output", "default.contains": {"operator": "any", "words": ["Apple"]}}
          ],
          "default_output_guardrails": [
            {"id": "default-output", "default.contains": {"operator": "any", "words": ["Apple"]}}
          ]
        }
        """.replace("\n", "");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-test")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"hook_results\"");
    assertThat(response.body().indexOf("explicit-input")).isLessThan(response.body().indexOf("default-input"));
    assertThat(response.body().indexOf("explicit-output")).isLessThan(response.body().indexOf("default-output"));
  }

  @Test
  void defaultOutputGuardrailRunsOnCachedResponses() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    startProviderServer(exchange -> {
      providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"Apple\"}}]}");
    });
    app = GatewayApp.create().start(0);

    String baseConfig = "{\"cache\":{\"mode\":\"simple\",\"max_age\":60000}}";
    HttpResponse<String> first = client.send(
        chatRequest(baseConfig, "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"),
        HttpResponse.BodyHandlers.ofString());
    String guardedConfig = """
        {
          "cache": {"mode": "simple", "max_age": 60000},
          "default_output_guardrails": [
            {"id": "cached-output-deny", "default.contains": {"operator": "none", "words": ["Apple"]}, "deny": true}
          ]
        }
        """.replace("\n", "");
    HttpResponse<String> second = client.send(
        chatRequest(guardedConfig, "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"),
        HttpResponse.BodyHandlers.ofString());

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.statusCode()).isEqualTo(446);
    assertThat(second.body()).contains("after_request_hooks", "cached-output-deny");
    assertThat(providerCalls).hasValue(1);
  }

  @Test
  void failedNonDenyingGuardrailDoesNotPoisonCachedProviderResponse() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    startProviderServer(exchange -> {
      providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"Apple\"}}]}");
    });
    app = GatewayApp.create().start(0);

    String guardedConfig = """
        {
          "cache": {"mode": "simple", "max_age": 60000},
          "output_guardrails": [
            {"id": "cache-output-warn", "default.contains": {"operator": "none", "words": ["Apple"]}}
          ]
        }
        """.replace("\n", "");
    String cacheOnlyConfig = "{\"cache\":{\"mode\":\"simple\",\"max_age\":60000}}";
    String body = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";

    HttpResponse<String> first = client.send(chatRequest(guardedConfig, body), HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> second = client.send(chatRequest(cacheOnlyConfig, body), HttpResponse.BodyHandlers.ofString());

    assertThat(first.statusCode()).isEqualTo(246);
    assertThat(first.body()).contains("\"hook_results\"", "cache-output-warn");
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(second.body()).contains("\"choices\"");
    assertThat(second.body()).doesNotContain("\"hook_results\"", "cache-output-warn");
    assertThat(providerCalls).hasValue(1);
  }

  @Test
  void streamingRequestsRejectOutputGuardrailsInsteadOfBypassingThem() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    startProviderServer(exchange -> {
      providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "default_output_guardrails": [
            {"id": "stream-output", "default.contains": {"operator": "none", "words": ["Apple"]}, "deny": true}
          ]
        }
        """.replace("\n", "");
    HttpResponse<String> response = client.send(
        chatRequest(config, "{\"model\":\"gpt-4o-mini\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("Output guardrails are not supported for streaming responses");
    assertThat(providerCalls).hasValue(0);
  }

  @Test
  void simpleCacheReturnsSecondIdenticalChatCompletionWithoutProviderCall() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    startProviderServer(exchange -> {
      int call = providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-" + call + "\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = "{\"cache\":{\"mode\":\"simple\",\"max_age\":60000}}";
    HttpRequest firstRequest = chatRequest(config, "{\"model\":\"gpt-4o-mini\"}");
    HttpRequest secondRequest = chatRequest(config, "{\"model\":\"gpt-4o-mini\"}");

    HttpResponse<String> first = client.send(firstRequest, HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> second = client.send(secondRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(first.body()).contains("\"id\":\"chatcmpl-1\"");
    assertThat(second.body()).contains("\"id\":\"chatcmpl-1\"");
    assertThat(first.headers().firstValue("x-portkey-cache-status")).contains("MISS");
    assertThat(first.headers().firstValue("x-modelgate-cache-status")).contains("MISS");
    assertThat(second.headers().firstValue("x-portkey-cache-status")).contains("HIT");
    assertThat(second.headers().firstValue("x-modelgate-cache-status")).contains("HIT");
    assertThat(providerCalls).hasValue(1);
  }

  @Test
  void redisCacheModeReturnsSecondIdenticalChatCompletionWithoutProviderCall() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    startProviderServer(exchange -> {
      int call = providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-redis-" + call + "\",\"choices\":[]}");
    });
    ResponseCacheFactory cacheFactory = new ResponseCacheFactory(
        new SimpleResponseCache(Clock.systemUTC()),
        new RedisResponseCache(new FakeRedisCacheStore()));
    app = GatewayApp.create(GatewayRuntimeConfig.defaults(), cacheFactory).start(0);

    String config = "{\"cache\":{\"mode\":\"redis\",\"max_age\":60000}}";
    HttpRequest firstRequest = chatRequest(config, "{\"model\":\"gpt-4o-mini\"}");
    HttpRequest secondRequest = chatRequest(config, "{\"model\":\"gpt-4o-mini\"}");

    HttpResponse<String> first = client.send(firstRequest, HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> second = client.send(secondRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(first.body()).contains("\"id\":\"chatcmpl-redis-1\"");
    assertThat(second.body()).contains("\"id\":\"chatcmpl-redis-1\"");
    assertThat(first.headers().firstValue("x-modelgate-cache-status")).contains("MISS");
    assertThat(second.headers().firstValue("x-modelgate-cache-status")).contains("HIT");
    assertThat(providerCalls).hasValue(1);
  }

  @Test
  void simpleCacheExpiresAfterConfiguredMaxAgeMillis() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    startProviderServer(exchange -> {
      int call = providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-expiring-" + call + "\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = "{\"cache\":{\"mode\":\"simple\",\"max_age\":1000}}";
    HttpRequest firstRequest = chatRequest(config, "{\"model\":\"gpt-4o-mini\"}");
    HttpRequest secondRequest = chatRequest(config, "{\"model\":\"gpt-4o-mini\"}");
    HttpRequest thirdRequest = chatRequest(config, "{\"model\":\"gpt-4o-mini\"}");

    HttpResponse<String> first = client.send(firstRequest, HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> second = client.send(secondRequest, HttpResponse.BodyHandlers.ofString());
    Thread.sleep(1200);
    HttpResponse<String> third = client.send(thirdRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(third.statusCode()).isEqualTo(200);
    assertThat(first.body()).contains("\"id\":\"chatcmpl-expiring-1\"");
    assertThat(second.body()).contains("\"id\":\"chatcmpl-expiring-1\"");
    assertThat(third.body()).contains("\"id\":\"chatcmpl-expiring-2\"");
    assertThat(first.headers().firstValue("x-modelgate-cache-status")).contains("MISS");
    assertThat(second.headers().firstValue("x-modelgate-cache-status")).contains("HIT");
    assertThat(third.headers().firstValue("x-modelgate-cache-status")).contains("MISS");
    assertThat(providerCalls).hasValue(2);
  }

  @Test
  void cacheForceRefreshBypassesExistingChatCompletionCacheEntry() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    startProviderServer(exchange -> {
      int call = providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-" + call + "\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = "{\"cache\":{\"mode\":\"simple\",\"max_age\":60000}}";
    HttpResponse<String> first = client.send(
        chatRequest(config, "{\"model\":\"gpt-4o-mini\"}"),
        HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> refreshed = client.send(
        chatRequest(config, "{\"model\":\"gpt-4o-mini\"}", Map.of("x-portkey-cache-force-refresh", "true")),
        HttpResponse.BodyHandlers.ofString());

    assertThat(first.body()).contains("\"id\":\"chatcmpl-1\"");
    assertThat(refreshed.body()).contains("\"id\":\"chatcmpl-2\"");
    assertThat(refreshed.headers().firstValue("x-portkey-cache-status")).contains("REFRESH");
    assertThat(providerCalls).hasValue(2);
  }

  @Test
  void cacheHitIncludesTraceProviderAndOptionMetadataHeaders() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    startProviderServer(exchange -> {
      int call = providerCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-cache-metadata-" + call + "\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = "{\"cache\":{\"mode\":\"simple\",\"max_age\":60000}}";
    HttpRequest firstRequest = chatRequest(
        config,
        "{\"model\":\"gpt-4o-mini\"}",
        Map.of("x-modelgate-trace-id", "trace-cache"));
    HttpRequest secondRequest = chatRequest(
        config,
        "{\"model\":\"gpt-4o-mini\"}",
        Map.of("x-modelgate-trace-id", "trace-cache"));

    HttpResponse<String> first = client.send(firstRequest, HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> second = client.send(secondRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(first.headers().firstValue("x-modelgate-cache-status")).contains("MISS");
    assertThat(second.headers().firstValue("x-modelgate-cache-status")).contains("HIT");
    assertThat(second.headers().firstValue("x-modelgate-trace-id")).contains("trace-cache");
    assertThat(second.headers().firstValue("x-portkey-trace-id")).contains("trace-cache");
    assertThat(second.headers().firstValue("x-modelgate-last-used-option-index")).contains("0");
    assertThat(second.headers().firstValue("x-portkey-last-used-option-index")).contains("0");
    assertThat(second.headers().firstValue("x-modelgate-provider")).contains("openai");
    assertThat(second.headers().firstValue("x-portkey-provider")).contains("openai");
    assertThat(providerCalls).hasValue(1);
  }

  @Test
  void fallbackStrategyUsesNextTargetWhenFirstTargetReturnsConfiguredFailureStatus() throws Exception {
    HttpServer failingServer = startAdditionalProviderServer(exchange ->
        respond(exchange, 500, "{\"error\":\"primary failed\"}"));
    HttpServer successfulServer = startAdditionalProviderServer(exchange ->
        respond(exchange, 200, "{\"id\":\"chatcmpl-fallback\",\"choices\":[]}"));
    app = GatewayApp.create().start(0);

    String config = """
        {
          "strategy": {"mode": "fallback", "on_status_codes": [500]},
          "targets": [
            {"name": "primary", "provider": "openai", "api_key": "sk-primary", "custom_host": "%s"},
            {"name": "backup", "provider": "openai", "api_key": "sk-backup", "custom_host": "%s"}
          ]
        }
        """.formatted(serverBaseUrl(failingServer), serverBaseUrl(successfulServer)).replace("\n", "");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-portkey-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"chatcmpl-fallback\"");
    assertThat(response.headers().firstValue("x-portkey-last-used-option-index")).contains("1");
  }

  @Test
  void fallbackTargetsInheritTopLevelRetryBeforeTryingNextTarget() throws Exception {
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger backupCalls = new AtomicInteger();
    HttpServer primaryServer = startAdditionalProviderServer(exchange -> {
      int call = primaryCalls.incrementAndGet();
      if (call == 1) {
        respond(exchange, 500, "{\"error\":\"primary failed once\"}");
        return;
      }
      respond(exchange, 200, "{\"id\":\"chatcmpl-primary-retried\",\"choices\":[]}");
    });
    HttpServer backupServer = startAdditionalProviderServer(exchange -> {
      backupCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-backup\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "retry": {"attempts": 1, "on_status_codes": [500]},
          "strategy": {"mode": "fallback", "on_status_codes": [500]},
          "targets": [
            {"name": "primary", "provider": "openai", "api_key": "sk-primary", "custom_host": "%s"},
            {"name": "backup", "provider": "openai", "api_key": "sk-backup", "custom_host": "%s"}
          ]
        }
        """.formatted(serverBaseUrl(primaryServer), serverBaseUrl(backupServer)).replaceAll("\\s+", " ");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"chatcmpl-primary-retried\"");
    assertThat(response.headers().firstValue("x-modelgate-last-used-option-index")).contains("0");
    assertThat(response.headers().firstValue("x-portkey-last-used-option-index")).contains("0");
    assertThat(response.headers().firstValue("x-modelgate-retry-attempt-count")).contains("1");
    assertThat(response.headers().firstValue("x-portkey-retry-attempt-count")).contains("1");
    assertThat(primaryCalls).hasValue(2);
    assertThat(backupCalls).hasValue(0);
  }

  @Test
  void fallbackTargetRetryOverridesInheritedRetryBeforeTryingNextTarget() throws Exception {
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger backupCalls = new AtomicInteger();
    HttpServer primaryServer = startAdditionalProviderServer(exchange -> {
      int call = primaryCalls.incrementAndGet();
      if (call == 1) {
        respond(exchange, 500, "{\"error\":\"primary failed once\"}");
        return;
      }
      respond(exchange, 200, "{\"id\":\"chatcmpl-target-retried\",\"choices\":[]}");
    });
    HttpServer backupServer = startAdditionalProviderServer(exchange -> {
      backupCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-backup\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "retry": {"attempts": 0, "on_status_codes": [500]},
          "strategy": {"mode": "fallback", "on_status_codes": [500]},
          "targets": [
            {
              "name": "primary",
              "provider": "openai",
              "api_key": "sk-primary",
              "custom_host": "%s",
              "retry": {"attempts": 1, "on_status_codes": [500]}
            },
            {"name": "backup", "provider": "openai", "api_key": "sk-backup", "custom_host": "%s"}
          ]
        }
        """.formatted(serverBaseUrl(primaryServer), serverBaseUrl(backupServer)).replaceAll("\\s+", " ");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"chatcmpl-target-retried\"");
    assertThat(response.headers().firstValue("x-modelgate-last-used-option-index")).contains("0");
    assertThat(response.headers().firstValue("x-portkey-last-used-option-index")).contains("0");
    assertThat(response.headers().firstValue("x-modelgate-retry-attempt-count")).contains("1");
    assertThat(response.headers().firstValue("x-portkey-retry-attempt-count")).contains("1");
    assertThat(primaryCalls).hasValue(2);
    assertThat(backupCalls).hasValue(0);
  }

  @Test
  void fallbackTargetsInheritTopLevelRequestTimeoutBeforeTryingNextTarget() throws Exception {
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger backupCalls = new AtomicInteger();
    HttpServer primaryServer = startAdditionalProviderServer(exchange -> {
      primaryCalls.incrementAndGet();
      Thread.sleep(300);
      respond(exchange, 200, "{\"id\":\"chatcmpl-primary-slow\",\"choices\":[]}");
    });
    HttpServer backupServer = startAdditionalProviderServer(exchange -> {
      backupCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-timeout-backup\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "request_timeout": 50,
          "strategy": {"mode": "fallback", "on_status_codes": [408]},
          "targets": [
            {"name": "primary", "provider": "openai", "api_key": "sk-primary", "custom_host": "%s"},
            {"name": "backup", "provider": "openai", "api_key": "sk-backup", "custom_host": "%s"}
          ]
        }
        """.formatted(serverBaseUrl(primaryServer), serverBaseUrl(backupServer)).replaceAll("\\s+", " ");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"chatcmpl-timeout-backup\"");
    assertThat(response.headers().firstValue("x-modelgate-last-used-option-index")).contains("1");
    assertThat(response.headers().firstValue("x-portkey-last-used-option-index")).contains("1");
    assertThat(primaryCalls).hasValue(1);
    assertThat(backupCalls).hasValue(1);
  }

  @Test
  void fallbackTargetRequestTimeoutOverridesInheritedRequestTimeoutBeforeTryingNextTarget() throws Exception {
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger backupCalls = new AtomicInteger();
    HttpServer primaryServer = startAdditionalProviderServer(exchange -> {
      primaryCalls.incrementAndGet();
      Thread.sleep(300);
      respond(exchange, 200, "{\"id\":\"chatcmpl-primary-slow\",\"choices\":[]}");
    });
    HttpServer backupServer = startAdditionalProviderServer(exchange -> {
      backupCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-target-timeout-backup\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "request_timeout": 1000,
          "strategy": {"mode": "fallback", "on_status_codes": [408]},
          "targets": [
            {
              "name": "primary",
              "provider": "openai",
              "api_key": "sk-primary",
              "custom_host": "%s",
              "request_timeout": 50
            },
            {"name": "backup", "provider": "openai", "api_key": "sk-backup", "custom_host": "%s"}
          ]
        }
        """.formatted(serverBaseUrl(primaryServer), serverBaseUrl(backupServer)).replaceAll("\\s+", " ");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"chatcmpl-target-timeout-backup\"");
    assertThat(response.headers().firstValue("x-modelgate-last-used-option-index")).contains("1");
    assertThat(response.headers().firstValue("x-portkey-last-used-option-index")).contains("1");
    assertThat(primaryCalls).hasValue(1);
    assertThat(backupCalls).hasValue(1);
  }

  @Test
  void fallbackStreamingChatCompletionStreamsNextTargetWhenFirstTargetFailsBeforeBodyCommit() throws Exception {
    AtomicInteger failingCalls = new AtomicInteger();
    HttpServer failingServer = startAdditionalProviderServer(exchange -> {
      failingCalls.incrementAndGet();
      respond(exchange, 500, "{\"error\":\"primary failed\"}");
    });
    CountDownLatch firstChunkWritten = new CountDownLatch(1);
    CountDownLatch allowProviderToFinish = new CountDownLatch(1);
    HttpServer successfulServer = startAdditionalProviderServer(exchange -> {
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write("data: {\"choices\":[{\"delta\":{\"content\":\"fallback-delta\"}}]}\n\n"
            .getBytes(StandardCharsets.UTF_8));
        output.flush();
        firstChunkWritten.countDown();
        assertThat(allowProviderToFinish.await(2, TimeUnit.SECONDS)).isTrue();
        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
      }
    });
    app = GatewayApp.create().start(0);
    String config = """
        {
          "strategy": {"mode": "fallback", "on_status_codes": [500]},
          "targets": [
            {"name": "primary", "provider": "openai", "api_key": "sk-primary", "custom_host": "%s"},
            {"name": "backup", "provider": "openai", "api_key": "sk-backup", "custom_host": "%s"}
          ]
        }
        """.formatted(serverBaseUrl(failingServer), serverBaseUrl(successfulServer)).replaceAll("\\s+", " ");

    CompletableFuture<HttpResponse<InputStream>> responseFuture = client.sendAsync(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\",\"stream\":true}"))
            .build(),
        HttpResponse.BodyHandlers.ofInputStream());

    assertThat(firstChunkWritten.await(2, TimeUnit.SECONDS)).isTrue();
    HttpResponse<InputStream> response = awaitStreamingResponse(responseFuture, allowProviderToFinish);
    String firstChunk = readUntilContains(response.body(), "fallback-delta", Duration.ofSeconds(2));
    allowProviderToFinish.countDown();
    String remainingBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("content-type")).contains("text/event-stream");
    assertThat(response.headers().firstValue("x-modelgate-last-used-option-index")).contains("1");
    assertThat(response.headers().firstValue("x-portkey-last-used-option-index")).contains("1");
    assertThat(response.headers().firstValue("x-modelgate-retry-attempt-count")).contains("0");
    assertThat(firstChunk).contains("fallback-delta");
    assertThat(remainingBody).contains("[DONE]");
    assertThat(failingCalls).hasValue(1);
  }

  @Test
  void loadbalanceStrategySkipsZeroWeightTargetAndReportsSelectedOptionIndex() throws Exception {
    AtomicInteger disabledCalls = new AtomicInteger();
    AtomicInteger activeCalls = new AtomicInteger();
    HttpServer disabledServer = startAdditionalProviderServer(exchange -> {
      disabledCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-disabled\",\"choices\":[]}");
    });
    HttpServer activeServer = startAdditionalProviderServer(exchange -> {
      activeCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-active\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "strategy": {"mode": "loadbalance"},
          "targets": [
            {"name": "disabled", "provider": "openai", "api_key": "sk-disabled", "custom_host": "%s", "weight": 0},
            {"name": "active", "provider": "openai", "api_key": "sk-active", "custom_host": "%s", "weight": 1}
          ]
        }
        """.formatted(serverBaseUrl(disabledServer), serverBaseUrl(activeServer)).replace("\n", "");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-portkey-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"chatcmpl-active\"");
    assertThat(response.headers().firstValue("x-portkey-last-used-option-index")).contains("1");
    assertThat(disabledCalls).hasValue(0);
    assertThat(activeCalls).hasValue(1);
  }

  @Test
  void conditionalStrategyRoutesByMetadataAndRequestBodyParams() throws Exception {
    AtomicInteger standardCalls = new AtomicInteger();
    AtomicInteger premiumCalls = new AtomicInteger();
    AtomicReference<String> premiumAuthorization = new AtomicReference<>();
    HttpServer standardServer = startAdditionalProviderServer(exchange -> {
      standardCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-standard\",\"choices\":[]}");
    });
    HttpServer premiumServer = startAdditionalProviderServer(exchange -> {
      premiumCalls.incrementAndGet();
      premiumAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      respond(exchange, 200, "{\"id\":\"chatcmpl-premium\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "strategy": {
            "mode": "conditional",
            "conditions": [
              {"query": {"metadata.tier": {"$eq": "gold"}, "params.model": {"$regex": "gpt-4o"}}, "then": "premium"}
            ],
            "default": "standard"
          },
          "targets": [
            {"name": "standard", "provider": "openai", "api_key": "sk-standard", "custom_host": "%s"},
            {"name": "premium", "provider": "openai", "api_key": "sk-premium", "custom_host": "%s"}
          ]
        }
        """.formatted(serverBaseUrl(standardServer), serverBaseUrl(premiumServer)).replace("\n", "");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-portkey-metadata", "{\"tier\":\"gold\"}")
            .header("x-portkey-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"chatcmpl-premium\"");
    assertThat(response.headers().firstValue("x-portkey-last-used-option-index")).contains("1");
    assertThat(standardCalls).hasValue(0);
    assertThat(premiumCalls).hasValue(1);
    assertThat(premiumAuthorization).hasValue("Bearer sk-premium");
  }

  @Test
  void conditionalStrategyRoutesByModelgateMetadataAlias() throws Exception {
    AtomicInteger standardCalls = new AtomicInteger();
    AtomicInteger premiumCalls = new AtomicInteger();
    HttpServer standardServer = startAdditionalProviderServer(exchange -> {
      standardCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-standard\",\"choices\":[]}");
    });
    HttpServer premiumServer = startAdditionalProviderServer(exchange -> {
      premiumCalls.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"chatcmpl-premium\",\"choices\":[]}");
    });
    app = GatewayApp.create().start(0);

    String config = """
        {
          "strategy": {
            "mode": "conditional",
            "conditions": [
              {"query": {"metadata.tier": {"$eq": "gold"}}, "then": "premium"}
            ],
            "default": "standard"
          },
          "targets": [
            {"name": "standard", "provider": "openai", "api_key": "sk-standard", "custom_host": "%s"},
            {"name": "premium", "provider": "openai", "api_key": "sk-premium", "custom_host": "%s"}
          ]
        }
        """.formatted(serverBaseUrl(standardServer), serverBaseUrl(premiumServer)).replace("\n", "");

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("x-modelgate-metadata", "{\"tier\":\"gold\"}")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"id\":\"chatcmpl-premium\"");
    assertThat(response.headers().firstValue("x-modelgate-last-used-option-index")).contains("1");
    assertThat(response.headers().firstValue("x-portkey-last-used-option-index")).contains("1");
    assertThat(standardCalls).hasValue(0);
    assertThat(premiumCalls).hasValue(1);
  }

  @Test
  void conditionalStrategyRoutesMultipartRawRequestByModelField() throws Exception {
    AtomicInteger standardCalls = new AtomicInteger();
    AtomicInteger premiumCalls = new AtomicInteger();
    AtomicReference<String> premiumPath = new AtomicReference<>();
    AtomicReference<String> premiumContentType = new AtomicReference<>();
    AtomicReference<byte[]> premiumBody = new AtomicReference<>();
    HttpServer standardServer = startAdditionalProviderServer(exchange -> {
      standardCalls.incrementAndGet();
      respond(exchange, 200, "{\"image\":\"standard\"}");
    });
    HttpServer premiumServer = startAdditionalProviderServer(exchange -> {
      premiumCalls.incrementAndGet();
      premiumPath.set(exchange.getRequestURI().getPath());
      premiumContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      premiumBody.set(exchange.getRequestBody().readAllBytes());
      respond(exchange, 200, "{\"image\":\"premium\"}");
    });
    app = GatewayApp.create().start(0);
    String config = """
        {
          "strategy": {
            "mode": "conditional",
            "conditions": [
              {"query": {"params.model": {"$eq": "core"}}, "then": "premium"}
            ],
            "default": "standard"
          },
          "targets": [
            {"name": "standard", "provider": "stability-ai", "api_key": "sk-standard", "custom_host": "%s"},
            {"name": "premium", "provider": "stability-ai", "api_key": "sk-premium", "custom_host": "%s"}
          ]
        }
        """.formatted(serverBaseUrl(standardServer), serverBaseUrl(premiumServer)).replace("\n", "");
    byte[] multipartBody = """
        --mg
        Content-Disposition: form-data; name="model"

        core
        --mg
        Content-Disposition: form-data; name="prompt"

        mountain
        --mg--
        """.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/images/generations"))
            .header("content-type", "multipart/form-data; boundary=mg")
            .header("x-modelgate-config", config)
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("x-modelgate-last-used-option-index")).contains("1");
    assertThat(standardCalls).hasValue(0);
    assertThat(premiumCalls).hasValue(1);
    assertThat(premiumPath).hasValue("/v2beta/stable-image/generate/core");
    assertThat(premiumContentType).hasValue("multipart/form-data; boundary=mg");
    assertThat(premiumBody).hasValue(multipartBody);
  }

  private HttpResponse<String> get(String url) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest chatRequest(String config, String body) {
    return chatRequest(config, body, Map.of());
  }

  private HttpRequest chatRequest(String config, String body, Map<String, String> extraHeaders) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(
            URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
        .header("content-type", "application/json")
        .header("authorization", "Bearer sk-test")
        .header("x-portkey-provider", "openai")
        .header("x-portkey-custom-host", providerBaseUrl())
        .header("x-portkey-config", config)
        .POST(HttpRequest.BodyPublishers.ofString(body));
    extraHeaders.forEach(builder::header);
    return builder.build();
  }

  private HttpResponse<String> sendChatUnchecked(HttpRequest request) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private HttpResponse<String> sendJsonEndpoint(String gatewayBaseUrl, String path, String method) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(gatewayBaseUrl + path))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-family")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .method(method, HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-4o-mini\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode readTree(String body) throws IOException {
    return OBJECT_MAPPER.readTree(body);
  }

  private void startProviderServer(ThrowingHandler handler) throws IOException {
    providerServer = startProviderServerInstance(handler);
  }

  private HttpServer startProviderServerInstance(ThrowingHandler handler) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/", exchange -> {
      try {
        handler.handle(exchange);
      } catch (Exception exception) {
        respond(exchange, 500, exception.getMessage());
      }
    });
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return server;
  }

  private HttpServer startAdditionalProviderServer(ThrowingHandler handler) throws IOException {
    HttpServer server = startProviderServerInstance(handler);
    providerServers.add(server);
    return server;
  }

  private String providerBaseUrl() {
    return serverBaseUrl(providerServer);
  }

  private String serverBaseUrl(HttpServer server) {
    return "http://localhost:" + server.getAddress().getPort();
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().put("content-type", java.util.List.of("application/json"));
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static HttpResponse<InputStream> awaitStreamingResponse(
      CompletableFuture<HttpResponse<InputStream>> responseFuture,
      CountDownLatch allowProviderToFinish) throws Exception {
    try {
      return responseFuture.get(500, TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      allowProviderToFinish.countDown();
      throw new AssertionError("Gateway buffered the streaming provider response until completion", exception);
    }
  }

  private static String readUntilContains(InputStream inputStream, String expected, Duration timeout)
      throws IOException {
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    StringBuilder body = new StringBuilder();
    while (System.nanoTime() < deadlineNanos) {
      int next = inputStream.read();
      if (next == -1) {
        break;
      }
      body.append((char) next);
      if (body.indexOf(expected) >= 0) {
        return body.toString();
      }
    }
    return body.toString();
  }

  private static boolean awaitPaths(List<String> actualPaths, List<String> expectedPaths, Duration timeout)
      throws InterruptedException {
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      if (actualPaths.containsAll(expectedPaths)) {
        return true;
      }
      Thread.sleep(25);
    }
    return actualPaths.containsAll(expectedPaths);
  }

  @FunctionalInterface
  private interface ThrowingHandler {
    void handle(HttpExchange exchange) throws Exception;
  }

  private static final class FakeRedisCacheStore implements RedisCacheStore {
    private final Map<String, String> values = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
      return Optional.ofNullable(values.get(key));
    }

    @Override
    public void set(String key, String body, Duration ttl) {
      values.put(key, body);
    }
  }

  private static final class TextQueueWebSocketListener implements WebSocket.Listener {
    private final ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<>(8);
    private final ArrayBlockingQueue<byte[]> binaryMessages = new ArrayBlockingQueue<>(8);
    private final StringBuilder partial = new StringBuilder();

    @Override
    public void onOpen(WebSocket webSocket) {
      WebSocket.Listener.super.onOpen(webSocket);
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        messages.add(partial.toString());
        partial.setLength(0);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      if (last) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        binaryMessages.add(bytes);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      messages.add("closed:" + statusCode + ":" + reason);
      return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      messages.add("error:" + error.getClass().getSimpleName() + ":" + error.getMessage());
    }

    String nextMessage() throws InterruptedException {
      return messages.poll(2, TimeUnit.SECONDS);
    }

    byte[] nextBinaryMessage() throws InterruptedException {
      byte[] message = binaryMessages.poll(2, TimeUnit.SECONDS);
      assertThat(message).isNotNull();
      return message;
    }
  }
}
