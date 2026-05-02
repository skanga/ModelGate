package com.modelgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modelgate.gateway.config.GatewayRuntimeConfig;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GatewayServerTest {
  @Test
  void resolvesDefaultPortWhenNoConfigurationIsProvided() {
    assertThat(GatewayServer.resolvePort(Map.of(), new String[0])).isEqualTo(8787);
  }

  @Test
  void resolvesPortFromEnvironment() {
    assertThat(GatewayServer.resolvePort(Map.of("PORT", "9090"), new String[0])).isEqualTo(9090);
  }

  @Test
  void commandLinePortOverridesEnvironment() {
    assertThat(GatewayServer.resolvePort(Map.of("PORT", "9090"), new String[] {"--port", "9191"}))
        .isEqualTo(9191);
  }

  @Test
  void rejectsInvalidPortValues() {
    assertThatThrownBy(() -> GatewayServer.resolvePort(Map.of("PORT", "0"), new String[0]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Port must be between 1 and 65535");
  }

  @Test
  void runStartsResolvedPortAndBlocksUntilShutdown() throws Exception {
    AtomicInteger startedPort = new AtomicInteger();
    AtomicBoolean blocked = new AtomicBoolean();

    GatewayServer.run(
        Map.of("PORT", "9090"),
        new String[] {"--port", "9191"},
        startedPort::set,
        () -> blocked.set(true));

    assertThat(startedPort).hasValue(9191);
    assertThat(blocked).isTrue();
  }

  @Test
  void runPassesRuntimeConfigFromEnvironmentToStarter() {
    AtomicInteger startedPort = new AtomicInteger();
    AtomicInteger maxConcurrentProviderRequests = new AtomicInteger();

    GatewayServer.run(
        Map.of(
            "PORT", "9090",
            "MODELGATE_PROVIDER_MAX_CONCURRENT_REQUESTS", "19"),
        new String[0],
        (port, runtimeConfig) -> {
          startedPort.set(port);
          maxConcurrentProviderRequests.set(runtimeConfig.maxConcurrentProviderRequests());
        },
        () -> {});

    assertThat(startedPort).hasValue(9090);
    assertThat(maxConcurrentProviderRequests).hasValue(19);
  }
}
