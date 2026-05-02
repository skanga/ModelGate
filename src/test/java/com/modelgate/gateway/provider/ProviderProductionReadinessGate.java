package com.modelgate.gateway.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ProviderProductionReadinessGate {
  private ProviderProductionReadinessGate() {}

  static Result check(JsonNode matrix, JsonNode liveManifest, String selector) {
    String normalizedSelector = selector == null ? "" : selector.trim().toLowerCase(Locale.ROOT);
    if (normalizedSelector.isBlank()) {
      return new Result(2, List.of(), List.of("validation readiness selector must not be blank"));
    }

    Map<String, JsonNode> providers = providersById(matrix);
    Map<String, List<JsonNode>> liveScenarios = liveScenariosByProvider(liveManifest);
    List<String> drift = matrixManifestDrift(providers, liveScenarios);
    if (!drift.isEmpty()) {
      return new Result(2, selectedProviderIds(providers, normalizedSelector), prefix("drift: ", drift));
    }

    List<JsonNode> selectedProviders = selectedProviders(providers, normalizedSelector);
    if (selectedProviders.isEmpty()) {
      return new Result(2, List.of(), List.of("provider " + selector + " was not found in validation matrix"));
    }

    List<JsonNode> notReady = selectedProviders.stream()
        .filter(provider -> !provider.path("production_ready").asBoolean(false))
        .toList();
    if (!notReady.isEmpty()) {
      if ("all".equals(normalizedSelector)) {
        return new Result(
            1,
            providerIds(selectedProviders),
            List.of(notReady.size() + " provider(s) are not production_ready"));
      }
      JsonNode provider = notReady.getFirst();
      return new Result(
          1,
          providerIds(selectedProviders),
          List.of(provider.path("provider").asText() + " production_ready=false validation_tier="
              + provider.path("validation_tier").asText()));
    }

    return new Result(0, providerIds(selectedProviders), List.of());
  }

  private static Map<String, JsonNode> providersById(JsonNode matrix) {
    Map<String, JsonNode> providers = new LinkedHashMap<>();
    JsonNode providerArray = matrix.path("providers");
    if (!providerArray.isArray()) {
      return providers;
    }
    providerArray.forEach(provider -> {
      String providerId = provider.path("provider").asText("").trim().toLowerCase(Locale.ROOT);
      if (!providerId.isBlank()) {
        providers.put(providerId, provider);
      }
    });
    return providers;
  }

  private static Map<String, List<JsonNode>> liveScenariosByProvider(JsonNode liveManifest) {
    Map<String, List<JsonNode>> scenarios = new LinkedHashMap<>();
    JsonNode scenarioArray = liveManifest.path("scenarios");
    if (!scenarioArray.isArray()) {
      return scenarios;
    }
    scenarioArray.forEach(scenario -> {
      String providerId = scenario.path("provider").asText("").trim().toLowerCase(Locale.ROOT);
      if (!providerId.isBlank()) {
        scenarios.computeIfAbsent(providerId, ignored -> new ArrayList<>()).add(scenario);
      }
    });
    return scenarios;
  }

  private static List<String> matrixManifestDrift(
      Map<String, JsonNode> providers,
      Map<String, List<JsonNode>> liveScenarios) {
    List<String> drift = new ArrayList<>();
    liveScenarios.keySet().stream().sorted().forEach(providerId -> {
      JsonNode provider = providers.get(providerId);
      if (provider == null) {
        drift.add(providerId + " has live scenarios but is missing from the validation matrix");
        return;
      }
      if (!provider.path("live_validation").asBoolean(false)) {
        drift.add(providerId + " has live scenarios but live_validation is not true");
      }
      if (!"live-smoke".equals(provider.path("validation_tier").asText())) {
        drift.add(providerId + " has live scenarios but validation_tier is "
            + provider.path("validation_tier").asText());
      }
    });
    providers.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .filter(entry -> entry.getValue().path("live_validation").asBoolean(false))
        .filter(entry -> !liveScenarios.containsKey(entry.getKey()))
        .forEach(entry -> drift.add(entry.getKey() + " is marked live_validation but has no live scenario"));
    return drift;
  }

  private static List<JsonNode> selectedProviders(Map<String, JsonNode> providers, String normalizedSelector) {
    if ("all".equals(normalizedSelector)) {
      return providers.values().stream()
          .sorted(Comparator.comparing(provider -> provider.path("provider").asText()))
          .toList();
    }
    JsonNode provider = providers.get(normalizedSelector);
    return provider == null ? List.of() : List.of(provider);
  }

  private static List<String> selectedProviderIds(Map<String, JsonNode> providers, String normalizedSelector) {
    return providerIds(selectedProviders(providers, normalizedSelector));
  }

  private static List<String> providerIds(List<JsonNode> providers) {
    return providers.stream()
        .map(provider -> provider.path("provider").asText())
        .toList();
  }

  private static List<String> prefix(String prefix, List<String> values) {
    return values.stream()
        .map(value -> prefix + value)
        .toList();
  }

  record Result(int exitCode, List<String> selectedProviders, List<String> problems) {}
}
