package com.modelgate.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class ProviderLiveValidationTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(30))
      .build();

  @TestFactory
  Iterable<DynamicTest> liveProviderValidationScenarios() throws Exception {
    JsonNode root = readManifest();
    assertThat(root.path("scenarios")).isNotEmpty();

    return StreamSupport.stream(root.path("scenarios").spliterator(), false)
        .map(scenario -> DynamicTest.dynamicTest(
            scenario.path("name").asText("live provider validation"),
            () -> runScenario(scenario)))
        .toList();
  }

  private void runScenario(JsonNode scenario) throws Exception {
    assumeTrue("true".equalsIgnoreCase(System.getenv("MODELGATE_LIVE_PROVIDER_VALIDATION")),
        "Set MODELGATE_LIVE_PROVIDER_VALIDATION=true to call real provider APIs");
    String filter = System.getenv("MODELGATE_LIVE_PROVIDER_FILTER");
    assumeTrue(ProviderLiveValidationSelector.hasExplicitFilter(filter),
        "Set MODELGATE_LIVE_PROVIDER_FILTER to a provider, tag, scenario name, all, or * to call real provider APIs");
    assumeTrue(ProviderLiveValidationSelector.matches(scenario, filter),
        "Skipped by MODELGATE_LIVE_PROVIDER_FILTER");
    for (JsonNode envName : scenario.path("required_env")) {
      assumeTrue(hasText(System.getenv(envName.asText())),
          "Missing required env var for " + scenario.path("name").asText() + ": " + envName.asText());
    }

    Javalin app = GatewayApp.create().start(0);
    try {
      String config = ProviderLiveValidationScenarioConfig.gatewayConfig(scenario, System::getenv);
      String endpoint = ProviderLiveValidationScenarioConfig.resolvedText(scenario.path("endpoint"), System::getenv);
      HttpResponse<String> response = client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + endpoint))
              .timeout(Duration.ofSeconds(90))
              .header("content-type", scenario.path("content_type").asText("application/json"))
              .header("x-modelgate-config", config)
              .POST(HttpRequest.BodyPublishers.ofString(
                  ProviderLiveValidationScenarioConfig.writeResolved(scenario.path("request_body"), System::getenv)))
              .build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

      assertThat(response.statusCode())
          .as(scenario.path("name").asText() + " body="
              + ProviderLiveValidationOutputSanitizer.sanitize(response.body()))
          .isBetween(200, 299);
      for (JsonNode assertion : scenario.path("response_body_contains")) {
        assertThat(response.body()).as(scenario.path("name").asText()).contains(assertion.asText());
      }
    } finally {
      app.stop();
    }
  }

  private JsonNode readManifest() throws Exception {
    try (InputStream stream = getClass().getResourceAsStream("/provider-contracts/live-validation.json")) {
      assertThat(stream).as("provider live-validation manifest").isNotNull();
      return objectMapper.readTree(stream);
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
