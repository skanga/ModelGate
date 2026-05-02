package com.modelgate.gateway.http;

import com.modelgate.gateway.cache.CacheStatus;
import com.modelgate.gateway.observability.ProviderResponseMetadata;
import com.modelgate.gateway.plugins.BoundedPluginRegistry;
import com.modelgate.gateway.provider.ProviderClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class RequestMetrics {
  private final PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
  private final LongAdder totalRequests = new LongAdder();
  private final LongAdder status2xx = new LongAdder();
  private final LongAdder status3xx = new LongAdder();
  private final LongAdder status4xx = new LongAdder();
  private final LongAdder status5xx = new LongAdder();
  private final LongAdder validationFailures = new LongAdder();
  private final LongAdder providerRequests = new LongAdder();
  private final LongAdder providerAttempts = new LongAdder();
  private final LongAdder cacheHits = new LongAdder();
  private final LongAdder cacheMisses = new LongAdder();
  private final LongAdder cacheRefreshes = new LongAdder();
  private final LongAdder providerPromptTokens = new LongAdder();
  private final LongAdder providerCompletionTokens = new LongAdder();
  private final LongAdder providerTotalTokens = new LongAdder();
  private final LongAdder providerReasoningTokens = new LongAdder();
  private final LongAdder providerCachedTokens = new LongAdder();
  private final LongAdder durationTotalNanos = new LongAdder();
  private final AtomicLong durationMaxNanos = new AtomicLong();
  private final Counter prometheusTotalRequests = prometheusRegistry.counter("modelgate.requests");
  private final Counter prometheusValidationFailures = prometheusRegistry.counter("modelgate.validation.failures");
  private final Counter prometheusProviderRequests = prometheusRegistry.counter("modelgate.provider.requests");
  private final Counter prometheusProviderAttempts = prometheusRegistry.counter("modelgate.provider.attempts");
  private final Counter prometheusCacheHits = prometheusRegistry.counter("modelgate.cache.hits");
  private final Counter prometheusCacheMisses = prometheusRegistry.counter("modelgate.cache.misses");
  private final Counter prometheusCacheRefreshes = prometheusRegistry.counter("modelgate.cache.refreshes");
  private final Timer prometheusRequestDuration = prometheusRegistry.timer("modelgate.request.duration");
  private final Counter prometheusRealtimeConnections = prometheusRegistry.counter("modelgate.realtime.connections");

  public RequestMetrics() {
    bindRuntimeGauges();
  }

  public void record(
      int status,
      long durationNanos,
      boolean validationFailure,
      boolean providerRequest,
      CacheStatus cacheStatus) {
    record(status, durationNanos, validationFailure, providerRequest, providerRequest ? 1 : 0, cacheStatus);
  }

  public void record(
      int status,
      long durationNanos,
      boolean validationFailure,
      boolean providerRequest,
      int providerAttemptCount,
      CacheStatus cacheStatus) {
    totalRequests.increment();
    incrementStatus(status);
    if (validationFailure) {
      validationFailures.increment();
      prometheusValidationFailures.increment();
    }
    if (providerRequest) {
      providerRequests.increment();
      prometheusProviderRequests.increment();
    }
    providerAttempts.add(Math.max(0, providerAttemptCount));
    if (providerAttemptCount > 0) {
      prometheusProviderAttempts.increment(providerAttemptCount);
    }
    if (cacheStatus == CacheStatus.HIT) {
      cacheHits.increment();
      prometheusCacheHits.increment();
    } else if (cacheStatus == CacheStatus.MISS) {
      cacheMisses.increment();
      prometheusCacheMisses.increment();
    } else if (cacheStatus == CacheStatus.REFRESH) {
      cacheRefreshes.increment();
      prometheusCacheRefreshes.increment();
    }
    durationTotalNanos.add(Math.max(0, durationNanos));
    updateMaxDuration(Math.max(0, durationNanos));
    prometheusTotalRequests.increment();
    prometheusRegistry.counter("modelgate.requests.status", "status_class", statusClass(status)).increment();
    prometheusRequestDuration.record(Math.max(0, durationNanos), java.util.concurrent.TimeUnit.NANOSECONDS);
  }

  public void recordProviderObservation(
      String endpoint,
      String provider,
      String model,
      int status,
      CacheStatus cacheStatus,
      long durationNanos) {
    recordProviderObservation(endpoint, provider, model, status, cacheStatus, durationNanos, ProviderResponseMetadata.empty());
  }

  public void recordProviderObservation(
      String endpoint,
      String provider,
      String model,
      int status,
      CacheStatus cacheStatus,
      long durationNanos,
      ProviderResponseMetadata metadata) {
    ProviderResponseMetadata safeMetadata = metadata == null ? ProviderResponseMetadata.empty() : metadata;
    Timer.builder("modelgate.provider.request.duration")
        .tags(
            "provider", valueOrUnknown(provider),
            "model", valueOrUnknown(model),
            "endpoint", valueOrUnknown(endpoint),
            "status_class", statusClass(status),
            "cache_status", cacheStatus == null ? "DISABLED" : cacheStatus.name())
        .register(prometheusRegistry)
        .record(Math.max(0, durationNanos), java.util.concurrent.TimeUnit.NANOSECONDS);
    addTokens("prompt", safeMetadata.promptTokens());
    addTokens("completion", safeMetadata.completionTokens());
    addTokens("total", safeMetadata.totalTokens());
    addTokens("reasoning", safeMetadata.reasoningTokens());
    addTokens("cached", safeMetadata.cachedTokens());
  }

  public void recordRealtimeConnection() {
    prometheusRealtimeConnections.increment();
  }

  public void recordRealtimeMessage(String direction, String messageType) {
    prometheusRegistry.counter(
            "modelgate.realtime.messages",
            "direction", valueOrUnknown(direction),
            "message_type", valueOrUnknown(messageType))
        .increment();
  }

  public Map<String, Object> snapshot() {
    return Map.ofEntries(
        Map.entry("service", "modelgate"),
        Map.entry("total_requests", totalRequests.sum()),
        Map.entry("status_2xx", status2xx.sum()),
        Map.entry("status_3xx", status3xx.sum()),
        Map.entry("status_4xx", status4xx.sum()),
        Map.entry("status_5xx", status5xx.sum()),
        Map.entry("validation_failures", validationFailures.sum()),
        Map.entry("provider_requests", providerRequests.sum()),
        Map.entry("provider_attempts", providerAttempts.sum()),
        Map.entry("provider_prompt_tokens", providerPromptTokens.sum()),
        Map.entry("provider_completion_tokens", providerCompletionTokens.sum()),
        Map.entry("provider_total_tokens", providerTotalTokens.sum()),
        Map.entry("provider_reasoning_tokens", providerReasoningTokens.sum()),
        Map.entry("provider_cached_tokens", providerCachedTokens.sum()),
        Map.entry("cache_hits", cacheHits.sum()),
        Map.entry("cache_misses", cacheMisses.sum()),
        Map.entry("cache_refreshes", cacheRefreshes.sum()),
        Map.entry("duration_total_ms", durationTotalNanos.sum() / 1_000_000L),
        Map.entry("duration_max_ms", durationMaxNanos.get() / 1_000_000L));
  }

  public String prometheusScrape(String acceptHeader) {
    if (acceptHeader != null && acceptHeader.toLowerCase(java.util.Locale.ROOT).contains("application/openmetrics-text")) {
      return prometheusRegistry.scrape("application/openmetrics-text");
    }
    return prometheusRegistry.scrape();
  }

  public void bindProviderClient(ProviderClient providerClient) {
    Gauge.builder("modelgate.provider.concurrency.limit", providerClient, client -> metricValue(client, "provider_concurrency_limit"))
        .register(prometheusRegistry);
    Gauge.builder("modelgate.provider.concurrency.available", providerClient, client -> metricValue(client, "provider_concurrency_available"))
        .register(prometheusRegistry);
    Gauge.builder("modelgate.provider.concurrency.in.flight", providerClient, client -> metricValue(client, "provider_concurrency_in_flight"))
        .register(prometheusRegistry);
  }

  public void bindPluginRegistry(BoundedPluginRegistry pluginRegistry) {
    Gauge.builder("modelgate.plugin.concurrency.limit", pluginRegistry, registry -> metricValue(registry, "plugin_concurrency_limit"))
        .register(prometheusRegistry);
    Gauge.builder("modelgate.plugin.concurrency.available", pluginRegistry, registry -> metricValue(registry, "plugin_concurrency_available"))
        .register(prometheusRegistry);
    Gauge.builder("modelgate.plugin.concurrency.in.flight", pluginRegistry, registry -> metricValue(registry, "plugin_concurrency_in_flight"))
        .register(prometheusRegistry);
  }

  private void bindRuntimeGauges() {
    RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    Gauge.builder("modelgate.virtual.threads.enabled", () -> 1)
        .register(prometheusRegistry);
    Gauge.builder("modelgate.available.processors", Runtime.getRuntime(), Runtime::availableProcessors)
        .register(prometheusRegistry);
    Gauge.builder("modelgate.jvm.thread.count", threads, ThreadMXBean::getThreadCount)
        .register(prometheusRegistry);
    Gauge.builder("modelgate.jvm.daemon.thread.count", threads, ThreadMXBean::getDaemonThreadCount)
        .register(prometheusRegistry);
    Gauge.builder("modelgate.jvm.peak.thread.count", threads, ThreadMXBean::getPeakThreadCount)
        .register(prometheusRegistry);
    Gauge.builder("modelgate.uptime", runtime, RuntimeMXBean::getUptime)
        .baseUnit("milliseconds")
        .register(prometheusRegistry);
  }

  private void incrementStatus(int status) {
    if (status >= 200 && status < 300) {
      status2xx.increment();
    } else if (status >= 300 && status < 400) {
      status3xx.increment();
    } else if (status >= 400 && status < 500) {
      status4xx.increment();
    } else if (status >= 500) {
      status5xx.increment();
    }
  }

  private void updateMaxDuration(long durationNanos) {
    durationMaxNanos.accumulateAndGet(durationNanos, Math::max);
  }

  private void addTokens(String tokenType, int tokens) {
    if (tokens <= 0) {
      return;
    }
    switch (tokenType) {
      case "prompt" -> providerPromptTokens.add(tokens);
      case "completion" -> providerCompletionTokens.add(tokens);
      case "total" -> providerTotalTokens.add(tokens);
      case "reasoning" -> providerReasoningTokens.add(tokens);
      case "cached" -> providerCachedTokens.add(tokens);
      default -> {
      }
    }
    prometheusRegistry.counter("modelgate.provider.tokens", "token_type", tokenType).increment(tokens);
  }

  private static String statusClass(int status) {
    if (status >= 200 && status < 300) {
      return "2xx";
    }
    if (status >= 300 && status < 400) {
      return "3xx";
    }
    if (status >= 400 && status < 500) {
      return "4xx";
    }
    if (status >= 500) {
      return "5xx";
    }
    return "other";
  }

  private static double metricValue(ProviderClient providerClient, String key) {
    Object value = providerClient.concurrencySnapshot().get(key);
    return value instanceof Number number ? number.doubleValue() : 0;
  }

  private static double metricValue(BoundedPluginRegistry pluginRegistry, String key) {
    Object value = pluginRegistry.concurrencySnapshot().get(key);
    return value instanceof Number number ? number.doubleValue() : 0;
  }

  private static String valueOrUnknown(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }
}
