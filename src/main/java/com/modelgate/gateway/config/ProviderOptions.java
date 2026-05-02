package com.modelgate.gateway.config;

import java.util.Map;

public record ProviderOptions(Map<String, Object> asMap) {
  public static final ProviderOptions EMPTY = new ProviderOptions(Map.of());

  public ProviderOptions {
    asMap = asMap == null ? Map.of() : Map.copyOf(asMap);
  }

  public Object get(String key) {
    return asMap.get(key);
  }

  public String stringValue(String key) {
    Object value = asMap.get(key);
    return value == null ? null : String.valueOf(value);
  }

  public boolean isEmpty() {
    return asMap.isEmpty();
  }
}
