package com.modelgate.gateway.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedPluginRegistryTest {
  @Test
  void blocksPluginExecutionBeyondConfiguredLimit() throws Exception {
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger activeDelegates = new AtomicInteger();
    AtomicInteger maxActiveDelegates = new AtomicInteger();
    PluginRegistry slowDelegate = (pluginId, context, parameters) -> {
      int active = activeDelegates.incrementAndGet();
      maxActiveDelegates.accumulateAndGet(active, Math::max);
      firstEntered.countDown();
      await(releaseFirst);
      activeDelegates.decrementAndGet();
      return PluginResult.pass();
    };
    BoundedPluginRegistry registry = new BoundedPluginRegistry(slowDelegate, 1);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<PluginResult> first = CompletableFuture.supplyAsync(
          () -> registry.execute("default.any", HookContext.forResponseText("first"), Map.of()),
          executor);
      assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

      CompletableFuture<PluginResult> second = CompletableFuture.supplyAsync(
          () -> registry.execute("default.any", HookContext.forResponseText("second"), Map.of()),
          executor);
      Thread.sleep(100);

      assertThat(second).isNotDone();
      assertThat(registry.concurrencySnapshot()).containsEntry("plugin_concurrency_available", 0);
      assertThat(registry.concurrencySnapshot()).containsEntry("plugin_concurrency_in_flight", 1);

      releaseFirst.countDown();

      assertThat(first.get(1, TimeUnit.SECONDS).verdict()).isTrue();
      assertThat(second.get(1, TimeUnit.SECONDS).verdict()).isTrue();
      assertThat(maxActiveDelegates).hasValue(1);
    }
  }

  @Test
  void releasesPermitWhenDelegateThrows() {
    AtomicInteger calls = new AtomicInteger();
    PluginRegistry flakyDelegate = (pluginId, context, parameters) -> {
      if (calls.incrementAndGet() == 1) {
        throw new IllegalStateException("plugin failed");
      }
      return PluginResult.pass();
    };
    BoundedPluginRegistry registry = new BoundedPluginRegistry(flakyDelegate, 1);

    assertThatThrownBy(() -> registry.execute("default.any", HookContext.forResponseText("first"), Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("plugin failed");

    PluginResult result = registry.execute("default.any", HookContext.forResponseText("second"), Map.of());

    assertThat(result.verdict()).isTrue();
    assertThat(registry.concurrencySnapshot()).containsEntry("plugin_concurrency_available", 1);
    assertThat(registry.concurrencySnapshot()).containsEntry("plugin_concurrency_in_flight", 0);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(1, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for latch");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }
}
