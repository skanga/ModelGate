package com.modelgate.gateway.cache;

import com.modelgate.gateway.headers.GatewayHeaders;
import java.util.Locale;
import java.util.Map;

public record CacheRequest(String url, String body, Map<String, String> headers) {
  public CacheRequest {
    body = body == null ? "" : body;
    headers = normalize(headers);
  }

  public boolean forceRefresh() {
    return GatewayHeaders.contains(headers, "cache-force-refresh");
  }

  private static Map<String, String> normalize(Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    return headers.entrySet().stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(
            entry -> entry.getKey().toLowerCase(Locale.ROOT),
            Map.Entry::getValue,
            (left, right) -> right));
  }
}
