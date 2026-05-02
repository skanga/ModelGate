package com.modelgate.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderProductionReadinessGateTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void readyProviderPassesReadinessGate() throws Exception {
    ProviderProductionReadinessGate.Result result = ProviderProductionReadinessGate.check(
        readJson("/provider-contracts/provider-validation-ready-fixture.json"),
        readJson("/provider-contracts/live-validation-ready-fixture.json"),
        "openai");

    assertThat(result.exitCode()).isZero();
    assertThat(result.problems()).isEmpty();
    assertThat(result.selectedProviders()).containsExactly("openai");
  }

  @Test
  void knownProviderThatIsNotProductionReadyFailsClosed() throws Exception {
    ProviderProductionReadinessGate.Result result = ProviderProductionReadinessGate.check(
        readJson("/provider-contracts/provider-validation-matrix.json"),
        readJson("/provider-contracts/live-validation.json"),
        "openai");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.problems()).anySatisfy(problem -> assertThat(problem)
        .contains("openai")
        .contains("production_ready=false"));
  }

  @Test
  void allSelectorFailsWhenAnyProviderIsNotReady() throws Exception {
    ProviderProductionReadinessGate.Result result = ProviderProductionReadinessGate.check(
        readJson("/provider-contracts/provider-validation-matrix.json"),
        readJson("/provider-contracts/live-validation.json"),
        "all");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.selectedProviders()).hasSize(76);
    assertThat(result.problems()).contains("76 provider(s) are not production_ready");
  }

  @Test
  void readinessGateReportsMatrixManifestDriftAsBadInput() throws Exception {
    ProviderProductionReadinessGate.Result result = ProviderProductionReadinessGate.check(
        readJson("/provider-contracts/provider-validation-drift-fixture.json"),
        readJson("/provider-contracts/live-validation-ready-fixture.json"),
        "openai");

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(result.problems()).anySatisfy(problem -> assertThat(problem)
        .contains("drift")
        .contains("openai"));
  }

  @Test
  void mavenReadinessGateCanBeEnabledWithSystemProperty() throws Exception {
    String selector = System.getProperty("modelgate.provider.ready");
    assumeTrue(selector != null && !selector.isBlank(), "set -Dmodelgate.provider.ready=PROVIDER|all to enforce");

    ProviderProductionReadinessGate.Result result = ProviderProductionReadinessGate.check(
        readJson("/provider-contracts/provider-validation-matrix.json"),
        readJson("/provider-contracts/live-validation.json"),
        selector);

    assertThat(result.problems()).isEmpty();
    assertThat(result.exitCode()).isZero();
  }

  private JsonNode readJson(String resource) throws Exception {
    try (InputStream stream = getClass().getResourceAsStream(resource)) {
      assertThat(stream).as(resource).isNotNull();
      return objectMapper.readTree(stream);
    }
  }
}
