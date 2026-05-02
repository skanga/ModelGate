package com.modelgate.gateway.provider;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

public record ProviderResponse(
    int status,
    String body,
    byte[] bodyBytes,
    InputStream bodyStream,
    Map<String, String> headers,
    int attempts,
    boolean streaming) {
  public ProviderResponse(int status, String body, Map<String, String> headers, int attempts) {
    this(status, body, bytesFromBody(body), null, headers, attempts, false);
  }

  public ProviderResponse(int status, byte[] bodyBytes, Map<String, String> headers, int attempts) {
    this(status, textFromBytes(bodyBytes), bodyBytes, null, headers, attempts, false);
  }

  public static ProviderResponse streaming(
      int status,
      InputStream bodyStream,
      Map<String, String> headers,
      int attempts) {
    return new ProviderResponse(status, "", new byte[0], bodyStream, headers, attempts, true);
  }

  public ProviderResponse {
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    body = body == null ? "" : body;
    bodyBytes = bodyBytes == null ? bytesFromBody(body) : Arrays.copyOf(bodyBytes, bodyBytes.length);
  }

  public byte[] bodyBytes() {
    return Arrays.copyOf(bodyBytes, bodyBytes.length);
  }

  public ProviderResponse withBody(String nextBody) {
    return new ProviderResponse(status, nextBody, headers, attempts);
  }

  public ProviderResponse withStatus(int nextStatus) {
    return new ProviderResponse(nextStatus, body, bodyBytes, bodyStream, headers, attempts, streaming);
  }

  private static byte[] bytesFromBody(String body) {
    return body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
  }

  private static String textFromBytes(byte[] bodyBytes) {
    return bodyBytes == null ? "" : new String(bodyBytes, StandardCharsets.UTF_8);
  }
}
