package com.modelgate.gateway.plugins;

import java.util.Map;
import java.util.concurrent.Semaphore;

public final class BoundedPluginRegistry implements PluginRegistry {
  private final PluginRegistry delegate;
  private final Semaphore permits;
  private final int maxConcurrentRequests;

  public BoundedPluginRegistry(PluginRegistry delegate, int maxConcurrentRequests) {
    if (delegate == null) {
      throw new IllegalArgumentException("delegate plugin registry is required");
    }
    if (maxConcurrentRequests < 1) {
      throw new IllegalArgumentException("maxConcurrentRequests must be at least 1");
    }
    this.delegate = delegate;
    this.maxConcurrentRequests = maxConcurrentRequests;
    this.permits = new Semaphore(maxConcurrentRequests, true);
  }

  @Override
  public PluginResult execute(String pluginId, HookContext context, Map<String, ?> parameters) {
    try {
      permits.acquire();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new PluginResult(
          false,
          "Interrupted while waiting for plugin execution capacity",
          Map.of("plugin_concurrency_limit", maxConcurrentRequests),
          false,
          null);
    }
    try {
      return delegate.execute(pluginId, context, parameters);
    } finally {
      permits.release();
    }
  }

  public Map<String, Object> concurrencySnapshot() {
    int available = permits.availablePermits();
    return Map.of(
        "plugin_concurrency_limit", maxConcurrentRequests,
        "plugin_concurrency_available", available,
        "plugin_concurrency_in_flight", maxConcurrentRequests - available);
  }
}
