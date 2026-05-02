package com.modelgate.gateway.headers;

import java.util.Map;

public final class GatewayHeaders {
  public static final String MODELGATE_PREFIX = "x-modelgate-";
  public static final String PORTKEY_PREFIX = "x-portkey-";

  private GatewayHeaders() {}

  public static String modelgate(String suffix) {
    return MODELGATE_PREFIX + suffix;
  }

  public static String portkey(String suffix) {
    return PORTKEY_PREFIX + suffix;
  }

  public static String value(Map<String, String> headers, String suffix) {
    String modelgateValue = valueExactIgnoreCase(headers, modelgate(suffix));
    if (modelgateValue != null) {
      return modelgateValue;
    }
    return valueExactIgnoreCase(headers, portkey(suffix));
  }

  public static String valueOrDefault(Map<String, String> headers, String suffix, String defaultValue) {
    String value = value(headers, suffix);
    return value == null ? defaultValue : value;
  }

  public static boolean contains(Map<String, String> headers, String suffix) {
    return value(headers, suffix) != null;
  }

  public static boolean isGatewayControlHeader(String headerName) {
    String normalized = normalizeName(headerName);
    return normalized.startsWith(MODELGATE_PREFIX) || normalized.startsWith(PORTKEY_PREFIX);
  }

  public static boolean isForwardHeadersHeader(String headerName) {
    String normalized = normalizeName(headerName);
    return normalized.equals(modelgate("forward-headers")) || normalized.equals(portkey("forward-headers"));
  }

  private static String valueExactIgnoreCase(Map<String, String> headers, String name) {
    if (headers == null || headers.isEmpty()) {
      return null;
    }
    String direct = headers.get(name);
    if (direct != null) {
      return direct;
    }
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static String normalizeName(String headerName) {
    return headerName == null ? "" : headerName.trim().toLowerCase(java.util.Locale.ROOT);
  }
}
