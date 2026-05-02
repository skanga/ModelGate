package com.modelgate.gateway.provider;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

public record ProviderRequest(
    String url,
    String method,
    Map<String, String> headers,
    String body,
    byte[] bodyBytes,
    RetryPolicy retryPolicy,
    Duration timeout) {
  public ProviderRequest(
      String url,
      String method,
      Map<String, String> headers,
      String body,
      RetryPolicy retryPolicy,
      Duration timeout) {
    this(url, method, headers, body, bytesFromBody(body), retryPolicy, timeout);
  }

  public ProviderRequest {
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    body = body == null ? "" : body;
    bodyBytes = bodyBytes == null ? bytesFromBody(body) : Arrays.copyOf(bodyBytes, bodyBytes.length);
    retryPolicy = retryPolicy == null ? new RetryPolicy(0, java.util.List.of(), false) : retryPolicy;
  }

  public byte[] bodyBytes() {
    return Arrays.copyOf(bodyBytes, bodyBytes.length);
  }

  private static byte[] bytesFromBody(String body) {
    return body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
  }
}
