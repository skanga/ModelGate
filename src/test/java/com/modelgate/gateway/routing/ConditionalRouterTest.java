package com.modelgate.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modelgate.gateway.config.GatewayConfig;
import com.modelgate.gateway.config.Strategy;
import com.modelgate.gateway.config.StrategyMode;
import com.modelgate.gateway.config.Target;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConditionalRouterTest {
  @Test
  void resolvesFirstTargetWhoseQueryMatchesMetadataAndParams() {
    GatewayConfig config = GatewayConfig.builder()
        .strategy(new Strategy(
            StrategyMode.CONDITIONAL,
            List.of(),
            List.of(
                new Strategy.Condition(Map.of("metadata.tier", Map.of("$eq", "gold")), "premium"),
                new Strategy.Condition(Map.of("params.model", Map.of("$regex", "mini$")), "cheap")),
            "default"))
        .targets(List.of(
            Target.builder().name("premium").provider("openai").apiKey("sk-premium").build(),
            Target.builder().name("cheap").provider("groq").apiKey("sk-cheap").build(),
            Target.builder().name("default").provider("anthropic").apiKey("sk-default").build()))
        .build();

    Target resolved = new ConditionalRouter(config).resolve(new RouterContext(
        Map.of("tier", "gold"),
        Map.of("model", "gpt-4o-mini"),
        "/v1/chat/completions"));

    assertThat(resolved.name()).isEqualTo("premium");
    assertThat(resolved.provider()).isEqualTo("openai");
  }

  @Test
  void supportsAndOrAndFallsBackToDefaultTarget() {
    GatewayConfig config = GatewayConfig.builder()
        .strategy(new Strategy(
            StrategyMode.CONDITIONAL,
            List.of(),
            List.of(new Strategy.Condition(
                Map.of("$and", List.of(
                    Map.of("metadata.region", Map.of("$in", List.of("eu", "apac"))),
                    Map.of("$or", List.of(
                        Map.of("params.temperature", Map.of("$lt", 0.2)),
                        Map.of("params.stream", true))))),
                "regional")),
            "fallback"))
        .targets(List.of(
            Target.builder().name("regional").provider("mistral-ai").apiKey("sk-regional").build(),
            Target.builder().name("fallback").provider("openai").apiKey("sk-fallback").build()))
        .build();

    Target resolved = new ConditionalRouter(config).resolve(new RouterContext(
        Map.of("region", "us"),
        Map.of("temperature", 0.1, "stream", false),
        "/v1/chat/completions"));

    assertThat(resolved.name()).isEqualTo("fallback");
  }

  @Test
  void rejectsUnknownTargetNames() {
    GatewayConfig config = GatewayConfig.builder()
        .strategy(new Strategy(
            StrategyMode.CONDITIONAL,
            List.of(),
            List.of(new Strategy.Condition(Map.of("metadata.tier", "gold"), "missing")),
            null))
        .targets(List.of(Target.builder().name("existing").provider("openai").apiKey("sk").build()))
        .build();

    assertThatThrownBy(() -> new ConditionalRouter(config).resolve(new RouterContext(
            Map.of("tier", "gold"), Map.of(), "/v1/chat/completions")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing");
  }
}
