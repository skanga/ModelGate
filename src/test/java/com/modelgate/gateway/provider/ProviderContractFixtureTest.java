package com.modelgate.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.gateway.config.GatewayConfigParser;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderContractFixtureTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ProviderRequestFactory requestFactory = new ProviderRequestFactory(new GatewayConfigParser(objectMapper));

  @Test
  void providerContractFixturesMatchJavaTransforms() throws Exception {
    JsonNode root = readFixtureRoot();

    assertThat(root.path("request_transforms")).isNotEmpty();
    assertThat(root.path("response_transforms")).isNotEmpty();

    for (JsonNode fixture : root.path("request_transforms")) {
      ProviderRequest request = requestFactory.forEndpoint(
          fixture.path("endpoint").asText(),
          fixture.path("method").asText("POST"),
          fixture.path("body").toString(),
          headers(fixture.path("headers")));

      assertThat(request.url()).as(fixture.path("name").asText()).isEqualTo(fixture.path("expected_url").asText());
      for (JsonNode assertion : fixture.path("body_contains")) {
        assertThat(request.body()).as(fixture.path("name").asText()).contains(assertion.asText());
      }
      for (JsonNode assertion : fixture.path("body_not_contains")) {
        assertThat(request.body()).as(fixture.path("name").asText()).doesNotContain(assertion.asText());
      }
      for (JsonNode header : fixture.path("expected_headers")) {
        assertThat(request.headers()).as(fixture.path("name").asText())
            .containsEntry(header.path("name").asText(), header.path("value").asText());
      }
    }

    for (JsonNode fixture : root.path("response_transforms")) {
      ProviderResponse transformed = ProviderResponseTransformer.transform(
          fixture.path("provider").asText(),
          fixture.path("endpoint").asText(),
          new ProviderResponse(
              fixture.path("status").asInt(200),
              fixture.path("body").toString(),
              headers(fixture.path("headers")),
              0));

      for (JsonNode assertion : fixture.path("body_contains")) {
        assertThat(transformed.body()).as(fixture.path("name").asText()).contains(assertion.asText());
      }
      for (JsonNode assertion : fixture.path("body_not_contains")) {
        assertThat(transformed.body()).as(fixture.path("name").asText()).doesNotContain(assertion.asText());
      }
    }
  }

  private JsonNode readFixtureRoot() throws Exception {
    try (InputStream stream = getClass().getResourceAsStream("/provider-contracts/provider-contracts.json")) {
      assertThat(stream).as("provider contract fixture file").isNotNull();
      return objectMapper.readTree(stream);
    }
  }

  private Map<String, String> headers(JsonNode node) {
    Map<String, String> headers = new LinkedHashMap<>();
    if (node == null || !node.isObject()) {
      return headers;
    }
    node.fields().forEachRemaining(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
    return headers;
  }
}
