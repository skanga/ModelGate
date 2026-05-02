package com.modelgate.gateway.config;

import com.modelgate.gateway.observability.OpenTelemetrySettings;
import java.net.IDN;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record GatewayRuntimeConfig(
    int maxConcurrentProviderRequests,
    int maxConcurrentPluginRequests,
    Set<String> trustedCustomHosts,
    OpenTelemetrySettings openTelemetry,
    URI redisCacheUrl) {
  public static final int DEFAULT_MAX_CONCURRENT_PROVIDER_REQUESTS = 256;
  public static final int DEFAULT_MAX_CONCURRENT_PLUGIN_REQUESTS = 256;
  private static final String PROVIDER_CONCURRENCY_KEY = "MODELGATE_PROVIDER_MAX_CONCURRENT_REQUESTS";
  private static final String PLUGIN_CONCURRENCY_KEY = "MODELGATE_PLUGIN_MAX_CONCURRENT_REQUESTS";
  private static final String TRUSTED_CUSTOM_HOSTS_KEY = "MODELGATE_TRUSTED_CUSTOM_HOSTS";
  private static final String REDIS_URL_KEY = "MODELGATE_REDIS_URL";

  public GatewayRuntimeConfig {
    if (maxConcurrentProviderRequests < 1) {
      maxConcurrentProviderRequests = DEFAULT_MAX_CONCURRENT_PROVIDER_REQUESTS;
    }
    if (maxConcurrentPluginRequests < 1) {
      maxConcurrentPluginRequests = DEFAULT_MAX_CONCURRENT_PLUGIN_REQUESTS;
    }
    trustedCustomHosts = normalizeTrustedHosts(trustedCustomHosts);
    openTelemetry = openTelemetry == null ? OpenTelemetrySettings.disabled() : openTelemetry;
    redisCacheUrl = normalizeRedisUrl(redisCacheUrl);
  }

  public GatewayRuntimeConfig(
      int maxConcurrentProviderRequests,
      Set<String> trustedCustomHosts,
      OpenTelemetrySettings openTelemetry,
      URI redisCacheUrl) {
    this(
        maxConcurrentProviderRequests,
        DEFAULT_MAX_CONCURRENT_PLUGIN_REQUESTS,
        trustedCustomHosts,
        openTelemetry,
        redisCacheUrl);
  }

  public GatewayRuntimeConfig(
      int maxConcurrentProviderRequests,
      Set<String> trustedCustomHosts,
      OpenTelemetrySettings openTelemetry) {
    this(
        maxConcurrentProviderRequests,
        DEFAULT_MAX_CONCURRENT_PLUGIN_REQUESTS,
        trustedCustomHosts,
        openTelemetry,
        null);
  }

  public GatewayRuntimeConfig(int maxConcurrentProviderRequests) {
    this(
        maxConcurrentProviderRequests,
        DEFAULT_MAX_CONCURRENT_PLUGIN_REQUESTS,
        Set.of(),
        OpenTelemetrySettings.disabled(),
        null);
  }

  public GatewayRuntimeConfig(int maxConcurrentProviderRequests, int maxConcurrentPluginRequests) {
    this(
        maxConcurrentProviderRequests,
        maxConcurrentPluginRequests,
        Set.of(),
        OpenTelemetrySettings.disabled(),
        null);
  }

  public GatewayRuntimeConfig(int maxConcurrentProviderRequests, Set<String> trustedCustomHosts) {
    this(
        maxConcurrentProviderRequests,
        DEFAULT_MAX_CONCURRENT_PLUGIN_REQUESTS,
        trustedCustomHosts,
        OpenTelemetrySettings.disabled(),
        null);
  }

  public static GatewayRuntimeConfig defaults() {
    return new GatewayRuntimeConfig(
        DEFAULT_MAX_CONCURRENT_PROVIDER_REQUESTS,
        DEFAULT_MAX_CONCURRENT_PLUGIN_REQUESTS,
        Set.of(),
        OpenTelemetrySettings.disabled(),
        null);
  }

  public static GatewayRuntimeConfig fromEnvironment(Map<String, String> environment) {
    if (environment == null || environment.isEmpty()) {
      return defaults();
    }
    return new GatewayRuntimeConfig(
        parsePositiveInt(environment.get(PROVIDER_CONCURRENCY_KEY), DEFAULT_MAX_CONCURRENT_PROVIDER_REQUESTS),
        parsePositiveInt(environment.get(PLUGIN_CONCURRENCY_KEY), DEFAULT_MAX_CONCURRENT_PLUGIN_REQUESTS),
        parseTrustedHosts(environment.get(TRUSTED_CUSTOM_HOSTS_KEY)),
        OpenTelemetrySettings.fromEnvironment(environment),
        parseRedisUrl(environment.get(REDIS_URL_KEY)));
  }

  private static int parsePositiveInt(String value, int defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed < 1 ? defaultValue : parsed;
    } catch (NumberFormatException exception) {
      return defaultValue;
    }
  }

  private static Set<String> parseTrustedHosts(String value) {
    if (value == null || value.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(item -> !item.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> normalizeTrustedHosts(Set<String> hosts) {
    if (hosts == null || hosts.isEmpty()) {
      return Set.of();
    }
    return hosts.stream()
        .map(GatewayRuntimeConfig::normalizeHost)
        .filter(host -> !host.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static URI parseRedisUrl(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return URI.create(value.trim());
  }

  private static URI normalizeRedisUrl(URI redisUrl) {
    if (redisUrl == null) {
      return null;
    }
    String scheme = redisUrl.getScheme();
    if (scheme == null || (!"redis".equalsIgnoreCase(scheme) && !"rediss".equalsIgnoreCase(scheme))) {
      throw new IllegalArgumentException("Redis cache URL must use redis:// or rediss://");
    }
    return redisUrl;
  }

  private static String normalizeHost(String host) {
    String trimmed = host == null ? "" : host.trim();
    if (trimmed.endsWith(".")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    if (trimmed.isEmpty()) {
      return "";
    }
    return IDN.toASCII(trimmed, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
  }
}
