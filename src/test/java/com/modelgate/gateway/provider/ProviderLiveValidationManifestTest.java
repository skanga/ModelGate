package com.modelgate.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ProviderLiveValidationManifestTest {
  private static final Set<String> PRIORITY_PROVIDERS = Set.of(
      "bedrock",
      "vertex-ai",
      "cohere",
      "mistral-ai",
      "workers-ai",
      "sagemaker",
      "stability-ai",
      "fireworks-ai",
      "huggingface",
      "deepseek",
      "openrouter",
      "perplexity-ai",
      "voyage",
      "jina");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void liveValidationManifestCoversPriorityProviderCampaign() throws Exception {
    JsonNode root = readManifest();
    Set<String> coveredProviders = new TreeSet<>();
    root.path("scenarios").forEach(scenario -> coveredProviders.add(scenario.path("provider").asText()));

    assertThat(coveredProviders).containsAll(PRIORITY_PROVIDERS);
  }

  @Test
  void liveValidationManifestScenariosAreRunnableAndHaveExpectedAssertions() throws Exception {
    JsonNode root = readManifest();

    assertThat(root.path("scenarios")).isNotEmpty();
    for (JsonNode scenario : root.path("scenarios")) {
      assertThat(scenario.path("name").asText()).isNotBlank();
      assertThat(scenario.path("provider").asText()).isNotBlank();
      assertThat(scenario.path("endpoint").asText()).startsWith("/v1/");
      assertThat(scenario.path("request_body").isObject()).isTrue();
      assertThat(scenario.path("response_body_contains")).as(scenario.path("name").asText()).isNotEmpty();
      assertThat(scenario.path("required_env")).as(scenario.path("name").asText()).isNotEmpty();
      assertThat(scenario.path("base_url_env").asText()).as(scenario.path("name").asText()).startsWith("MODELGATE_LIVE_");
      assertThat(scenario.path("tags")).as(scenario.path("name").asText()).isNotEmpty();
    }
  }

  private JsonNode readManifest() throws Exception {
    try (InputStream stream = getClass().getResourceAsStream("/provider-contracts/live-validation.json")) {
      assertThat(stream).as("provider live-validation manifest").isNotNull();
      return objectMapper.readTree(stream);
    }
  }
}
