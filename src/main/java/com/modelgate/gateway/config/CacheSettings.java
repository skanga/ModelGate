package com.modelgate.gateway.config;

public record CacheSettings(String mode, boolean enabled, long ttlMillis) {
  public CacheSettings(boolean enabled, long ttlMillis) {
    this(enabled ? "simple" : "DISABLED", enabled, ttlMillis);
  }

  public CacheSettings {
    mode = mode == null || mode.isBlank() ? "DISABLED" : mode;
  }

  public static CacheSettings defaults() {
    return new CacheSettings("DISABLED", false, 0);
  }
}
