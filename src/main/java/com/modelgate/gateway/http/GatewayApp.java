package com.modelgate.gateway.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modelgate.gateway.cache.CacheLookup;
import com.modelgate.gateway.cache.CacheRequest;
import com.modelgate.gateway.cache.CacheStatus;
import com.modelgate.gateway.cache.ResponseCache;
import com.modelgate.gateway.cache.ResponseCacheFactory;
import com.modelgate.gateway.config.GatewayConfigParser;
import com.modelgate.gateway.config.GatewayConfig;
import com.modelgate.gateway.config.GatewayRuntimeConfig;
import com.modelgate.gateway.config.StrategyMode;
import com.modelgate.gateway.config.Target;
import com.modelgate.gateway.headers.GatewayHeaders;
import com.modelgate.gateway.hooks.GuardrailHooks;
import com.modelgate.gateway.hooks.HookDecision;
import com.modelgate.gateway.observability.OpenTelemetryPipeline;
import com.modelgate.gateway.observability.ProviderResponseMetadata;
import com.modelgate.gateway.plugins.BoundedPluginRegistry;
import com.modelgate.gateway.plugins.DefaultPluginRegistry;
import com.modelgate.gateway.provider.ProviderClient;
import com.modelgate.gateway.provider.ProviderCatalog;
import com.modelgate.gateway.provider.ProviderRequest;
import com.modelgate.gateway.provider.ProviderRequestFactory;
import com.modelgate.gateway.provider.ProviderResponse;
import com.modelgate.gateway.provider.ProviderResponseTransformer;
import com.modelgate.gateway.routing.ConditionalRouter;
import com.modelgate.gateway.routing.RouterContext;
import com.modelgate.gateway.validation.RequestValidator;
import com.modelgate.gateway.validation.ValidationResult;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public final class GatewayApp {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final ObjectMapper STREAM_OBJECT_MAPPER = new ObjectMapper();

  private GatewayApp() {}

  public static Javalin create() {
    return create(GatewayRuntimeConfig.fromEnvironment(System.getenv()));
  }

  public static Javalin create(GatewayRuntimeConfig runtimeConfig) {
    return create(runtimeConfig, ResponseCacheFactory.fromRuntimeConfig(Clock.systemUTC(), runtimeConfig));
  }

  public static Javalin create(GatewayRuntimeConfig runtimeConfig, ResponseCacheFactory responseCacheFactory) {
    GatewayRuntimeConfig resolvedRuntimeConfig = runtimeConfig == null ? GatewayRuntimeConfig.defaults() : runtimeConfig;
    ResponseCacheFactory resolvedResponseCacheFactory = responseCacheFactory == null
        ? ResponseCacheFactory.inMemoryOnly(Clock.systemUTC())
        : responseCacheFactory;
    RequestValidator requestValidator = new RequestValidator(
        ProviderCatalog.supportedProviders(),
        resolvedRuntimeConfig.trustedCustomHosts());
    ObjectMapper objectMapper = new ObjectMapper();
    GatewayConfigParser configParser = new GatewayConfigParser(objectMapper);
    ProviderRequestFactory providerRequestFactory = new ProviderRequestFactory(configParser);
    ProviderClient providerClient = new ProviderClient(
        HttpClient.newHttpClient(),
        resolvedRuntimeConfig.maxConcurrentProviderRequests());
    BoundedPluginRegistry pluginRegistry = new BoundedPluginRegistry(
        DefaultPluginRegistry.create(),
        resolvedRuntimeConfig.maxConcurrentPluginRequests());
    GuardrailHooks guardrailHooks = new GuardrailHooks(pluginRegistry);
    RequestLogBuffer requestLogBuffer = new RequestLogBuffer(objectMapper);
    RequestMetrics requestMetrics = new RequestMetrics();
    requestMetrics.bindProviderClient(providerClient);
    requestMetrics.bindPluginRegistry(pluginRegistry);
    OpenTelemetryPipeline openTelemetryPipeline = OpenTelemetryPipeline.create(resolvedRuntimeConfig.openTelemetry());
    ConcurrentMap<String, WebSocket> realtimeUpstreams = new ConcurrentHashMap<>();
    return Javalin.create(config -> {
      config.concurrency.useVirtualThreads = true;
      config.startup.showJavalinBanner = false;
      config.events.serverStopped(openTelemetryPipeline::close);
      config.routes.get("/", ctx -> ctx.result("AI Gateway says hey!"));
      config.routes.get("/health", ctx -> ctx.json(statusResponse("ok")));
      config.routes.get("/ready", ctx -> ctx.json(statusResponse("ready")));
      config.routes.get("/metrics", ctx -> writeMetrics(ctx, requestMetrics, providerClient, pluginRegistry));
      config.routes.get("/public", GatewayApp::handlePublicRoute);
      config.routes.get("/public/logs", GatewayApp::writePublicUi);
      config.routes.get("/log/stream", ctx -> writeLogStream(ctx, requestLogBuffer));
      config.routes.ws("/v1/realtime", ws -> {
        ws.onConnect(ctx -> openRealtimeUpstream(
            ctx, requestValidator, providerRequestFactory, providerClient, requestMetrics, realtimeUpstreams));
        ws.onMessage(ctx -> {
          requestMetrics.recordRealtimeMessage("client_to_provider", "text");
          realtimeUpstream(ctx, realtimeUpstreams).sendText(ctx.message(), true).join();
        });
        ws.onBinaryMessage(ctx -> {
          requestMetrics.recordRealtimeMessage("client_to_provider", "binary");
          realtimeUpstream(ctx, realtimeUpstreams).sendBinary(ctx.data(), true).join();
        });
        ws.onClose(ctx -> closeRealtimeUpstream(ctx, realtimeUpstreams));
        ws.onError(ctx -> closeRealtimeUpstream(ctx, realtimeUpstreams));
      });
      config.routes.get("/v1/models", ctx -> {
        if (!hasProviderContext(ctx.headerMap())) {
          ctx.json(modelsResponse());
          return;
        }
        handleProviderRequest(
            ctx,
            "/v1/models",
            "GET",
            requestValidator,
            configParser,
            providerRequestFactory,
            providerClient,
            guardrailHooks,
            resolvedResponseCacheFactory,
            objectMapper,
            requestLogBuffer,
            requestMetrics,
            openTelemetryPipeline,
            false);
      });
      config.routes.post("/v1/chat/completions", ctx -> handleProviderRequest(
          ctx,
          "/v1/chat/completions",
          "POST",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          false));
      config.routes.post("/v1/completions", ctx -> handleProviderRequest(
          ctx,
          "/v1/completions",
          "POST",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          false));
      config.routes.post("/v1/embeddings", ctx -> handleProviderRequest(
          ctx,
          "/v1/embeddings",
          "POST",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          false));
      config.routes.post("/v1/proxy/*", ctx -> handleProviderRequest(
          ctx,
          endpointPathAfterPrefixWithQuery(ctx, "/v1/proxy"),
          "POST",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          true));
      config.routes.get("/v1/proxy/*", ctx -> handleProviderRequest(
          ctx,
          endpointPathAfterPrefixWithQuery(ctx, "/v1/proxy"),
          "GET",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          true));
      config.routes.head("/v1/proxy/*", ctx -> handleProviderRequest(
          ctx,
          endpointPathAfterPrefixWithQuery(ctx, "/v1/proxy"),
          "HEAD",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          true));
      config.routes.options("/v1/proxy/*", ctx -> handleProviderRequest(
          ctx,
          endpointPathAfterPrefixWithQuery(ctx, "/v1/proxy"),
          "OPTIONS",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          true));
      config.routes.delete("/v1/proxy/*", ctx -> handleProviderRequest(
          ctx,
          endpointPathAfterPrefixWithQuery(ctx, "/v1/proxy"),
          "DELETE",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          true));
      config.routes.patch("/v1/proxy/*", ctx -> handleProviderRequest(
          ctx,
          endpointPathAfterPrefixWithQuery(ctx, "/v1/proxy"),
          "PATCH",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          true));
      config.routes.put("/v1/proxy/*", ctx -> handleProviderRequest(
          ctx,
          endpointPathAfterPrefixWithQuery(ctx, "/v1/proxy"),
          "PUT",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          true));
      config.routes.post("/v1/*", ctx -> handleProviderRequest(
          ctx,
          endpointPathWithQuery(ctx),
          "POST",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          false));
      config.routes.get("/v1/*", ctx -> handleProviderRequest(
          ctx,
          endpointPathWithQuery(ctx),
          "GET",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          false));
      config.routes.head("/v1/*", ctx -> handleProviderRequest(
          ctx,
          endpointPathWithQuery(ctx),
          "HEAD",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          false));
      config.routes.options("/v1/*", ctx -> handleProviderRequest(
          ctx,
          endpointPathWithQuery(ctx),
          "OPTIONS",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          false));
      config.routes.delete("/v1/*", ctx -> handleProviderRequest(
          ctx,
          endpointPathWithQuery(ctx),
          "DELETE",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          false));
      config.routes.patch("/v1/*", ctx -> handleProviderRequest(
          ctx,
          endpointPathWithQuery(ctx),
          "PATCH",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          false));
      config.routes.put("/v1/*", ctx -> handleProviderRequest(
          ctx,
          endpointPathWithQuery(ctx),
          "PUT",
          requestValidator,
          configParser,
          providerRequestFactory,
          providerClient,
          guardrailHooks,
          resolvedResponseCacheFactory,
          objectMapper,
          requestLogBuffer,
          requestMetrics,
          openTelemetryPipeline,
          false));
      config.routes.error(404, ctx -> ctx.status(404).json(Map.of(
          "message", "Not Found",
          "ok", false)));
    });
  }

  private static Map<String, Object> metricsSnapshot(
      RequestMetrics requestMetrics,
      ProviderClient providerClient,
      BoundedPluginRegistry pluginRegistry) {
    Map<String, Object> snapshot = new LinkedHashMap<>(requestMetrics.snapshot());
    snapshot.putAll(providerClient.concurrencySnapshot());
    snapshot.putAll(pluginRegistry.concurrencySnapshot());
    snapshot.putAll(runtimeSnapshot());
    return snapshot;
  }

  private static void writeMetrics(
      Context ctx,
      RequestMetrics requestMetrics,
      ProviderClient providerClient,
      BoundedPluginRegistry pluginRegistry) {
    String accept = ctx.header("accept");
    if (accept != null
        && (accept.toLowerCase(Locale.ROOT).contains("text/plain")
            || accept.toLowerCase(Locale.ROOT).contains("application/openmetrics-text"))) {
      ctx.contentType("text/plain; version=0.0.4; charset=utf-8");
      ctx.result(requestMetrics.prometheusScrape(accept));
      return;
    }
    ctx.json(metricsSnapshot(requestMetrics, providerClient, pluginRegistry));
  }

  private static Map<String, Object> runtimeSnapshot() {
    RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("virtual_threads_enabled", true);
    snapshot.put("available_processors", Runtime.getRuntime().availableProcessors());
    snapshot.put("jvm_thread_count", threads.getThreadCount());
    snapshot.put("jvm_daemon_thread_count", threads.getDaemonThreadCount());
    snapshot.put("jvm_peak_thread_count", threads.getPeakThreadCount());
    snapshot.put("uptime_ms", runtime.getUptime());
    snapshot.put("java_version", Runtime.version().toString());
    return snapshot;
  }

  private static Map<String, Object> modelsResponse() {
    return ModelCatalog.response();
  }

  private static boolean hasProviderContext(Map<String, String> headers) {
    return GatewayHeaders.contains(headers, "provider")
        || GatewayHeaders.contains(headers, "config")
        || GatewayHeaders.contains(headers, "virtual-key");
  }

  private static Map<String, Object> statusResponse(String status) {
    return Map.of(
        "service", "modelgate",
        "status", status);
  }

  private static void handlePublicRoute(Context ctx) {
    if (ctx.path().endsWith("/")) {
      writePublicUi(ctx);
      return;
    }
    ctx.redirect("/public/");
  }

  private static void writePublicUi(Context ctx) {
    ctx.contentType("text/html; charset=utf-8");
    ctx.result("""
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>ModelGate</title>
        </head>
        <body>
          <main>
            <h1>ModelGate</h1>
            <pre id="logs"></pre>
          </main>
          <script>
            const logs = document.getElementById('logs');
            const source = new EventSource('/log/stream');
            source.addEventListener('connected', event => {
              logs.textContent += `connected ${event.data}\\n`;
            });
            source.addEventListener('log', event => {
              logs.textContent += `${event.data}\\n`;
            });
          </script>
        </body>
        </html>
        """);
  }

  private static void writeLogStream(Context ctx, RequestLogBuffer requestLogBuffer) throws IOException {
    String clientId = UUID.randomUUID().toString();
    ctx.header("Cache-Control", "no-cache");
    ctx.header("X-Accel-Buffering", "no");
    ctx.contentType("text/event-stream");
    if ("true".equalsIgnoreCase(ctx.queryParam("follow"))) {
      writeLiveLogStream(ctx, requestLogBuffer, clientId);
      return;
    }
    ctx.result(requestLogBuffer.renderSnapshot(clientId));
  }

  private static void writeLiveLogStream(Context ctx, RequestLogBuffer requestLogBuffer, String clientId) throws IOException {
    OutputStream output = ctx.res().getOutputStream();
    requestLogBuffer.follow(clientId, output);
    ctx.res().flushBuffer();
    try {
      while (requestLogBuffer.hasSubscriber(clientId)) {
        Thread.sleep(1000);
        requestLogBuffer.heartbeat(clientId);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private static void openRealtimeUpstream(
      io.javalin.websocket.WsConnectContext ctx,
      RequestValidator requestValidator,
      ProviderRequestFactory providerRequestFactory,
      ProviderClient providerClient,
      RequestMetrics requestMetrics,
      ConcurrentMap<String, WebSocket> realtimeUpstreams) {
    Map<String, String> headers = webSocketHeaderMap(ctx);
    ValidationResult validation = requestValidator.validate(headers);
    if (!validation.valid()) {
      ctx.closeSession(1008, validation.message());
      return;
    }
    try {
      ProviderRequest providerRequest = providerRequestFactory.forEndpoint(
          endpointPathWithQuery("/v1/realtime", ctx.session.getUpgradeRequest().getQueryString()),
          "GET",
          "",
          headers,
          false);
      WebSocket.Builder upstreamBuilder = providerClient.webSocketBuilder();
      providerRequest.headers().forEach(upstreamBuilder::header);
      WebSocket upstream = upstreamBuilder.buildAsync(
              java.net.URI.create(toWebSocketUrl(providerRequest.url())),
              new RealtimeUpstreamListener(ctx, requestMetrics))
          .join();
      realtimeUpstreams.put(ctx.sessionId(), upstream);
      requestMetrics.recordRealtimeConnection();
    } catch (RuntimeException exception) {
      ctx.closeSession(1011, "Failed to connect realtime provider");
    }
  }

  private static WebSocket realtimeUpstream(
      io.javalin.websocket.WsContext ctx,
      ConcurrentMap<String, WebSocket> realtimeUpstreams) {
    WebSocket upstream = realtimeUpstreams.get(ctx.sessionId());
    if (upstream == null) {
      ctx.closeSession(1011, "Realtime provider is not connected");
      throw new IllegalStateException("Realtime provider is not connected");
    }
    return upstream;
  }

  private static void closeRealtimeUpstream(
      io.javalin.websocket.WsContext ctx,
      ConcurrentMap<String, WebSocket> realtimeUpstreams) {
    WebSocket upstream = realtimeUpstreams.remove(ctx.sessionId());
    if (upstream != null) {
      upstream.sendClose(WebSocket.NORMAL_CLOSURE, "client closed");
    }
  }

  private static Map<String, String> webSocketHeaderMap(io.javalin.websocket.WsContext ctx) {
    Map<String, String> headers = new LinkedHashMap<>();
    ctx.session.getUpgradeRequest().getHeaders().forEach((key, values) -> {
      if (!values.isEmpty()) {
        headers.put(key, values.getFirst());
      }
    });
    return headers;
  }

  private static String endpointPathWithQuery(String path, String queryString) {
    if (queryString == null || queryString.isBlank()) {
      return path;
    }
    return path + "?" + queryString;
  }

  private static String toWebSocketUrl(String url) {
    if (url.regionMatches(true, 0, "https://", 0, 8)) {
      return "wss://" + url.substring(8);
    }
    if (url.regionMatches(true, 0, "http://", 0, 7)) {
      return "ws://" + url.substring(7);
    }
    return url;
  }

  private static String endpointPathWithQuery(Context ctx) {
    String queryString = ctx.queryString();
    if (queryString == null || queryString.isBlank()) {
      return ctx.path();
    }
    return ctx.path() + "?" + queryString;
  }

  private static String endpointPathAfterPrefixWithQuery(Context ctx, String prefix) {
    String path = ctx.path();
    String endpointPath = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    if (endpointPath.isBlank()) {
      endpointPath = "/";
    }
    String queryString = ctx.queryString();
    if (queryString == null || queryString.isBlank()) {
      return endpointPath;
    }
    return endpointPath + "?" + queryString;
  }

  private static void handleProviderRequest(
      Context ctx,
      String endpointPath,
      String method,
      RequestValidator requestValidator,
      GatewayConfigParser configParser,
      ProviderRequestFactory providerRequestFactory,
      ProviderClient providerClient,
      GuardrailHooks guardrailHooks,
      ResponseCacheFactory responseCacheFactory,
      ObjectMapper objectMapper,
      RequestLogBuffer requestLogBuffer,
      RequestMetrics requestMetrics,
      OpenTelemetryPipeline openTelemetryPipeline,
      boolean proxyHeaders) {
    long startedNanos = System.nanoTime();
    LatencyBreakdown latency = new LatencyBreakdown(startedNanos);
    boolean metricsRecorded = false;
    ValidationResult validation = requestValidator.validate(ctx.headerMap());
    if (!validation.valid()) {
      recordMetrics(requestMetrics, startedNanos, validation.status(), true, false, CacheStatus.DISABLED);
      ctx.status(validation.status()).json(Map.of(
          "status", "failure",
          "message", validation.message()));
      return;
    }
    try {
      GatewayConfig gatewayConfig = configParser.parse(
          GatewayHeaders.valueOrDefault(ctx.headerMap(), "config", "{}"),
          ctx.headerMap());
      long phaseStarted = System.nanoTime();
      byte[] requestBodyBytes = requestBodyBytes(ctx, method);
      latency.addRequestRead(System.nanoTime() - phaseStarted);
      boolean rawBody = isRawBodyRequest(ctx.header("content-type"));
      String requestBody = rawBody ? new String(requestBodyBytes, StandardCharsets.UTF_8) : ctx.body();
      boolean streamingRequest = !rawBody && isStreamingRequest(objectMapper, requestBody);
      String requestType = requestType(endpointPath, method, streamingRequest);
      if (isVertexListFilesUnsupported(gatewayConfig, endpointPath, method)) {
        recordMetrics(requestMetrics, startedNanos, 500, false, false, CacheStatus.DISABLED);
        ctx.status(500).json(Map.of(
            "message", "listFiles is not supported by Google Vertex AI",
            "status", "failure",
            "provider", "google-vertex-ai"));
        return;
      }
      String providerBody;
      byte[] providerBodyBytes;
      HookDecision inputDecision = null;
      if (rawBody) {
        providerBody = requestBody;
        providerBodyBytes = requestBodyBytes;
      } else {
        phaseStarted = System.nanoTime();
        inputDecision = guardrailHooks.evaluateInput(
            requestBody,
            combinedGuardrails(gatewayConfig.inputGuardrails(), gatewayConfig.defaultInputGuardrails()),
            ctx.headerMap(),
            requestType);
        latency.addInputGuardrails(System.nanoTime() - phaseStarted);
        if (!inputDecision.allowed()) {
          recordMetrics(requestMetrics, startedNanos, inputDecision.status(), false, false, CacheStatus.DISABLED);
          ctx.status(inputDecision.status()).contentType("application/json").result(inputDecision.body());
          return;
        }
        providerBody = inputDecision.transformedBody() == null ? requestBody : inputDecision.transformedBody();
        providerBodyBytes = providerBody.getBytes(StandardCharsets.UTF_8);
        streamingRequest = isStreamingRequest(objectMapper, providerBody);
      }
      List<Map<String, Object>> outputGuardrails = combinedGuardrails(
          gatewayConfig.outputGuardrails(),
          gatewayConfig.defaultOutputGuardrails());
      if (streamingRequest && !outputGuardrails.isEmpty()) {
        recordMetrics(requestMetrics, startedNanos, 400, false, false, CacheStatus.DISABLED);
        ctx.status(400).json(Map.of(
            "status", "failure",
            "message", "Output guardrails are not supported for streaming responses"));
        return;
      }
      if (isVertexBatchOutputRequest(gatewayConfig, endpointPath, method)) {
        phaseStarted = System.nanoTime();
        ProviderResponse providerResponse = vertexBatchOutputResponse(
            endpointPath,
            providerRequestFactory,
            providerClient,
            ctx.headerMap(),
            proxyHeaders,
            objectMapper);
        latency.addProvider(System.nanoTime() - phaseStarted);
        phaseStarted = System.nanoTime();
        writeProviderResponse(ctx, providerResponse, gatewayConfig.provider(), 0);
        latency.addResponseWrite(System.nanoTime() - phaseStarted);
        metricsRecorded = recordMetrics(
            requestMetrics,
            startedNanos,
            providerResponse.status(),
            false,
            true,
            providerAttempts(providerResponse),
            CacheStatus.DISABLED);
        recordProviderObservation(
            requestMetrics,
            openTelemetryPipeline,
            objectMapper,
            startedNanos,
            endpointPath,
            method,
            gatewayConfig.provider(),
            providerBody,
            ctx.headerMap(),
            providerResponse.status(),
            CacheStatus.DISABLED,
            latency);
        return;
      }
      if (isAzureAiBatchOutputRequest(gatewayConfig, endpointPath, method)) {
        phaseStarted = System.nanoTime();
        ProviderResponse providerResponse = azureAiBatchOutputResponse(
            endpointPath,
            providerRequestFactory,
            providerClient,
            ctx.headerMap(),
            proxyHeaders,
            objectMapper);
        latency.addProvider(System.nanoTime() - phaseStarted);
        phaseStarted = System.nanoTime();
        writeProviderResponse(ctx, providerResponse, gatewayConfig.provider(), 0);
        latency.addResponseWrite(System.nanoTime() - phaseStarted);
        metricsRecorded = recordMetrics(
            requestMetrics,
            startedNanos,
            providerResponse.status(),
            false,
            true,
            providerAttempts(providerResponse),
            CacheStatus.DISABLED);
        recordProviderObservation(
            requestMetrics,
            openTelemetryPipeline,
            objectMapper,
            startedNanos,
            endpointPath,
            method,
            gatewayConfig.provider(),
            providerBody,
            ctx.headerMap(),
            providerResponse.status(),
            CacheStatus.DISABLED,
            latency);
        return;
      }
      if (isOpenAiCompatibleBatchOutputRequest(gatewayConfig, endpointPath, method)) {
        phaseStarted = System.nanoTime();
        ProviderResponse providerResponse = openAiCompatibleBatchOutputResponse(
            endpointPath,
            gatewayConfig,
            providerRequestFactory,
            providerClient,
            ctx.headerMap(),
            proxyHeaders,
            objectMapper);
        latency.addProvider(System.nanoTime() - phaseStarted);
        phaseStarted = System.nanoTime();
        writeProviderResponse(ctx, providerResponse, gatewayConfig.provider(), 0);
        latency.addResponseWrite(System.nanoTime() - phaseStarted);
        metricsRecorded = recordMetrics(
            requestMetrics,
            startedNanos,
            providerResponse.status(),
            false,
            true,
            providerAttempts(providerResponse),
            CacheStatus.DISABLED);
        recordProviderObservation(
            requestMetrics,
            openTelemetryPipeline,
            objectMapper,
            startedNanos,
            endpointPath,
            method,
            gatewayConfig.provider(),
            providerBody,
            ctx.headerMap(),
            providerResponse.status(),
            CacheStatus.DISABLED,
            latency);
        return;
      }
      if (isFallbackStrategy(gatewayConfig)) {
        phaseStarted = System.nanoTime();
        RoutedProviderResponse routedResponse = sendFallbackRequest(
            providerRequestFactory,
            providerClient,
            gatewayConfig,
            endpointPath,
            method,
            providerBody,
            providerBodyBytes,
            ctx.headerMap(),
            proxyHeaders,
            rawBody,
            streamingRequest);
        latency.addProvider(System.nanoTime() - phaseStarted);
        phaseStarted = System.nanoTime();
        int finalStatus = writeRoutedProviderResponse(ctx, guardrailHooks, gatewayConfig, routedResponse, rawBody, providerBody, ctx.headerMap(), requestType, inputDecision);
        latency.addResponseWrite(System.nanoTime() - phaseStarted);
        metricsRecorded = recordMetrics(
            requestMetrics,
            startedNanos,
            finalStatus,
            false,
            true,
            routedResponse.providerAttempts(),
            CacheStatus.DISABLED);
        recordProviderObservation(
            requestMetrics,
            openTelemetryPipeline,
            objectMapper,
            startedNanos,
            endpointPath,
            method,
            routedProvider(gatewayConfig, routedResponse.optionIndex()),
            providerBody,
            ctx.headerMap(),
            finalStatus,
            CacheStatus.DISABLED,
            latency);
        return;
      }
      if (isLoadbalanceStrategy(gatewayConfig)) {
        phaseStarted = System.nanoTime();
        RoutedProviderResponse routedResponse = sendLoadbalancedRequest(
            providerRequestFactory,
            providerClient,
            gatewayConfig,
            endpointPath,
            method,
            providerBody,
            providerBodyBytes,
            ctx.headerMap(),
            proxyHeaders,
            rawBody,
            streamingRequest);
        latency.addProvider(System.nanoTime() - phaseStarted);
        phaseStarted = System.nanoTime();
        int finalStatus = writeRoutedProviderResponse(ctx, guardrailHooks, gatewayConfig, routedResponse, rawBody, providerBody, ctx.headerMap(), requestType, inputDecision);
        latency.addResponseWrite(System.nanoTime() - phaseStarted);
        metricsRecorded = recordMetrics(
            requestMetrics,
            startedNanos,
            finalStatus,
            false,
            true,
            routedResponse.providerAttempts(),
            CacheStatus.DISABLED);
        recordProviderObservation(
            requestMetrics,
            openTelemetryPipeline,
            objectMapper,
            startedNanos,
            endpointPath,
            method,
            routedProvider(gatewayConfig, routedResponse.optionIndex()),
            providerBody,
            ctx.headerMap(),
            finalStatus,
            CacheStatus.DISABLED,
            latency);
        return;
      }
      if (isConditionalStrategy(gatewayConfig)) {
        phaseStarted = System.nanoTime();
        RoutedProviderResponse routedResponse = sendConditionalRequest(
            providerRequestFactory,
            providerClient,
            objectMapper,
            gatewayConfig,
            endpointPath,
            method,
            providerBody,
            providerBodyBytes,
            ctx.headerMap(),
            ctx.path(),
            proxyHeaders,
            rawBody,
            streamingRequest);
        latency.addProvider(System.nanoTime() - phaseStarted);
        phaseStarted = System.nanoTime();
        int finalStatus = writeRoutedProviderResponse(ctx, guardrailHooks, gatewayConfig, routedResponse, rawBody, providerBody, ctx.headerMap(), requestType, inputDecision);
        latency.addResponseWrite(System.nanoTime() - phaseStarted);
        metricsRecorded = recordMetrics(
            requestMetrics,
            startedNanos,
            finalStatus,
            false,
            true,
            routedResponse.providerAttempts(),
            CacheStatus.DISABLED);
        recordProviderObservation(
            requestMetrics,
            openTelemetryPipeline,
            objectMapper,
            startedNanos,
            endpointPath,
            method,
            routedProvider(gatewayConfig, routedResponse.optionIndex()),
            providerBody,
            ctx.headerMap(),
            finalStatus,
            CacheStatus.DISABLED,
            latency);
        return;
      }
      ProviderRequest providerRequest = rawBody
          ? providerRequestFactory.forEndpointRawBody(
              endpointPath,
              method,
              providerBodyBytes,
              ctx.headerMap(),
              proxyHeaders)
          : providerRequestFactory.forEndpoint(
              endpointPath,
              method,
              providerBody,
              ctx.headerMap(),
              proxyHeaders);
      if (streamingRequest) {
        phaseStarted = System.nanoTime();
        ProviderResponse providerResponse = ProviderResponseTransformer.transform(
            gatewayConfig.provider(),
            endpointPath,
            providerClient.sendStreaming(providerRequest));
        latency.addProvider(System.nanoTime() - phaseStarted);
        metricsRecorded = recordMetrics(
            requestMetrics,
            startedNanos,
            providerResponse.status(),
            false,
            true,
            providerAttempts(providerResponse),
            CacheStatus.DISABLED);
        recordProviderObservation(
            requestMetrics,
            openTelemetryPipeline,
            objectMapper,
            startedNanos,
            endpointPath,
            method,
            gatewayConfig.provider(),
            providerBody,
            ctx.headerMap(),
            providerResponse.status(),
            CacheStatus.DISABLED,
            latency);
        phaseStarted = System.nanoTime();
        writeProviderResponse(
            ctx,
            providerResponse,
            gatewayConfig.provider(),
            0,
            inputDecision,
            requestType,
            strictOpenAiCompliance(gatewayConfig, ctx.headerMap()));
        latency.addResponseWrite(System.nanoTime() - phaseStarted);
        requestLogBuffer.record(requestLog(
            objectMapper,
            ctx,
            endpointPath,
            method,
            startedNanos,
            providerRequest,
            gatewayConfig,
            null,
            0,
            providerResponse.status(),
            "{\"message\":\"The response was a stream.\"}",
            ProviderResponseMetadata.empty(),
            latency));
        return;
      }
      CacheRequest cacheRequest = new CacheRequest(providerRequest.url(), providerRequest.body(), ctx.headerMap());
      CacheLookup cacheLookup = CacheLookup.miss(null);
      Optional<ResponseCache> responseCache = responseCacheFactory.forSettings(gatewayConfig.cache());
      CacheStatus effectiveCacheStatus = CacheStatus.DISABLED;
      if (!rawBody && responseCache.isPresent()) {
        phaseStarted = System.nanoTime();
        cacheLookup = responseCache.get().get(cacheRequest);
        effectiveCacheStatus = cacheLookup.status();
        latency.addCacheLookup(System.nanoTime() - phaseStarted);
        if (cacheLookup.status() == CacheStatus.HIT) {
          String cachedBody = cacheLookup.body();
          ProviderResponseMetadata cachedMetadata = ProviderResponseMetadata.fromJson(objectMapper, cachedBody);
          HookDecision outputDecision = null;
          if (!outputGuardrails.isEmpty()) {
            phaseStarted = System.nanoTime();
            outputDecision = guardrailHooks.evaluateOutput(
                cachedBody,
                outputGuardrails,
                providerBody,
                ctx.headerMap(),
                requestType);
            latency.addOutputGuardrails(System.nanoTime() - phaseStarted);
            if (!outputDecision.allowed()) {
              metricsRecorded = recordMetrics(
                  requestMetrics,
                  startedNanos,
                  outputDecision.status(),
                  false,
                  false,
                  CacheStatus.HIT);
              recordProviderObservation(
                  requestMetrics,
                  openTelemetryPipeline,
                  objectMapper,
                  startedNanos,
                  endpointPath,
                  method,
                  gatewayConfig.provider(),
                  providerBody,
                  ctx.headerMap(),
                  outputDecision.status(),
                  CacheStatus.HIT,
                  cachedMetadata,
                  latency);
              ctx.status(outputDecision.status()).contentType("application/json").result(outputDecision.body());
              return;
            }
            if (outputDecision.transformedBody() != null) {
              cachedBody = outputDecision.transformedBody();
            }
          }
          if (inputDecisionHasResults(inputDecision) || hookDecisionHasResults(outputDecision)) {
            cachedBody = appendHookResults(
                objectMapper,
                cachedBody,
                inputDecision == null ? List.of() : inputDecision.beforeRequestHooksResult(),
                outputDecision == null ? List.of() : outputDecision.afterRequestHooksResult());
          }
          int finalStatus = hooksFailed(inputDecision, outputDecision) ? 246 : 200;
          metricsRecorded = recordMetrics(requestMetrics, startedNanos, finalStatus, false, false, CacheStatus.HIT);
          recordProviderObservation(
              requestMetrics,
              openTelemetryPipeline,
              objectMapper,
              startedNanos,
              endpointPath,
              method,
              gatewayConfig.provider(),
              providerBody,
              ctx.headerMap(),
              finalStatus,
              CacheStatus.HIT,
              cachedMetadata,
              latency);
          phaseStarted = System.nanoTime();
          ctx.status(finalStatus);
          setProviderMetadataHeaders(ctx, gatewayConfig.provider(), 0);
          setTraceHeader(ctx);
          setGatewayResponseHeader(ctx, "retry-attempt-count", "0");
          setGatewayResponseHeader(ctx, "cache-status", "HIT");
          ctx.contentType("application/json");
          ctx.result(cachedBody);
          latency.addResponseWrite(System.nanoTime() - phaseStarted);
          requestLogBuffer.record(requestLog(
              objectMapper,
              ctx,
              endpointPath,
              method,
              startedNanos,
              providerRequest,
              gatewayConfig,
              "HIT",
              0,
              finalStatus,
              cachedBody,
              cachedMetadata,
              latency));
          return;
        }
      }
      phaseStarted = System.nanoTime();
      ProviderResponse providerResponse = ProviderResponseTransformer.transform(
          gatewayConfig.provider(),
          endpointPath,
          providerClient.send(providerRequest));
      latency.addProvider(System.nanoTime() - phaseStarted);
      ProviderResponseMetadata providerMetadata = ProviderResponseMetadata.fromJson(objectMapper, providerResponse.body());
      int cacheableProviderStatus = providerResponse.status();
      String cacheableProviderBody = providerResponse.body();
      HookDecision outputDecision = null;
      if (isTextualResponse(providerResponse)) {
        phaseStarted = System.nanoTime();
        outputDecision = guardrailHooks.evaluateOutput(
            providerResponse.body(),
            outputGuardrails,
            providerBody,
            ctx.headerMap(),
            requestType);
        latency.addOutputGuardrails(System.nanoTime() - phaseStarted);
        if (!outputDecision.allowed()) {
          metricsRecorded = recordMetrics(
              requestMetrics,
              startedNanos,
              outputDecision.status(),
              false,
              true,
              providerAttempts(providerResponse),
              effectiveCacheStatus);
          recordProviderObservation(
              requestMetrics,
              openTelemetryPipeline,
              objectMapper,
              startedNanos,
              endpointPath,
              method,
              gatewayConfig.provider(),
              providerBody,
              ctx.headerMap(),
              outputDecision.status(),
              effectiveCacheStatus,
              providerMetadata,
              latency);
          ctx.status(outputDecision.status()).contentType("application/json").result(outputDecision.body());
          return;
        }
      }
      if (!rawBody
          && responseCache.isPresent()
          && cacheableProviderStatus >= 200
          && cacheableProviderStatus < 300) {
        phaseStarted = System.nanoTime();
        responseCache.get().put(cacheRequest, cacheableProviderBody, Duration.ofMillis(gatewayConfig.cache().ttlMillis()));
        latency.addCacheLookup(System.nanoTime() - phaseStarted);
        setGatewayResponseHeader(ctx, "cache-status", cacheLookup.status().name());
      }
      if (outputDecision != null && outputDecision.transformedBody() != null) {
        providerResponse = providerResponse.withBody(outputDecision.transformedBody());
      }
      if (inputDecisionHasResults(inputDecision) || hookDecisionHasResults(outputDecision)) {
        providerResponse = providerResponse.withBody(appendHookResults(
            objectMapper,
            providerResponse.body(),
            inputDecision.beforeRequestHooksResult(),
            outputDecision == null ? List.of() : outputDecision.afterRequestHooksResult()));
        if (hooksFailed(inputDecision, outputDecision) && providerResponse.status() >= 200 && providerResponse.status() < 300) {
          providerResponse = providerResponse.withStatus(246);
        }
      }
      phaseStarted = System.nanoTime();
      writeProviderResponse(ctx, providerResponse, gatewayConfig.provider(), 0);
      latency.addResponseWrite(System.nanoTime() - phaseStarted);
      metricsRecorded = recordMetrics(
          requestMetrics,
          startedNanos,
          providerResponse.status(),
          false,
          true,
          providerAttempts(providerResponse),
          effectiveCacheStatus);
      recordProviderObservation(
          requestMetrics,
          openTelemetryPipeline,
          objectMapper,
          startedNanos,
          endpointPath,
          method,
          gatewayConfig.provider(),
          providerBody,
          ctx.headerMap(),
          providerResponse.status(),
          effectiveCacheStatus,
          providerMetadata,
          latency);
      requestLogBuffer.record(requestLog(
          objectMapper,
          ctx,
          endpointPath,
          method,
          startedNanos,
          providerRequest,
          gatewayConfig,
          responseCache.isPresent() ? cacheLookup.status().name() : null,
          0,
          providerResponse.status(),
          providerResponse.body(),
          providerMetadata,
          latency));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      recordMetrics(requestMetrics, startedNanos, 500, false, false, CacheStatus.DISABLED);
      ctx.status(500).json(Map.of(
          "status", "failure",
          "message", "Provider request interrupted"));
    } catch (RuntimeException exception) {
      if (!metricsRecorded) {
        recordMetrics(requestMetrics, startedNanos, 500, false, false, CacheStatus.DISABLED);
      }
      throw exception;
    }
  }

  private static boolean recordMetrics(
      RequestMetrics requestMetrics,
      long startedNanos,
      int status,
      boolean validationFailure,
      boolean providerRequest,
      CacheStatus cacheStatus) {
    return recordMetrics(
        requestMetrics,
        startedNanos,
        status,
        validationFailure,
        providerRequest,
        providerRequest ? 1 : 0,
        cacheStatus);
  }

  private static boolean recordMetrics(
      RequestMetrics requestMetrics,
      long startedNanos,
      int status,
      boolean validationFailure,
      boolean providerRequest,
      int providerAttempts,
      CacheStatus cacheStatus) {
    requestMetrics.record(
        status,
        System.nanoTime() - startedNanos,
        validationFailure,
        providerRequest,
        providerAttempts,
        cacheStatus);
    return true;
  }

  private static void recordProviderObservation(
      RequestMetrics requestMetrics,
      OpenTelemetryPipeline openTelemetryPipeline,
      ObjectMapper objectMapper,
      long startedNanos,
      String endpointPath,
      String method,
      String provider,
      String requestBody,
      Map<String, String> headers,
      int status,
      CacheStatus cacheStatus) {
    recordProviderObservation(
        requestMetrics,
        openTelemetryPipeline,
        objectMapper,
        startedNanos,
        endpointPath,
        method,
        provider,
        requestBody,
        headers,
        status,
        cacheStatus,
        ProviderResponseMetadata.empty(),
        new LatencyBreakdown(startedNanos));
  }

  private static void recordProviderObservation(
      RequestMetrics requestMetrics,
      OpenTelemetryPipeline openTelemetryPipeline,
      ObjectMapper objectMapper,
      long startedNanos,
      String endpointPath,
      String method,
      String provider,
      String requestBody,
      Map<String, String> headers,
      int status,
      CacheStatus cacheStatus,
      LatencyBreakdown latency) {
    recordProviderObservation(
        requestMetrics,
        openTelemetryPipeline,
        objectMapper,
        startedNanos,
        endpointPath,
        method,
        provider,
        requestBody,
        headers,
        status,
        cacheStatus,
        ProviderResponseMetadata.empty(),
        latency);
  }

  private static void recordProviderObservation(
      RequestMetrics requestMetrics,
      OpenTelemetryPipeline openTelemetryPipeline,
      ObjectMapper objectMapper,
      long startedNanos,
      String endpointPath,
      String method,
      String provider,
      String requestBody,
      Map<String, String> headers,
      int status,
      CacheStatus cacheStatus,
      ProviderResponseMetadata metadata,
      LatencyBreakdown latency) {
    ProviderResponseMetadata safeMetadata = metadata == null ? ProviderResponseMetadata.empty() : metadata;
    LatencyBreakdown safeLatency = latency == null ? new LatencyBreakdown(startedNanos) : latency;
    String endpoint = pathWithoutQuery(endpointPath);
    String model = !safeMetadata.model().isBlank() ? safeMetadata.model() : modelName(objectMapper, requestBody);
    long totalDurationNanos = safeLatency.totalNanos();
    long providerDurationNanos = cacheStatus == CacheStatus.HIT ? 0 : safeLatency.providerNanos();
    requestMetrics.recordProviderObservation(
        endpoint,
        provider,
        model,
        status,
        cacheStatus,
        providerDurationNanos,
        safeMetadata);
    String traceId = traceId(headers);
    openTelemetryPipeline.recordRequestMetric(
        endpoint,
        provider,
        model,
        status,
        Duration.ofNanos(totalDurationNanos),
        safeMetadata);
    openTelemetryPipeline.recordRequestLog(
        traceId,
        endpoint,
        method,
        provider,
        model,
        status,
        Duration.ofNanos(totalDurationNanos),
        cacheStatus == null ? null : cacheStatus.name(),
        safeMetadata);
    if (cacheStatus != CacheStatus.HIT) {
      openTelemetryPipeline.recordGatewayTrace(
          traceId,
          endpoint,
          method,
          provider,
          model,
          status,
          Duration.ofNanos(totalDurationNanos),
          Duration.ofNanos(safeLatency.inputGuardrailsNanos()),
          Duration.ofNanos(providerDurationNanos),
          Duration.ofNanos(safeLatency.outputGuardrailsNanos()),
          safeMetadata);
    }
  }

  private static String routedProvider(GatewayConfig gatewayConfig, int optionIndex) {
    if (!gatewayConfig.targets().isEmpty() && optionIndex >= 0 && optionIndex < gatewayConfig.targets().size()) {
      return gatewayConfig.targets().get(optionIndex).provider();
    }
    return gatewayConfig.provider();
  }

  private static String traceId(Map<String, String> headers) {
    String traceId = GatewayHeaders.value(headers, "trace-id");
    return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
  }

  private static String modelName(ObjectMapper objectMapper, String requestBody) {
    Object parsed = parseJsonValue(objectMapper, requestBody);
    if (parsed instanceof Map<?, ?> map && map.get("model") != null) {
      return String.valueOf(map.get("model"));
    }
    return "unknown";
  }

  private static int providerAttempts(ProviderResponse providerResponse) {
    return providerResponse == null ? 0 : providerResponse.attempts() + 1;
  }

  private static boolean isFallbackStrategy(GatewayConfig gatewayConfig) {
    return gatewayConfig.strategy() != null
        && gatewayConfig.strategy().mode() == StrategyMode.FALLBACK
        && !gatewayConfig.targets().isEmpty();
  }

  private static boolean isLoadbalanceStrategy(GatewayConfig gatewayConfig) {
    return gatewayConfig.strategy() != null
        && gatewayConfig.strategy().mode() == StrategyMode.LOADBALANCE
        && !gatewayConfig.targets().isEmpty();
  }

  private static boolean isConditionalStrategy(GatewayConfig gatewayConfig) {
    return gatewayConfig.strategy() != null
        && gatewayConfig.strategy().mode() == StrategyMode.CONDITIONAL
        && !gatewayConfig.targets().isEmpty();
  }

  private static RoutedProviderResponse sendFallbackRequest(
      ProviderRequestFactory providerRequestFactory,
      ProviderClient providerClient,
      GatewayConfig gatewayConfig,
      String endpointPath,
      String method,
      String providerBody,
      byte[] providerBodyBytes,
      Map<String, String> headers,
      boolean proxyHeaders,
      boolean rawBody,
      boolean streamingRequest) throws InterruptedException {
    ProviderResponse latestResponse = null;
    int latestOptionIndex = 0;
    int providerAttempts = 0;
    for (int optionIndex = 0; optionIndex < gatewayConfig.targets().size(); optionIndex++) {
      Target target = gatewayConfig.targets().get(optionIndex);
      ProviderRequest providerRequest = rawBody
          ? providerRequestFactory.forTargetRawBody(
              target,
              endpointPath,
              method,
              providerBodyBytes,
              headers,
              proxyHeaders,
              gatewayConfig)
          : providerRequestFactory.forTarget(
              target,
              endpointPath,
              method,
              providerBody,
              headers,
              proxyHeaders,
              gatewayConfig);
      ProviderResponse providerResponse = streamingRequest
          ? providerClient.sendStreaming(providerRequest)
          : providerClient.send(providerRequest);
      latestResponse = providerResponse;
      latestOptionIndex = optionIndex;
      providerAttempts += providerAttempts(providerResponse);
      if (!gatewayConfig.strategy().onStatusCodes().contains(providerResponse.status())) {
        break;
      }
      closeStreamingBody(providerResponse);
    }
    return new RoutedProviderResponse(latestResponse, latestOptionIndex, providerAttempts);
  }

  private static RoutedProviderResponse sendLoadbalancedRequest(
      ProviderRequestFactory providerRequestFactory,
      ProviderClient providerClient,
      GatewayConfig gatewayConfig,
      String endpointPath,
      String method,
      String providerBody,
      byte[] providerBodyBytes,
      Map<String, String> headers,
      boolean proxyHeaders,
      boolean rawBody,
      boolean streamingRequest) throws InterruptedException {
    int optionIndex = selectWeightedTargetIndex(gatewayConfig.targets());
    Target target = gatewayConfig.targets().get(optionIndex);
    ProviderRequest providerRequest = rawBody
        ? providerRequestFactory.forTargetRawBody(
            target,
            endpointPath,
            method,
            providerBodyBytes,
            headers,
            proxyHeaders,
            gatewayConfig)
        : providerRequestFactory.forTarget(
            target,
            endpointPath,
            method,
            providerBody,
            headers,
            proxyHeaders,
            gatewayConfig);
    ProviderResponse providerResponse = streamingRequest
        ? providerClient.sendStreaming(providerRequest)
        : providerClient.send(providerRequest);
    return new RoutedProviderResponse(providerResponse, optionIndex, providerAttempts(providerResponse));
  }

  private static RoutedProviderResponse sendConditionalRequest(
      ProviderRequestFactory providerRequestFactory,
      ProviderClient providerClient,
      ObjectMapper objectMapper,
      GatewayConfig gatewayConfig,
      String endpointPath,
      String method,
      String providerBody,
      byte[] providerBodyBytes,
      Map<String, String> headers,
      String path,
      boolean proxyHeaders,
      boolean rawBody,
      boolean streamingRequest) throws InterruptedException {
    Target target = new ConditionalRouter(gatewayConfig).resolve(new RouterContext(
        parseObject(objectMapper, GatewayHeaders.value(headers, "metadata")),
        rawBody ? rawBodyParamsForRouting(headers, providerBodyBytes) : parseObject(objectMapper, providerBody),
        path));
    int optionIndex = gatewayConfig.targets().indexOf(target);
    ProviderRequest providerRequest = rawBody
        ? providerRequestFactory.forTargetRawBody(
            target,
            endpointPath,
            method,
            providerBodyBytes,
            headers,
            proxyHeaders,
            gatewayConfig)
        : providerRequestFactory.forTarget(
            target,
            endpointPath,
            method,
            providerBody,
            headers,
            proxyHeaders,
            gatewayConfig);
    ProviderResponse providerResponse = streamingRequest
        ? providerClient.sendStreaming(providerRequest)
        : providerClient.send(providerRequest);
    return new RoutedProviderResponse(providerResponse, optionIndex, providerAttempts(providerResponse));
  }

  private static int selectWeightedTargetIndex(List<Target> targets) {
    double totalWeight = targets.stream()
        .mapToDouble(Target::weight)
        .filter(weight -> weight > 0)
        .sum();
    if (totalWeight <= 0) {
      throw new IllegalArgumentException("No loadbalance target has positive weight");
    }

    double randomWeight = ThreadLocalRandom.current().nextDouble(totalWeight);
    for (int optionIndex = 0; optionIndex < targets.size(); optionIndex++) {
      double weight = Math.max(0, targets.get(optionIndex).weight());
      if (randomWeight < weight) {
        return optionIndex;
      }
      randomWeight -= weight;
    }
    return targets.size() - 1;
  }

  private static void writeProviderResponse(
      Context ctx,
      ProviderResponse providerResponse,
      String provider,
      int optionIndex) {
    writeProviderResponse(ctx, providerResponse, provider, optionIndex, null, null, true);
  }

  private static void writeProviderResponse(
      Context ctx,
      ProviderResponse providerResponse,
      String provider,
      int optionIndex,
      HookDecision inputDecision,
      String requestType,
      boolean strictOpenAiCompliance) {
    ctx.status(providerResponse.status());
    setProviderMetadataHeaders(ctx, provider, optionIndex);
    setTraceHeader(ctx);
    setGatewayResponseHeader(ctx, "retry-attempt-count", String.valueOf(providerResponse.attempts()));
    providerResponse.headers().forEach((key, value) -> {
      if ("content-type".equalsIgnoreCase(key)) {
        ctx.contentType(value);
      } else if (shouldForwardProviderResponseHeader(key, value)) {
        ctx.header(key, value);
      }
    });
    if (providerResponse.streaming() && providerResponse.bodyStream() != null) {
      writeStreamingResponse(
          ctx,
          providerResponse.bodyStream(),
          streamingHookResultsPrelude(inputDecision, requestType, strictOpenAiCompliance));
      return;
    }
    if (!isTextualResponse(providerResponse)) {
      ctx.result(new ByteArrayInputStream(providerResponse.bodyBytes()));
      return;
    }
    ctx.result(providerResponse.body());
  }

  private static boolean inputDecisionHasResults(HookDecision decision) {
    return decision != null && !decision.beforeRequestHooksResult().isEmpty();
  }

  private static boolean hookDecisionHasResults(HookDecision decision) {
    return decision != null
        && (!decision.beforeRequestHooksResult().isEmpty() || !decision.afterRequestHooksResult().isEmpty());
  }

  private static boolean hooksFailed(HookDecision inputDecision, HookDecision outputDecision) {
    return hookResultsFailed(inputDecision == null ? List.of() : inputDecision.beforeRequestHooksResult())
        || hookResultsFailed(outputDecision == null ? List.of() : outputDecision.afterRequestHooksResult());
  }

  private static boolean hookResultsFailed(List<Map<String, Object>> hookResults) {
    return hookResults != null
        && hookResults.stream().anyMatch(result -> Boolean.FALSE.equals(result.get("verdict")));
  }

  private static String appendHookResults(
      ObjectMapper objectMapper,
      String body,
      List<Map<String, Object>> beforeRequestHooks,
      List<Map<String, Object>> afterRequestHooks) {
    if ((beforeRequestHooks == null || beforeRequestHooks.isEmpty())
        && (afterRequestHooks == null || afterRequestHooks.isEmpty())) {
      return body;
    }
    try {
      Map<String, Object> root = objectMapper.readValue(body, MAP_TYPE);
      Map<String, Object> hookResults = new LinkedHashMap<>();
      hookResults.put("before_request_hooks", beforeRequestHooks == null ? List.of() : beforeRequestHooks);
      hookResults.put("after_request_hooks", afterRequestHooks == null ? List.of() : afterRequestHooks);
      root.put("hook_results", hookResults);
      return objectMapper.writeValueAsString(root);
    } catch (IOException exception) {
      return body;
    }
  }

  private static boolean shouldForwardProviderResponseHeader(String name, String value) {
    if (name == null || name.isBlank() || value == null) {
      return false;
    }
    String normalized = name.trim().toLowerCase(Locale.ROOT);
    return !GatewayHeaders.isGatewayControlHeader(normalized)
        && !normalized.equals("connection")
        && !normalized.equals("content-length")
        && !normalized.equals("host")
        && !normalized.equals("keep-alive")
        && !normalized.equals("proxy-authenticate")
        && !normalized.equals("proxy-authorization")
        && !normalized.equals("te")
        && !normalized.equals("trailer")
        && !normalized.equals("transfer-encoding")
        && !normalized.equals("upgrade")
        && !normalized.equals("set-cookie");
  }

  private static String streamingHookResultsPrelude(
      HookDecision inputDecision,
      String requestType,
      boolean strictOpenAiCompliance) {
    if (strictOpenAiCompliance) {
      return null;
    }
    if (!inputDecisionHasResults(inputDecision)) {
      return null;
    }
    Map<String, Object> hookResults = new LinkedHashMap<>();
    hookResults.put("before_request_hooks", inputDecision.beforeRequestHooksResult());
    Map<String, Object> payload = Map.of("hook_results", hookResults);
    try {
      String json = STREAM_OBJECT_MAPPER.writeValueAsString(payload);
      if ("messages".equals(requestType) || "stream-messages".equals(requestType)) {
        return "event: hook_results\ndata: " + json + "\n\n";
      }
      return "data: " + json + "\n\n";
    } catch (IOException exception) {
      return null;
    }
  }

  private static void writeStreamingResponse(Context ctx, InputStream bodyStream, String prelude) {
    try (InputStream upstream = bodyStream; OutputStream downstream = ctx.res().getOutputStream()) {
      ctx.res().flushBuffer();
      if (prelude != null && !prelude.isBlank()) {
        downstream.write(prelude.getBytes(StandardCharsets.UTF_8));
        downstream.flush();
      }
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = upstream.read(buffer)) != -1) {
        downstream.write(buffer, 0, bytesRead);
        downstream.flush();
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to stream provider response", exception);
    }
  }

  private static void closeStreamingBody(ProviderResponse providerResponse) {
    if (!providerResponse.streaming() || providerResponse.bodyStream() == null) {
      return;
    }
    try {
      providerResponse.bodyStream().close();
    } catch (IOException ignored) {
      // Ignore cleanup failures while trying the next fallback target.
    }
  }

  private static int writeRoutedProviderResponse(
      Context ctx,
      GuardrailHooks guardrailHooks,
      GatewayConfig gatewayConfig,
      RoutedProviderResponse routedResponse,
      boolean rawBody,
      String requestBody,
      Map<String, String> headers,
      String requestType,
      HookDecision inputDecision) {
    ProviderResponse providerResponse = routedResponse.response();
    String provider = gatewayConfig.targets().isEmpty()
        ? gatewayConfig.provider()
        : gatewayConfig.targets().get(routedResponse.optionIndex()).provider();
    if (providerResponse.streaming()) {
      providerResponse = ProviderResponseTransformer.transform(provider, ctx.path(), providerResponse);
      setGatewayResponseHeader(ctx, "last-used-option-index", String.valueOf(routedResponse.optionIndex()));
      writeProviderResponse(
          ctx,
          providerResponse,
          provider,
          routedResponse.optionIndex(),
          inputDecision,
          requestType,
          strictOpenAiCompliance(gatewayConfig, headers));
      return providerResponse.status();
    }
    providerResponse = ProviderResponseTransformer.transform(provider, ctx.path(), providerResponse);
    if (!rawBody && isTextualResponse(providerResponse)) {
      HookDecision outputDecision = guardrailHooks.evaluateOutput(
          providerResponse.body(),
          combinedGuardrails(gatewayConfig.outputGuardrails(), gatewayConfig.defaultOutputGuardrails()),
          requestBody,
          headers,
          requestType);
      if (!outputDecision.allowed()) {
        ctx.status(outputDecision.status()).contentType("application/json").result(outputDecision.body());
        return outputDecision.status();
      }
      if (outputDecision.transformedBody() != null) {
        providerResponse = providerResponse.withBody(outputDecision.transformedBody());
      }
      if (inputDecisionHasResults(inputDecision) || hookDecisionHasResults(outputDecision)) {
        providerResponse = providerResponse.withBody(appendHookResults(
            new ObjectMapper(),
            providerResponse.body(),
            inputDecision == null ? List.of() : inputDecision.beforeRequestHooksResult(),
            outputDecision.afterRequestHooksResult()));
        if (hooksFailed(inputDecision, outputDecision) && providerResponse.status() >= 200 && providerResponse.status() < 300) {
          providerResponse = providerResponse.withStatus(246);
        }
      }
    }
    setGatewayResponseHeader(ctx, "last-used-option-index", String.valueOf(routedResponse.optionIndex()));
    writeProviderResponse(ctx, providerResponse, provider, routedResponse.optionIndex());
    return providerResponse.status();
  }

  private static void setGatewayResponseHeader(Context ctx, String suffix, String value) {
    ctx.header(GatewayHeaders.modelgate(suffix), value);
    ctx.header(GatewayHeaders.portkey(suffix), value);
  }

  private static List<Map<String, Object>> combinedGuardrails(
      List<Map<String, Object>> explicitGuardrails,
      List<Map<String, Object>> defaultGuardrails) {
    if (defaultGuardrails == null || defaultGuardrails.isEmpty()) {
      return explicitGuardrails == null ? List.of() : explicitGuardrails;
    }
    if (explicitGuardrails == null || explicitGuardrails.isEmpty()) {
      return defaultGuardrails;
    }
    List<Map<String, Object>> combined = new java.util.ArrayList<>(explicitGuardrails.size() + defaultGuardrails.size());
    combined.addAll(explicitGuardrails);
    combined.addAll(defaultGuardrails);
    return combined;
  }

  private static boolean strictOpenAiCompliance(GatewayConfig gatewayConfig, Map<String, String> headers) {
    String headerValue = GatewayHeaders.value(headers, "strict-open-ai-compliance");
    if (headerValue != null && headerValue.trim().equalsIgnoreCase("false")) {
      return false;
    }
    Object configValue = gatewayConfig.providerOptions().get("strictOpenAiCompliance");
    return !(configValue instanceof Boolean booleanValue) || booleanValue;
  }

  private static boolean isVertexListFilesUnsupported(GatewayConfig gatewayConfig, String endpointPath, String method) {
    if (gatewayConfig == null || !"vertex-ai".equalsIgnoreCase(gatewayConfig.provider())) {
      return false;
    }
    String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
    return "GET".equals(normalizedMethod) && "/v1/files".equals(pathWithoutQuery(endpointPath));
  }

  private static boolean isVertexBatchOutputRequest(GatewayConfig gatewayConfig, String endpointPath, String method) {
    if (gatewayConfig == null || !"vertex-ai".equalsIgnoreCase(gatewayConfig.provider())) {
      return false;
    }
    String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
    return "GET".equals(normalizedMethod) && pathWithoutQuery(endpointPath).matches("/v1/batches/[^/]+/output");
  }

  private static boolean isAzureAiBatchOutputRequest(GatewayConfig gatewayConfig, String endpointPath, String method) {
    if (gatewayConfig == null || !"azure-ai".equalsIgnoreCase(gatewayConfig.provider())) {
      return false;
    }
    String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
    return "GET".equals(normalizedMethod) && pathWithoutQuery(endpointPath).matches("/v1/batches/[^/]+/output");
  }

  private static boolean isOpenAiCompatibleBatchOutputRequest(
      GatewayConfig gatewayConfig,
      String endpointPath,
      String method) {
    if (gatewayConfig == null) {
      return false;
    }
    String provider = gatewayConfig.provider() == null ? "" : gatewayConfig.provider().trim().toLowerCase(Locale.ROOT);
    if (!Set.of("openai", "azure-openai").contains(provider)) {
      return false;
    }
    String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
    return "GET".equals(normalizedMethod) && pathWithoutQuery(endpointPath).matches("/v1/batches/[^/]+/output");
  }

  private static ProviderResponse openAiCompatibleBatchOutputResponse(
      String endpointPath,
      GatewayConfig gatewayConfig,
      ProviderRequestFactory providerRequestFactory,
      ProviderClient providerClient,
      Map<String, String> headers,
      boolean proxyHeaders,
      ObjectMapper objectMapper) throws InterruptedException {
    String path = pathWithoutQuery(endpointPath);
    String batchId = path.substring("/v1/batches/".length(), path.length() - "/output".length());
    String batchEndpoint = "/v1/batches/" + batchId;
    ProviderRequest batchRequest = providerRequestFactory.forEndpoint(
        batchEndpoint,
        "GET",
        "",
        headers,
        proxyHeaders);
    ProviderResponse batchResponse = ProviderResponseTransformer.transform(
        gatewayConfig.provider(),
        batchEndpoint,
        providerClient.send(batchRequest));
    if (batchResponse.status() < 200 || batchResponse.status() >= 300) {
      return batchResponse;
    }
    String outputFileId = outputOrErrorFileId(objectMapper, batchResponse.body());
    if (outputFileId.isBlank()) {
      ProviderResponse errorsResponse = batchErrorsResponse(objectMapper, batchResponse);
      if (errorsResponse != null) {
        return errorsResponse;
      }
      return new ProviderResponse(
          500,
          "{\"status\":\"failure\",\"message\":\"OpenAI-compatible batch output file was not available\"}",
          Map.of("content-type", "application/json"),
          batchResponse.attempts());
    }
    ProviderRequest outputRequest = providerRequestFactory.forEndpoint(
        "/v1/files/" + outputFileId + "/content",
        "GET",
        "",
        headers,
        proxyHeaders);
    return providerClient.send(outputRequest);
  }

  private static ProviderResponse batchErrorsResponse(ObjectMapper objectMapper, ProviderResponse batchResponse) {
    try {
      JsonNode errors = objectMapper.readTree(batchResponse.body()).path("errors");
      if (errors.isMissingNode() || errors.isNull() || errors.isEmpty()) {
        return null;
      }
      return new ProviderResponse(
          200,
          objectMapper.writeValueAsString(errors),
          Map.of("content-type", "application/json"),
          batchResponse.attempts());
    } catch (IOException exception) {
      return null;
    }
  }

  private static ProviderResponse azureAiBatchOutputResponse(
      String endpointPath,
      ProviderRequestFactory providerRequestFactory,
      ProviderClient providerClient,
      Map<String, String> headers,
      boolean proxyHeaders,
      ObjectMapper objectMapper) throws InterruptedException {
    String path = pathWithoutQuery(endpointPath);
    String batchId = path.substring("/v1/batches/".length(), path.length() - "/output".length());
    String batchEndpoint = "/v1/batches/" + batchId;
    ProviderRequest batchRequest = providerRequestFactory.forEndpoint(
        batchEndpoint,
        "GET",
        "",
        headers,
        proxyHeaders);
    ProviderResponse batchResponse = ProviderResponseTransformer.transform(
        "azure-ai",
        batchEndpoint,
        providerClient.send(batchRequest));
    if (batchResponse.status() < 200 || batchResponse.status() >= 300) {
      return batchResponse;
    }
    String outputFileId = outputOrErrorFileId(objectMapper, batchResponse.body());
    if (outputFileId.isBlank()) {
      return new ProviderResponse(
          500,
          "{\"status\":\"failure\",\"message\":\"Azure AI batch output file was not available\"}",
          Map.of("content-type", "application/json"),
          batchResponse.attempts());
    }
    ProviderRequest outputRequest = providerRequestFactory.forEndpoint(
        "/v1/files/" + outputFileId + "/content",
        "GET",
        "",
        headers,
        proxyHeaders);
    return providerClient.send(outputRequest);
  }

  private static ProviderResponse vertexBatchOutputResponse(
      String endpointPath,
      ProviderRequestFactory providerRequestFactory,
      ProviderClient providerClient,
      Map<String, String> headers,
      boolean proxyHeaders,
      ObjectMapper objectMapper) throws InterruptedException {
    String path = pathWithoutQuery(endpointPath);
    String batchId = path.substring("/v1/batches/".length(), path.length() - "/output".length());
    String batchEndpoint = "/v1/batches/" + batchId;
    ProviderRequest batchRequest = providerRequestFactory.forEndpoint(
        batchEndpoint,
        "GET",
        "",
        headers,
        proxyHeaders);
    ProviderResponse batchResponse = ProviderResponseTransformer.transform(
        "vertex-ai",
        batchEndpoint,
        providerClient.send(batchRequest));
    if (batchResponse.status() < 200 || batchResponse.status() >= 300) {
      return batchResponse;
    }
    String outputFileId = outputFileId(objectMapper, batchResponse.body());
    if (outputFileId.isBlank()) {
      return new ProviderResponse(
          500,
          "{\"status\":\"failure\",\"message\":\"Vertex batch output file was not available\"}",
          Map.of("content-type", "application/json"),
          batchResponse.attempts());
    }
    ProviderRequest outputRequest = providerRequestFactory.forEndpoint(
        "/v1/files/" + outputFileId + "/content",
        "GET",
        "",
        headers,
        proxyHeaders);
    return normalizeVertexBatchOutput(providerClient.send(outputRequest), objectMapper);
  }

  private static String outputFileId(ObjectMapper objectMapper, String body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      return root.path("output_file_id").asText("");
    } catch (IOException exception) {
      return "";
    }
  }

  private static String outputOrErrorFileId(ObjectMapper objectMapper, String body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      return firstNonBlank(root.path("output_file_id").asText(""), root.path("error_file_id").asText(""));
    } catch (IOException exception) {
      return "";
    }
  }

  private static ProviderResponse normalizeVertexBatchOutput(ProviderResponse response, ObjectMapper objectMapper) {
    if (response.status() < 200 || response.status() >= 300 || response.body().isBlank()) {
      return response;
    }
    StringBuilder output = new StringBuilder();
    for (String line : response.body().split("\\R")) {
      if (line.isBlank()) {
        continue;
      }
      try {
        JsonNode row = objectMapper.readTree(line);
        output.append(objectMapper.writeValueAsString(vertexBatchOutputRow(row, objectMapper, response.attempts())))
            .append('\n');
      } catch (IOException exception) {
        return response;
      }
    }
    Map<String, String> headers = new LinkedHashMap<>(response.headers());
    headers.put("content-type", "application/x-ndjson");
    return new ProviderResponse(response.status(), output.toString(), headers, response.attempts());
  }

  private static ObjectNode vertexBatchOutputRow(JsonNode row, ObjectMapper objectMapper, int attempts)
      throws IOException {
    String customId = firstNonBlank(
        row.path("custom_id").asText(""),
        row.path("requestId").asText(""),
        row.path("id").asText(""));
    JsonNode error = vertexBatchRowError(row, objectMapper);
    if (error != null) {
      return vertexBatchErrorOutputRow(row, customId, error, objectMapper);
    }
    JsonNode nativeResponse = firstObject(row.path("response"), row.path("prediction"), row.path("result"), row);
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "vertex-ai",
        vertexBatchOutputEndpoint(nativeResponse),
        new ProviderResponse(200, nativeResponse.toString(), Map.of("content-type", "application/json"), attempts));
    ObjectNode out = objectMapper.createObjectNode();
    out.put("id", firstNonBlank(row.path("id").asText(""), "batch_req_" + customId));
    out.put("custom_id", customId);
    ObjectNode response = out.putObject("response");
    response.put("status_code", 200);
    response.put("request_id", customId);
    response.set("body", objectMapper.readTree(transformed.body()));
    out.set("error", objectMapper.nullNode());
    return out;
  }

  private static ObjectNode vertexBatchErrorOutputRow(
      JsonNode row,
      String customId,
      JsonNode error,
      ObjectMapper objectMapper) {
    int statusCode = error.path("code").asInt(500);
    ObjectNode out = objectMapper.createObjectNode();
    out.put("id", firstNonBlank(row.path("id").asText(""), "batch_req_" + customId));
    out.put("custom_id", customId);
    ObjectNode response = out.putObject("response");
    response.put("status_code", statusCode);
    response.put("request_id", customId);
    response.set("body", objectMapper.nullNode());
    ObjectNode errorOut = out.putObject("error");
    errorOut.put("code", statusCode);
    errorOut.put("message", error.path("message").asText(""));
    return out;
  }

  private static JsonNode vertexBatchRowError(JsonNode row, ObjectMapper objectMapper) throws IOException {
    if (row.path("error").isObject()) {
      return row.path("error");
    }
    if (!row.path("status").isTextual()) {
      return null;
    }
    JsonNode status = objectMapper.readTree(row.path("status").asText());
    return status.has("code") || status.has("message") ? status : null;
  }

  private static String vertexBatchOutputEndpoint(JsonNode nativeResponse) {
    if (nativeResponse.path("predictions").isArray()) {
      for (JsonNode prediction : nativeResponse.path("predictions")) {
        if (prediction.at("/embeddings/values").isArray() || prediction.path("textEmbedding").isArray()) {
          return "/v1/embeddings";
        }
      }
    }
    return "/v1/chat/completions";
  }

  private static JsonNode firstObject(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      if (node != null && node.isObject() && !node.isMissingNode()) {
        return node;
      }
    }
    return STREAM_OBJECT_MAPPER.createObjectNode();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static void setProviderMetadataHeaders(Context ctx, String provider, int optionIndex) {
    setGatewayResponseHeader(ctx, "last-used-option-index", String.valueOf(optionIndex));
    if (provider != null && !provider.isBlank()) {
      setGatewayResponseHeader(ctx, "provider", provider);
    }
  }

  private static void setTraceHeader(Context ctx) {
    String traceId = GatewayHeaders.value(ctx.headerMap(), "trace-id");
    if (traceId != null && !traceId.isBlank()) {
      setGatewayResponseHeader(ctx, "trace-id", traceId);
    }
  }

  private static Map<String, Object> requestLog(
      ObjectMapper objectMapper,
      Context ctx,
      String endpointPath,
      String method,
      long startedNanos,
      ProviderRequest providerRequest,
      GatewayConfig gatewayConfig,
      String cacheStatus,
      int optionIndex,
      int status,
      String responseBody,
      ProviderResponseMetadata metadata,
      LatencyBreakdown latency) {
    ProviderResponseMetadata safeMetadata = metadata == null ? ProviderResponseMetadata.empty() : metadata;
    LatencyBreakdown safeLatency = latency == null ? new LatencyBreakdown(startedNanos) : latency;
    long durationMillis = safeLatency.totalMillis();
    long startedUnixNanos = Instant.now().minusMillis(durationMillis).toEpochMilli() * 1_000_000L;
    long endedUnixNanos = Instant.now().toEpochMilli() * 1_000_000L;
    String traceId = GatewayHeaders.value(ctx.headerMap(), "trace-id");
    if (traceId == null || traceId.isBlank()) {
      traceId = UUID.randomUUID().toString();
    }
    String spanId = UUID.randomUUID().toString();
    Map<String, Object> requestOption = new LinkedHashMap<>();
    requestOption.put("providerOptions", Map.of(
        "requestURL", providerRequest.url(),
        "rubeusURL", endpointPath));
    requestOption.put("transformedRequest", Map.of(
        "body", parseJsonValue(objectMapper, providerRequest.body()),
        "headers", sanitizedHeaders(providerRequest.headers())));
    requestOption.put("requestParams", parseJsonValue(objectMapper, providerRequest.body()));
    requestOption.put("finalUntransformedRequest", Map.of(
        "body", parseJsonValue(objectMapper, ctx.body())));
    Object response = parseJsonValue(objectMapper, responseBody);
    requestOption.put("originalResponse", Map.of("body", response));
    requestOption.put("response", response);
    requestOption.put("cacheStatus", cacheStatus);
    requestOption.put("lastUsedOptionIndex", optionIndex);
    requestOption.put("cacheMode", gatewayConfig.cache().mode());
    requestOption.put("cacheMaxAge", gatewayConfig.cache().ttlMillis());
    requestOption.put("hookSpanId", spanId);
    requestOption.put("executionTime", durationMillis);
    requestOption.put("tokenUsage", safeMetadata.tokenUsage());
    requestOption.put("cost", safeMetadata.cost(pricingConfig(gatewayConfig), modelName(objectMapper, providerRequest.body())));
    requestOption.put("responseMetadata", safeMetadata.responseMetadata());
    requestOption.put("latency", safeLatency.toMap(cacheStatus));

    Map<String, Object> log = new LinkedHashMap<>();
    log.put("time", Instant.now().toString());
    log.put("traceId", traceId);
    log.put("spanId", spanId);
    log.put("method", method);
    log.put("endpoint", endpointPath);
    log.put("status", status);
    log.put("duration", durationMillis);
    log.put("tokenUsage", safeMetadata.tokenUsage());
    log.put("cost", safeMetadata.cost(pricingConfig(gatewayConfig), modelName(objectMapper, providerRequest.body())));
    log.put("responseMetadata", safeMetadata.responseMetadata());
    log.put("latency", safeLatency.toMap(cacheStatus));
    log.put("requestOptions", List.of(requestOption));
    log.put("spans", List.of(providerSpan(
        traceId,
        spanId,
        gatewayConfig.provider(),
        endpointPath,
        status,
        startedUnixNanos,
        endedUnixNanos,
        safeMetadata,
        safeLatency)));
    return log;
  }

  private static Map<String, Object> providerSpan(
      String traceId,
      String spanId,
      String provider,
      String endpointPath,
      int status,
      long startedUnixNanos,
      long endedUnixNanos,
      ProviderResponseMetadata metadata,
      LatencyBreakdown latency) {
    ProviderResponseMetadata safeMetadata = metadata == null ? ProviderResponseMetadata.empty() : metadata;
    LatencyBreakdown safeLatency = latency == null ? new LatencyBreakdown(System.nanoTime()) : latency;
    Map<String, Object> span = new LinkedHashMap<>();
    span.put("type", "otlp_span");
    span.put("traceId", traceId);
    span.put("spanId", spanId);
    span.put("name", "provider_request " + (provider == null || provider.isBlank() ? "unknown" : provider));
    span.put("kind", "SPAN_KIND_CLIENT");
    span.put("startTimeUnixNano", String.valueOf(startedUnixNanos));
    span.put("endTimeUnixNano", String.valueOf(endedUnixNanos));
    span.put("status", Map.of("code", status >= 500 ? "STATUS_CODE_ERROR" : "STATUS_CODE_OK"));
    span.put("attributes", List.of(
        otlpStringAttribute("gen_ai.operation.name", "provider_request"),
        otlpStringAttribute("gen_ai.provider.name", provider == null || provider.isBlank() ? "unknown" : provider),
        otlpStringAttribute("http.route", endpointPath),
        otlpStringAttribute("http.response.status_code", String.valueOf(status)),
        otlpStringAttribute("gen_ai.usage.input_tokens", String.valueOf(safeMetadata.promptTokens())),
        otlpStringAttribute("gen_ai.usage.output_tokens", String.valueOf(safeMetadata.completionTokens())),
        otlpStringAttribute("gen_ai.usage.total_tokens", String.valueOf(safeMetadata.totalTokens())),
        otlpStringAttribute("gen_ai.response.finish_reasons", safeMetadata.finishReason()),
        otlpStringAttribute("modelgate.response.tool_call_count", String.valueOf(safeMetadata.toolCallCount())),
        otlpStringAttribute("modelgate.response.refused", String.valueOf(safeMetadata.refused())),
        otlpStringAttribute("modelgate.response.safety_flagged", String.valueOf(safeMetadata.safetyFlagged())),
        otlpStringAttribute("modelgate.latency.provider_ms", String.valueOf(safeLatency.providerMillis()))));
    span.put("events", List.of());
    return span;
  }

  private static Object pricingConfig(GatewayConfig gatewayConfig) {
    Object pricing = gatewayConfig.providerOptions().get("pricing");
    return pricing == null ? gatewayConfig.providerOptions().get("pricingConfig") : pricing;
  }

  private static final class LatencyBreakdown {
    private final long startedNanos;
    private long requestReadNanos;
    private long inputGuardrailsNanos;
    private long cacheLookupNanos;
    private long providerNanos;
    private long outputGuardrailsNanos;
    private long responseWriteNanos;

    private LatencyBreakdown(long startedNanos) {
      this.startedNanos = startedNanos;
    }

    private void addRequestRead(long nanos) {
      requestReadNanos += Math.max(0, nanos);
    }

    private void addInputGuardrails(long nanos) {
      inputGuardrailsNanos += Math.max(0, nanos);
    }

    private void addCacheLookup(long nanos) {
      cacheLookupNanos += Math.max(0, nanos);
    }

    private void addProvider(long nanos) {
      providerNanos += Math.max(0, nanos);
    }

    private void addOutputGuardrails(long nanos) {
      outputGuardrailsNanos += Math.max(0, nanos);
    }

    private void addResponseWrite(long nanos) {
      responseWriteNanos += Math.max(0, nanos);
    }

    private long totalNanos() {
      return Math.max(0, System.nanoTime() - startedNanos);
    }

    private long providerNanos() {
      return providerNanos;
    }

    private long inputGuardrailsNanos() {
      return inputGuardrailsNanos;
    }

    private long outputGuardrailsNanos() {
      return outputGuardrailsNanos;
    }

    private long totalMillis() {
      return millis(totalNanos());
    }

    private long providerMillis() {
      return millis(providerNanos);
    }

    private Map<String, Object> toMap(String cacheStatus) {
      long totalNanos = totalNanos();
      long measuredNanos = requestReadNanos
          + inputGuardrailsNanos
          + cacheLookupNanos
          + providerNanos
          + outputGuardrailsNanos
          + responseWriteNanos;
      Map<String, Object> latency = new LinkedHashMap<>();
      latency.put("total_ms", millis(totalNanos));
      latency.put("request_read_ms", millis(requestReadNanos));
      latency.put("input_guardrails_ms", millis(inputGuardrailsNanos));
      latency.put("cache_lookup_ms", millis(cacheLookupNanos));
      latency.put("provider_ms", "HIT".equals(cacheStatus) ? 0L : millis(providerNanos));
      latency.put("output_guardrails_ms", millis(outputGuardrailsNanos));
      latency.put("response_write_ms", millis(responseWriteNanos));
      latency.put("gateway_overhead_ms", millis(Math.max(0, totalNanos - measuredNanos)));
      latency.put("cache_status", cacheStatus == null ? "DISABLED" : cacheStatus);
      return latency;
    }

    private static long millis(long nanos) {
      return Duration.ofNanos(Math.max(0, nanos)).toMillis();
    }
  }

  private static Map<String, Object> otlpStringAttribute(String key, String value) {
    return Map.of(
        "key", key,
        "value", Map.of("stringValue", value));
  }

  private static Map<String, String> sanitizedHeaders(Map<String, String> headers) {
    Map<String, String> sanitized = new LinkedHashMap<>();
    headers.forEach((key, value) -> {
      if (!isSensitiveHeader(key)) {
        sanitized.put(key, value);
      }
    });
    return Map.copyOf(sanitized);
  }

  private static boolean isSensitiveHeader(String key) {
    return "authorization".equalsIgnoreCase(key)
        || "api-key".equalsIgnoreCase(key)
        || "x-api-key".equalsIgnoreCase(key);
  }

  private static Object parseJsonValue(ObjectMapper objectMapper, String json) {
    if (json == null || json.isBlank()) {
      return "";
    }
    try {
      return objectMapper.readValue(json, Object.class);
    } catch (java.io.IOException exception) {
      return json;
    }
  }

  private static boolean isStreamingRequest(ObjectMapper objectMapper, String body) {
    Object parsed = parseJsonValue(objectMapper, body);
    return parsed instanceof Map<?, ?> requestBody
        && Boolean.TRUE.equals(requestBody.get("stream"));
  }

  private static String requestType(String endpointPath, String method, boolean streaming) {
    String path = pathWithoutQuery(endpointPath).toLowerCase(Locale.ROOT);
    String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
    if (path.equals("/v1/chat/completions")) {
      return streaming ? "stream-chatComplete" : "chatComplete";
    }
    if (path.equals("/v1/completions")) {
      return streaming ? "stream-complete" : "complete";
    }
    if (path.equals("/v1/embeddings")) {
      return "embed";
    }
    if (path.equals("/v1/moderations")) {
      return "moderate";
    }
    if (path.equals("/v1/images/generations")) {
      return "imageGenerate";
    }
    if (path.equals("/v1/audio/speech")) {
      return "createSpeech";
    }
    if (path.equals("/v1/audio/transcriptions")) {
      return "createTranscription";
    }
    if (path.equals("/v1/audio/translations")) {
      return "createTranslation";
    }
    if (path.equals("/v1/files")) {
      return "GET".equals(normalizedMethod) ? "listFiles" : "uploadFile";
    }
    if (path.matches("/v1/files/[^/]+/content")) {
      return "retrieveFileContent";
    }
    if (path.matches("/v1/files/[^/]+")) {
      return "DELETE".equals(normalizedMethod) ? "deleteFile" : "retrieveFile";
    }
    if (path.equals("/v1/batches")) {
      return "GET".equals(normalizedMethod) ? "listBatches" : "createBatch";
    }
    if (path.matches("/v1/batches/[^/]+/cancel")) {
      return "cancelBatch";
    }
    if (path.matches("/v1/batches/[^/]+/output")) {
      return "getBatchOutput";
    }
    if (path.matches("/v1/batches/[^/]+")) {
      return "retrieveBatch";
    }
    if (path.equals("/v1/fine_tuning/jobs")) {
      return "GET".equals(normalizedMethod) ? "listFinetunes" : "createFinetune";
    }
    if (path.matches("/v1/fine_tuning/jobs/[^/]+/cancel")) {
      return "cancelFinetune";
    }
    if (path.matches("/v1/fine_tuning/jobs/[^/]+")) {
      return "retrieveFinetune";
    }
    if (path.equals("/v1/responses")) {
      return "createModelResponse";
    }
    if (path.matches("/v1/responses/[^/]+/input_items")) {
      return "listResponseInputItems";
    }
    if (path.matches("/v1/responses/[^/]+")) {
      return "DELETE".equals(normalizedMethod) ? "deleteModelResponse" : "getModelResponse";
    }
    if (path.equals("/v1/messages/count_tokens")) {
      return "messagesCountTokens";
    }
    if (path.equals("/v1/messages")) {
      return streaming ? "stream-messages" : "messages";
    }
    return "proxy";
  }

  private static String pathWithoutQuery(String endpointPath) {
    if (endpointPath == null) {
      return "";
    }
    int queryIndex = endpointPath.indexOf('?');
    return queryIndex < 0 ? endpointPath : endpointPath.substring(0, queryIndex);
  }

  private static byte[] requestBodyBytes(Context ctx, String method) {
    String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
    if ("GET".equals(normalizedMethod) || "HEAD".equals(normalizedMethod)) {
      return new byte[0];
    }
    return ctx.bodyAsBytes();
  }

  private static boolean isRawBodyRequest(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return false;
    }
    String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    return mediaType.equals("multipart/form-data")
        || mediaType.equals("application/octet-stream")
        || mediaType.startsWith("audio/")
        || mediaType.startsWith("image/");
  }

  private static Map<String, Object> rawBodyParamsForRouting(Map<String, String> headers, byte[] bodyBytes) {
    String contentType = headerValue(headers, "content-type");
    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
      return Map.of();
    }
    String boundary = multipartBoundary(contentType);
    if (boundary.isBlank()) {
      return Map.of();
    }
    Map<String, Object> params = new LinkedHashMap<>();
    String body = new String(bodyBytes == null ? new byte[0] : bodyBytes, StandardCharsets.UTF_8);
    String delimiter = "--" + boundary;
    for (String part : body.split(Pattern.quote(delimiter))) {
      int separator = part.indexOf("\r\n\r\n");
      int separatorLength = 4;
      if (separator < 0) {
        separator = part.indexOf("\n\n");
        separatorLength = 2;
      }
      if (separator < 0) {
        continue;
      }
      String partHeaders = part.substring(0, separator);
      if (partHeaders.toLowerCase(Locale.ROOT).contains("filename=")) {
        continue;
      }
      String name = multipartPartName(partHeaders);
      if (name.isBlank()) {
        continue;
      }
      String value = part.substring(separator + separatorLength)
          .replaceFirst("\\r?\\n--?$", "")
          .trim();
      params.put(name, value);
    }
    return Map.copyOf(params);
  }

  private static String multipartPartName(String partHeaders) {
    java.util.regex.Matcher matcher = Pattern.compile("(?i)\\bname=\"([^\"]+)\"").matcher(partHeaders);
    return matcher.find() ? matcher.group(1) : "";
  }

  private static String multipartBoundary(String contentType) {
    for (String parameter : contentType.split(";")) {
      String trimmed = parameter.trim();
      if (trimmed.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
        String value = trimmed.substring("boundary=".length()).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
          return value.substring(1, value.length() - 1);
        }
        return value;
      }
    }
    return "";
  }

  private static String headerValue(Map<String, String> headers, String name) {
    if (headers == null || headers.isEmpty()) {
      return null;
    }
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static boolean isTextualResponse(ProviderResponse providerResponse) {
    if (providerResponse.streaming()) {
      return true;
    }
    String contentType = providerResponse.headers().get("content-type");
    if (contentType == null || contentType.isBlank()) {
      return true;
    }
    String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    return mediaType.equals("application/json")
        || mediaType.endsWith("+json")
        || mediaType.startsWith("text/")
        || mediaType.equals("application/x-ndjson")
        || mediaType.equals("application/problem+json");
  }

  private static Map<String, Object> parseObject(ObjectMapper objectMapper, String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (java.io.IOException exception) {
      return Map.of();
    }
  }

  private record RoutedProviderResponse(ProviderResponse response, int optionIndex, int providerAttempts) {}

  private static final class RealtimeUpstreamListener implements WebSocket.Listener {
    private final io.javalin.websocket.WsContext client;
    private final RequestMetrics requestMetrics;
    private final StringBuilder partialText = new StringBuilder();

    private RealtimeUpstreamListener(io.javalin.websocket.WsContext client, RequestMetrics requestMetrics) {
      this.client = client;
      this.requestMetrics = requestMetrics;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      WebSocket.Listener.super.onOpen(webSocket);
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partialText.append(data);
      if (last) {
        requestMetrics.recordRealtimeMessage("provider_to_client", "text");
        client.send(partialText.toString());
        partialText.setLength(0);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      if (last) {
        requestMetrics.recordRealtimeMessage("provider_to_client", "binary");
        client.send(data);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      client.closeSession(statusCode, reason);
      return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      client.closeSession(1011, "Realtime provider error");
    }
  }
}
