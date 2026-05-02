package com.modelgate.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ProviderValidationMatrixTest {
  private static final Set<String> VALIDATION_TIERS = Set.of(
      "mocked-smoke",
      "mocked-contract",
      "recorded-contract",
      "live-smoke");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void validationMatrixCoversEverySupportedProvider() throws Exception {
    JsonNode root = readMatrix();
    Set<String> matrixProviders = new TreeSet<>();
    root.path("providers").forEach(provider -> matrixProviders.add(provider.path("provider").asText()));

    assertThat(matrixProviders).containsExactlyInAnyOrderElementsOf(ProviderCatalog.supportedProviders());
  }

  @Test
  void validationMatrixUsesExplicitTiersAndProductionReadyRequiresExternalValidation() throws Exception {
    JsonNode root = readMatrix();

    for (JsonNode provider : root.path("providers")) {
      String providerId = provider.path("provider").asText();
      assertThat(providerId).as("provider").isNotBlank();
      assertThat(provider.path("validation_tier").asText()).as(providerId).isIn(VALIDATION_TIERS);
      assertThat(provider.path("mocked_contract").isBoolean()).as(providerId + " mocked_contract").isTrue();
      assertThat(provider.path("live_validation").isBoolean()).as(providerId + " live_validation").isTrue();
      assertThat(provider.path("recorded_contract").isBoolean()).as(providerId + " recorded_contract").isTrue();
      assertThat(provider.path("production_ready").isBoolean()).as(providerId + " production_ready").isTrue();
      assertThat(provider.path("notes").asText()).as(providerId + " notes").isNotBlank();

      if (provider.path("production_ready").asBoolean()) {
        assertThat(provider.path("live_validation").asBoolean() || provider.path("recorded_contract").asBoolean())
            .as(providerId + " production_ready requires live_validation or recorded_contract")
            .isTrue();
      }
    }
  }

  @Test
  void liveValidationProvidersAreMarkedInMatrix() throws Exception {
    JsonNode matrix = readMatrix();
    JsonNode liveManifest = readLiveManifest();
    Set<String> liveProviders = new TreeSet<>();
    liveManifest.path("scenarios").forEach(scenario -> liveProviders.add(scenario.path("provider").asText()));

    for (JsonNode provider : matrix.path("providers")) {
      if (liveProviders.contains(provider.path("provider").asText())) {
        assertThat(provider.path("live_validation").asBoolean())
            .as(provider.path("provider").asText())
            .isTrue();
      }
    }
  }

  @Test
  void validationMatrixAndLiveManifestHaveNoDrift() throws Exception {
    ProviderProductionReadinessGate.Result result = ProviderProductionReadinessGate.check(
        readMatrix(),
        readLiveManifest(),
        "all");

    assertThat(result.exitCode()).as("readiness still fails until all providers are production_ready").isEqualTo(1);
    assertThat(result.problems())
        .as("matrix/live manifest drift")
        .noneMatch(problem -> problem.startsWith("drift:"));
  }

  private JsonNode readMatrix() throws Exception {
    try (InputStream stream = getClass().getResourceAsStream("/provider-contracts/provider-validation-matrix.json")) {
      assertThat(stream).as("provider validation matrix").isNotNull();
      return objectMapper.readTree(stream);
    }
  }

  private JsonNode readLiveManifest() throws Exception {
    try (InputStream stream = getClass().getResourceAsStream("/provider-contracts/live-validation.json")) {
      assertThat(stream).as("provider live-validation manifest").isNotNull();
      return objectMapper.readTree(stream);
    }
  }
}
