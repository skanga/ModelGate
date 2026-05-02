package com.modelgate.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.gateway.provider.ProviderCatalog;
import java.io.InputStream;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ProviderGatewayContractCoverageTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void gatewayContractsCoverEverySupportedProvider() throws Exception {
    JsonNode root = readContracts();
    Set<String> coveredProviders = new TreeSet<>();
    root.path("contracts").forEach(contract -> coveredProviders.add(contract.path("provider").asText()));

    assertThat(coveredProviders).containsAll(ProviderCatalog.supportedProviders());
  }

  private JsonNode readContracts() throws Exception {
    try (InputStream stream = getClass().getResourceAsStream("/provider-contracts/gateway-contracts.json")) {
      assertThat(stream).as("gateway provider contract fixture file").isNotNull();
      return objectMapper.readTree(stream);
    }
  }
}
