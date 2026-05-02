package com.modelgate.gateway.provider;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProviderClient {
  private static final int REQUEST_TIMEOUT_STATUS = 408;
  private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 256;
  private static final Set<String> RESTRICTED_REQUEST_HEADERS = Set.of(
      "connection",
      "content-length",
      "expect",
      "host",
      "upgrade");

  private final HttpClient httpClient;
  private final Semaphore outboundPermits;
  private final int maxConcurrentRequests;

  public ProviderClient(HttpClient httpClient) {
    this(httpClient, defaultMaxConcurrentRequests());
  }

  public ProviderClient(HttpClient httpClient, int maxConcurrentRequests) {
    if (maxConcurrentRequests < 1) {
      throw new IllegalArgumentException("maxConcurrentRequests must be at least 1");
    }
    this.httpClient = httpClient;
    this.maxConcurrentRequests = maxConcurrentRequests;
    this.outboundPermits = new Semaphore(maxConcurrentRequests, true);
  }

  public ProviderResponse send(ProviderRequest request) throws InterruptedException {
    int retriesMade = 0;
    while (true) {
      ProviderResponse response = sendOnce(request, retriesMade);
      if (retriesMade >= request.retryPolicy().attempts()
          || !request.retryPolicy().onStatusCodes().contains(response.status())) {
        return response;
      }
      retriesMade++;
      sleepForRetryAfterIfNeeded(response, request.retryPolicy());
    }
  }

  public ProviderResponse sendStreaming(ProviderRequest request) throws InterruptedException {
    int retriesMade = 0;
    while (true) {
      ProviderResponse response = sendStreamingOnce(request, retriesMade);
      if (retriesMade >= request.retryPolicy().attempts()
          || !request.retryPolicy().onStatusCodes().contains(response.status())) {
        return response;
      }
      closeResponseBody(response);
      retriesMade++;
      sleepForRetryAfterIfNeeded(response, request.retryPolicy());
    }
  }

  public WebSocket.Builder webSocketBuilder() {
    return httpClient.newWebSocketBuilder();
  }

  public Map<String, Object> concurrencySnapshot() {
    int available = outboundPermits.availablePermits();
    return Map.of(
        "provider_concurrency_limit", maxConcurrentRequests,
        "provider_concurrency_available", available,
        "provider_concurrency_in_flight", maxConcurrentRequests - available);
  }

  private ProviderResponse sendOnce(ProviderRequest request, int retriesMade) throws InterruptedException {
    outboundPermits.acquire();
    HttpRequest httpRequest = buildRequest(request);
    try {
      HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
      return new ProviderResponse(
          response.statusCode(),
          response.body(),
          flattenHeaders(response),
          retriesMade);
    } catch (IOException exception) {
      if (isTimeout(exception)) {
        return timeoutResponse(retriesMade, request.timeout());
      }
      return new ProviderResponse(500, exception.getMessage(), Map.of(), retriesMade);
    } finally {
      outboundPermits.release();
    }
  }

  private ProviderResponse sendStreamingOnce(ProviderRequest request, int retriesMade) throws InterruptedException {
    outboundPermits.acquire();
    boolean releasePermit = true;
    HttpRequest httpRequest = buildRequest(request);
    try {
      HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
      InputStream bodyStream = new PermitReleasingInputStream(response.body(), outboundPermits::release);
      releasePermit = false;
      return ProviderResponse.streaming(
          response.statusCode(),
          bodyStream,
          flattenHeaders(response),
          retriesMade);
    } catch (IOException exception) {
      if (isTimeout(exception)) {
        return timeoutResponse(retriesMade, request.timeout());
      }
      return new ProviderResponse(500, exception.getMessage(), Map.of(), retriesMade);
    } finally {
      if (releasePermit) {
        outboundPermits.release();
      }
    }
  }

  private HttpRequest buildRequest(ProviderRequest request) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()));
    if (request.timeout() != null) {
      builder.timeout(request.timeout());
    }
    request.headers().forEach((name, value) -> {
      if (!isRestrictedRequestHeader(name)) {
        builder.header(name, value);
      }
    });

    String method = request.method() == null ? "POST" : request.method().toUpperCase(Locale.ROOT);
    if ("GET".equals(method) || "HEAD".equals(method)) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.method(method, HttpRequest.BodyPublishers.ofByteArray(request.bodyBytes()));
    }
    return builder.build();
  }

  private static ProviderResponse timeoutResponse(int retriesMade, Duration timeout) {
    long timeoutMillis = timeout == null ? 0 : timeout.toMillis();
    String body = """
        {"error":{"message":"Request exceeded the timeout sent in the request: %dms","type":"timeout_error","param":null,"code":null}}
        """.formatted(timeoutMillis).trim();
    return new ProviderResponse(REQUEST_TIMEOUT_STATUS, body, Map.of("content-type", "application/json"), retriesMade);
  }

  private static boolean isTimeout(IOException exception) {
    Throwable current = exception;
    while (current != null) {
      if (current instanceof TimeoutException
          || current instanceof java.net.http.HttpTimeoutException
          || current instanceof CompletionException
          || current instanceof ExecutionException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static boolean isRestrictedRequestHeader(String name) {
    return name != null && RESTRICTED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT));
  }

  private static Map<String, String> flattenHeaders(HttpResponse<?> response) {
    Map<String, String> headers = new HashMap<>();
    response.headers().map().forEach((key, values) -> {
      if (!values.isEmpty()) {
        headers.put(key.toLowerCase(Locale.ROOT), values.getFirst());
      }
    });
    return headers;
  }

  private static void closeResponseBody(ProviderResponse response) {
    if (!response.streaming() || response.bodyStream() == null) {
      return;
    }
    try {
      response.bodyStream().close();
    } catch (IOException ignored) {
      // Ignore cleanup failures before retrying another provider request.
    }
  }

  private static void sleepForRetryAfterIfNeeded(ProviderResponse response, RetryPolicy retryPolicy)
      throws InterruptedException {
    if (!retryPolicy.useRetryAfterHeader() || response.status() != 429) {
      return;
    }
    String retryAfterMillis = response.headers().get("retry-after-ms");
    if (retryAfterMillis == null) {
      retryAfterMillis = response.headers().get("x-ms-retry-after-ms");
    }
    if (retryAfterMillis != null) {
      sleepIfValid(retryAfterMillis, 1);
      return;
    }
    String retryAfterSeconds = response.headers().get("retry-after");
    if (retryAfterSeconds != null) {
      sleepIfValid(retryAfterSeconds, 1000);
    }
  }

  private static void sleepIfValid(String value, long multiplier) throws InterruptedException {
    try {
      long delayMillis = Long.parseLong(value.trim()) * multiplier;
      if (delayMillis > 0) {
        Thread.sleep(delayMillis);
      }
    } catch (NumberFormatException ignored) {
      // Ignore malformed provider retry headers and continue with ordinary retry behavior.
    }
  }

  private static int defaultMaxConcurrentRequests() {
    String configured = System.getProperty("modelgate.provider.maxConcurrentRequests");
    if (configured == null || configured.isBlank()) {
      configured = System.getenv("MODELGATE_PROVIDER_MAX_CONCURRENT_REQUESTS");
    }
    if (configured == null || configured.isBlank()) {
      return DEFAULT_MAX_CONCURRENT_REQUESTS;
    }
    try {
      int value = Integer.parseInt(configured.trim());
      return value < 1 ? DEFAULT_MAX_CONCURRENT_REQUESTS : value;
    } catch (NumberFormatException ignored) {
      return DEFAULT_MAX_CONCURRENT_REQUESTS;
    }
  }

  private static final class PermitReleasingInputStream extends FilterInputStream {
    private final Runnable release;
    private final AtomicBoolean released = new AtomicBoolean();

    private PermitReleasingInputStream(InputStream delegate, Runnable release) {
      super(delegate);
      this.release = release;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value == -1) {
        releaseOnce();
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int count = super.read(buffer, offset, length);
      if (count == -1) {
        releaseOnce();
      }
      return count;
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } finally {
        releaseOnce();
      }
    }

    private void releaseOnce() {
      if (released.compareAndSet(false, true)) {
        release.run();
      }
    }
  }
}
