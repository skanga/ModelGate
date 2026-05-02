package com.modelgate.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderResponseMetadataTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void normalizesTokenUsageFinishReasonToolsRefusalAndSafetySignals() {
    ProviderResponseMetadata metadata = ProviderResponseMetadata.fromJson(objectMapper, """
        {
          "provider": "google",
          "model": "gemini-1.5-pro",
          "usage": {
            "prompt_tokens": 11,
            "completion_tokens": 7,
            "total_tokens": 18,
            "prompt_tokens_details": {"cached_tokens": 3, "audio_tokens": 2},
            "completion_tokens_details": {"reasoning_tokens": 4, "audio_tokens": 1}
          },
          "choices": [
            {
              "finish_reason": "tool_calls",
              "message": {
                "refusal": "policy refusal",
                "tool_calls": [{"id": "tool-1", "type": "function"}]
              },
              "safetyRatings": [{"category": "HARM_CATEGORY_DANGEROUS_CONTENT", "blocked": true}]
            }
          ]
        }
        """);

    assertThat(metadata.provider()).isEqualTo("google");
    assertThat(metadata.model()).isEqualTo("gemini-1.5-pro");
    assertThat(metadata.promptTokens()).isEqualTo(11);
    assertThat(metadata.completionTokens()).isEqualTo(7);
    assertThat(metadata.totalTokens()).isEqualTo(18);
    assertThat(metadata.cachedTokens()).isEqualTo(3);
    assertThat(metadata.reasoningTokens()).isEqualTo(4);
    assertThat(metadata.promptAudioTokens()).isEqualTo(2);
    assertThat(metadata.completionAudioTokens()).isEqualTo(1);
    assertThat(metadata.finishReason()).isEqualTo("tool_calls");
    assertThat(metadata.toolCallCount()).isEqualTo(1);
    assertThat(metadata.refused()).isTrue();
    assertThat(metadata.refusal()).isEqualTo("policy refusal");
    assertThat(metadata.safetySignals()).isEqualTo(1);
    assertThat(metadata.safetyFlagged()).isTrue();
    assertThat(metadata.tokenUsage()).containsEntry("prompt_tokens", 11);
    assertThat(metadata.cost()).containsEntry("currency", "USD");
    assertThat(metadata.cost()).containsEntry("source", "not_configured");
  }

  @Test
  void supportsProviderNativeUsageAliases() {
    ProviderResponseMetadata metadata = ProviderResponseMetadata.fromJson(objectMapper, """
        {
          "usage": {"input_tokens": 5, "output_tokens": 6},
          "choices": [{"finish_reason": "end_turn"}]
        }
        """);

    assertThat(metadata.promptTokens()).isEqualTo(5);
    assertThat(metadata.completionTokens()).isEqualTo(6);
    assertThat(metadata.totalTokens()).isEqualTo(11);
    assertThat(metadata.finishReason()).isEqualTo("stop");
  }

  @Test
  void calculatesConfiguredModelCostFromNormalizedTokenUsage() {
    ProviderResponseMetadata metadata = ProviderResponseMetadata.fromJson(objectMapper, """
        {
          "model": "gpt-observe",
          "usage": {
            "prompt_tokens": 9,
            "completion_tokens": 4,
            "total_tokens": 13,
            "prompt_tokens_details": {"cached_tokens": 2},
            "completion_tokens_details": {"reasoning_tokens": 3}
          }
        }
        """);

    Map<String, Object> pricing = Map.of(
        "currency", "USD",
        "models", Map.of(
            "gpt-observe", Map.of(
                "input_per_million", 1_000_000,
                "cached_input_per_million", 1_000_000,
                "output_per_million", 1_000_000,
                "reasoning_per_million", 1_000_000)));

    assertThat(metadata.cost(pricing))
        .containsEntry("currency", "USD")
        .containsEntry("input_cost_usd", "7")
        .containsEntry("cached_input_cost_usd", "2")
        .containsEntry("output_cost_usd", "1")
        .containsEntry("reasoning_cost_usd", "3")
        .containsEntry("total_cost_usd", "13")
        .containsEntry("source", "config");
  }
}
