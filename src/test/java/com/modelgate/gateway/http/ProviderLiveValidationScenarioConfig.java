package com.modelgate.gateway.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ProviderLiveValidationScenarioConfig {
  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+)(:-([^}]*))?}");
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private ProviderLiveValidationScenarioConfig() {
  }

  static String gatewayConfig(JsonNode scenario, Map<String, String> environment) throws Exception {
    return gatewayConfig(scenario, environment::get);
  }

  static String gatewayConfig(JsonNode scenario, Function<String, String> environment) throws Exception {
    ObjectNode config = OBJECT_MAPPER.createObjectNode();
    config.put("provider", scenario.path("provider").asText());
    if (scenario.path("api_key_env").isTextual()) {
      config.put("api_key", environment.apply(scenario.path("api_key_env").asText()));
    }
    if (scenario.path("base_url_env").isTextual()) {
      String baseUrl = environment.apply(scenario.path("base_url_env").asText());
      if (hasText(baseUrl)) {
        config.put("custom_host", baseUrl.trim());
      }
    }
    JsonNode scenarioConfig = scenario.path("config");
    if (scenarioConfig.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = scenarioConfig.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        config.set(entry.getKey(), resolvedNode(entry.getValue(), environment));
      }
    }
    return OBJECT_MAPPER.writeValueAsString(config);
  }

  static String writeResolved(JsonNode node, Function<String, String> environment) throws Exception {
    return OBJECT_MAPPER.writeValueAsString(resolvedNode(node, environment));
  }

  static String resolvedText(JsonNode node, Map<String, String> environment) {
    return resolvedText(node, environment::get);
  }

  static String resolvedText(JsonNode node, Function<String, String> environment) {
    if (node == null || !node.isTextual()) {
      return "";
    }
    return resolvePlaceholders(node.asText(), environment);
  }

  private static JsonNode resolvedNode(JsonNode node, Function<String, String> environment) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return OBJECT_MAPPER.nullNode();
    }
    if (node.isTextual()) {
      return OBJECT_MAPPER.getNodeFactory().textNode(resolvePlaceholders(node.asText(), environment));
    }
    if (node.isArray()) {
      var array = OBJECT_MAPPER.createArrayNode();
      node.forEach(child -> array.add(resolvedNode(child, environment)));
      return array;
    }
    if (node.isObject()) {
      ObjectNode object = OBJECT_MAPPER.createObjectNode();
      node.fields().forEachRemaining(entry -> object.set(entry.getKey(), resolvedNode(entry.getValue(), environment)));
      return object;
    }
    return node.deepCopy();
  }

  private static String resolvePlaceholders(String value, Function<String, String> environment) {
    Matcher matcher = ENV_PLACEHOLDER.matcher(value);
    StringBuffer resolved = new StringBuffer();
    while (matcher.find()) {
      String envValue = environment.apply(matcher.group(1));
      String fallback = matcher.group(3);
      String replacement = hasText(envValue) ? envValue : (fallback == null ? "" : fallback);
      matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(resolved);
    return resolved.toString();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
