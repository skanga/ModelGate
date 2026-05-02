package com.modelgate.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProviderLiveValidationSelectorTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void blankFilterRunsEveryScenario() throws Exception {
    JsonNode openAi = scenario("openai chat live smoke", "openai", "chat", "baseline");
    JsonNode voyage = scenario("voyage embeddings live smoke", "voyage", "embeddings");

    assertThat(ProviderLiveValidationSelector.matches(openAi, "")).isTrue();
    assertThat(ProviderLiveValidationSelector.matches(voyage, null)).isTrue();
  }

  @Test
  void explicitFilterIsRequiredForLiveProviderCalls() {
    assertThat(ProviderLiveValidationSelector.hasExplicitFilter(null)).isFalse();
    assertThat(ProviderLiveValidationSelector.hasExplicitFilter(" ")).isFalse();
    assertThat(ProviderLiveValidationSelector.hasExplicitFilter("openai")).isTrue();
    assertThat(ProviderLiveValidationSelector.hasExplicitFilter("all")).isTrue();
  }

  @Test
  void allFilterRunsEveryScenario() throws Exception {
    JsonNode voyage = scenario("voyage embeddings live smoke", "voyage", "embeddings");

    assertThat(ProviderLiveValidationSelector.matches(voyage, "all")).isTrue();
    assertThat(ProviderLiveValidationSelector.matches(voyage, "*")).isTrue();
  }

  @Test
  void filterMatchesProviderNameScenarioNameOrTagsCaseInsensitively() throws Exception {
    JsonNode openAi = scenario("openai chat live smoke", "openai", "chat", "baseline");
    JsonNode voyage = scenario("voyage embeddings live smoke", "voyage", "embeddings");

    assertThat(ProviderLiveValidationSelector.matches(openAi, "OPENAI")).isTrue();
    assertThat(ProviderLiveValidationSelector.matches(openAi, "chat live")).isTrue();
    assertThat(ProviderLiveValidationSelector.matches(openAi, "baseline")).isTrue();
    assertThat(ProviderLiveValidationSelector.matches(voyage, "embeddings")).isTrue();
    assertThat(ProviderLiveValidationSelector.matches(voyage, "openai")).isFalse();
  }

  @Test
  void commaSeparatedFilterMatchesAnyTerm() throws Exception {
    JsonNode bedrock = scenario("bedrock anthropic count tokens live smoke", "bedrock", "count-tokens");

    assertThat(ProviderLiveValidationSelector.matches(bedrock, "openai, count-tokens")).isTrue();
    assertThat(ProviderLiveValidationSelector.matches(bedrock, "openai, voyage")).isFalse();
  }

  private JsonNode scenario(String name, String provider, String... tags) throws Exception {
    return objectMapper.readTree("""
        {
          "name": "%s",
          "provider": "%s",
          "tags": [%s]
        }
        """.formatted(name, provider, quoted(tags)));
  }

  private String quoted(String[] tags) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < tags.length; i++) {
      if (i > 0) {
        out.append(", ");
      }
      out.append('"').append(tags[i]).append('"');
    }
    return out.toString();
  }
}
