package com.modelgate.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayRuntimeConfigTest {
  @Test
  void defaultsProviderConcurrencyToSafeBound() {
    GatewayRuntimeConfig config = GatewayRuntimeConfig.fromEnvironment(Map.of());

    assertThat(config.maxConcurrentProviderRequests()).isEqualTo(256);
    assertThat(config.maxConcurrentPluginRequests()).isEqualTo(256);
  }

  @Test
  void parsesProviderConcurrencyFromEnvironment() {
    GatewayRuntimeConfig config = GatewayRuntimeConfig.fromEnvironment(
        Map.of(
            "MODELGATE_PROVIDER_MAX_CONCURRENT_REQUESTS", "17",
            "MODELGATE_PLUGIN_MAX_CONCURRENT_REQUESTS", "11"));

    assertThat(config.maxConcurrentProviderRequests()).isEqualTo(17);
    assertThat(config.maxConcurrentPluginRequests()).isEqualTo(11);
  }

  @Test
  void parsesTrustedCustomHostsFromEnvironment() {
    GatewayRuntimeConfig config = GatewayRuntimeConfig.fromEnvironment(Map.of(
        "MODELGATE_PROVIDER_MAX_CONCURRENT_REQUESTS", "17",
        "MODELGATE_TRUSTED_CUSTOM_HOSTS", "10.0.0.2, internal-models.example.com ,"));

    assertThat(config.maxConcurrentProviderRequests()).isEqualTo(17);
    assertThat(config.trustedCustomHosts()).containsExactlyInAnyOrder(
        "10.0.0.2",
        "internal-models.example.com");
  }

  @Test
  void parsesRedisCacheUrlFromEnvironment() {
    GatewayRuntimeConfig config = GatewayRuntimeConfig.fromEnvironment(Map.of(
        "MODELGATE_REDIS_URL", "redis://cache.example.com:6380/2"));

    assertThat(config.redisCacheUrl()).hasToString("redis://cache.example.com:6380/2");
  }

  @Test
  void parsesOpenTelemetrySettingsFromEnvironment() {
    GatewayRuntimeConfig config = GatewayRuntimeConfig.fromEnvironment(Map.of(
        "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://collector.example.com/custom-traces",
        "OTEL_SERVICE_NAME", "modelgate-edge"));

    assertThat(config.openTelemetry().enabled()).isTrue();
    assertThat(config.openTelemetry().traceEndpoint().toString()).isEqualTo("http://collector.example.com/custom-traces");
    assertThat(config.openTelemetry().serviceName()).isEqualTo("modelgate-edge");
  }

  @Test
  void ignoresInvalidProviderConcurrencyEnvironmentValue() {
    GatewayRuntimeConfig config = GatewayRuntimeConfig.fromEnvironment(
        Map.of(
            "MODELGATE_PROVIDER_MAX_CONCURRENT_REQUESTS", "0",
            "MODELGATE_PLUGIN_MAX_CONCURRENT_REQUESTS", "not-a-number"));

    assertThat(config.maxConcurrentProviderRequests()).isEqualTo(256);
    assertThat(config.maxConcurrentPluginRequests()).isEqualTo(256);
  }
}
