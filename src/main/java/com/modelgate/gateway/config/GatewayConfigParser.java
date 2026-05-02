package com.modelgate.gateway.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.gateway.headers.GatewayHeaders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GatewayConfigParser {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private final ObjectMapper objectMapper;

  public GatewayConfigParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public GatewayConfig parse(String json, Map<String, String> headers) {
    JsonNode root = readRoot(json);
    Map<String, String> normalizedHeaders = normalizeHeaders(headers);

    List<Target> targets = parseTargets(root.path("targets"));
    String provider = textOrNull(root.get("provider"));
    String apiKey = textOrNull(field(root, "api_key", "apiKey"));
    Map<String, Object> providerOptions = parseProviderOptions(root);

    if (provider == null && targets.isEmpty()) {
      provider = GatewayHeaders.value(normalizedHeaders, "provider");
    }
    if (apiKey == null && targets.isEmpty()) {
      apiKey = stripBearer(normalizedHeaders.get("authorization"));
    }
    if (apiKey == null && targets.isEmpty() && "anthropic".equalsIgnoreCase(provider)) {
      apiKey = normalizedHeaders.get("x-api-key");
    }
    if (provider != null) {
      providerOptions.put("provider", provider);
    }
    if (apiKey != null) {
      providerOptions.put("apiKey", apiKey);
    }
    applyHeaderProviderOptions(providerOptions, normalizedHeaders);
    List<Map<String, Object>> defaultInputGuardrails = defaultGuardrails(
        field(root, "default_input_guardrails", "defaultInputGuardrails", "default_before_request_hooks", "defaultBeforeRequestHooks"),
        normalizedHeaders,
        "default-input-guardrails");
    List<Map<String, Object>> defaultOutputGuardrails = defaultGuardrails(
        field(root, "default_output_guardrails", "defaultOutputGuardrails", "default_after_request_hooks", "defaultAfterRequestHooks"),
        normalizedHeaders,
        "default-output-guardrails");

    return GatewayConfig.builder()
        .provider(provider)
        .apiKey(apiKey)
        .strategy(parseStrategy(root.get("strategy")))
        .retry(parseRetry(root.get("retry")))
        .cache(parseCache(root.get("cache")))
        .providerOptions(new ProviderOptions(providerOptions))
        .requestTimeoutMillis(longValue(field(root, "request_timeout", "requestTimeout"), 0))
        .customHost(textOrNull(field(root, "custom_host", "customHost")))
        .forwardHeaders(parseStringList(field(root, "forward_headers", "forwardHeaders")))
        .inputGuardrails(parseObjectList(field(root, "input_guardrails", "inputGuardrails", "before_request_hooks", "beforeRequestHooks")))
        .outputGuardrails(parseObjectList(field(root, "output_guardrails", "outputGuardrails", "after_request_hooks", "afterRequestHooks")))
        .defaultInputGuardrails(defaultInputGuardrails)
        .defaultOutputGuardrails(defaultOutputGuardrails)
        .targets(targets)
        .build();
  }

  private JsonNode readRoot(String json) {
    try {
      if (json == null || json.isBlank()) {
        return objectMapper.createObjectNode();
      }
      return objectMapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid gateway config JSON", e);
    }
  }

  private Strategy parseStrategy(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    List<Integer> onStatusCodes = new ArrayList<>();
    JsonNode statusCodes = field(node, "on_status_codes", "onStatusCodes");
    if (statusCodes.isArray()) {
      statusCodes.forEach(code -> onStatusCodes.add(code.asInt()));
    }

    List<Strategy.Condition> conditions = new ArrayList<>();
    JsonNode conditionNodes = node.path("conditions");
    if (conditionNodes.isArray()) {
      for (JsonNode conditionNode : conditionNodes) {
        conditions.add(new Strategy.Condition(
            objectMapper.convertValue(conditionNode.path("query"), MAP_TYPE),
            firstText(conditionNode, "then", "target")));
      }
    }

    return new Strategy(
        StrategyMode.fromString(textOrNull(node.get("mode"))),
        onStatusCodes,
        conditions,
        defaultTarget(node));
  }

  private RetrySettings parseRetry(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return RetrySettings.defaults();
    }
    List<Integer> onStatusCodes = new ArrayList<>();
    JsonNode statusCodes = field(node, "on_status_codes", "onStatusCodes");
    if (statusCodes.isArray()) {
      statusCodes.forEach(code -> onStatusCodes.add(code.asInt()));
    }
    return new RetrySettings(
        intValue(node.get("attempts"), 0),
        onStatusCodes,
        booleanValue(field(node, "use_retry_after_header", "useRetryAfterHeader"), false));
  }

  private CacheSettings parseCache(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return CacheSettings.defaults();
    }
    String mode = textOrNull(node.get("mode"));
    boolean enabled = "simple".equalsIgnoreCase(mode)
        || "redis".equalsIgnoreCase(mode)
        || booleanValue(node.get("enabled"), false);
    long ttlMillis = 0;
    if (hasField(node, "max_age", "maxAge")) {
      ttlMillis = longValue(field(node, "max_age", "maxAge"), 0);
    } else if (node.has("ttl")) {
      ttlMillis = longValue(node.get("ttl"), 0);
    }
    return new CacheSettings(
        mode == null ? (enabled ? "simple" : "DISABLED") : mode,
        enabled,
        ttlMillis);
  }

  private List<Target> parseTargets(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<Target> targets = new ArrayList<>();
    for (JsonNode targetNode : node) {
      targets.add(Target.builder()
          .name(textOrNull(targetNode.get("name")))
          .provider(textOrNull(targetNode.get("provider")))
          .apiKey(textOrNull(field(targetNode, "api_key", "apiKey")))
          .customHost(textOrNull(field(targetNode, "custom_host", "customHost")))
          .providerOptions(new ProviderOptions(parseProviderOptions(targetNode)))
          .weight(doubleValue(targetNode.get("weight"), 1))
          .retry(parseTargetRetry(targetNode.get("retry")))
          .requestTimeoutMillis(longValue(field(targetNode, "request_timeout", "requestTimeout"), 0))
          .forwardHeaders(parseTargetForwardHeaders(field(targetNode, "forward_headers", "forwardHeaders")))
          .build());
    }
    return targets;
  }

  private Map<String, Object> parseProviderOptions(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return new LinkedHashMap<>();
    }

    Map<String, Object> providerOptions = new LinkedHashMap<>();
    var fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      if (isProviderOptionContainer(entry.getKey())) {
        continue;
      }
      providerOptions.put(providerOptionKey(entry.getKey()), objectMapper.convertValue(entry.getValue(), Object.class));
    }
    return providerOptions;
  }

  private static boolean isProviderOptionContainer(String fieldName) {
    return fieldName.equals("strategy")
        || fieldName.equals("retry")
        || fieldName.equals("cache")
        || fieldName.equals("targets")
        || fieldName.equals("input_guardrails")
        || fieldName.equals("inputGuardrails")
        || fieldName.equals("before_request_hooks")
        || fieldName.equals("beforeRequestHooks")
        || fieldName.equals("output_guardrails")
        || fieldName.equals("outputGuardrails")
        || fieldName.equals("after_request_hooks")
        || fieldName.equals("afterRequestHooks")
        || fieldName.equals("default_input_guardrails")
        || fieldName.equals("defaultInputGuardrails")
        || fieldName.equals("default_before_request_hooks")
        || fieldName.equals("defaultBeforeRequestHooks")
        || fieldName.equals("default_output_guardrails")
        || fieldName.equals("defaultOutputGuardrails")
        || fieldName.equals("default_after_request_hooks")
        || fieldName.equals("defaultAfterRequestHooks");
  }

  private static String providerOptionKey(String fieldName) {
    return switch (fieldName) {
      case "api_key" -> "apiKey";
      case "custom_host" -> "customHost";
      case "forward_headers" -> "forwardHeaders";
      case "request_timeout" -> "requestTimeout";
      case "default_input_guardrails" -> "defaultInputGuardrails";
      case "default_output_guardrails" -> "defaultOutputGuardrails";
      case "azure_extra_params" -> "azureExtraParameters";
      case "aws_server_side_encryption_kms_key_id" -> "awsServerSideEncryptionKMSKeyId";
      default -> snakeToCamelCase(fieldName);
    };
  }

  private static void applyHeaderProviderOptions(Map<String, Object> providerOptions, Map<String, String> headers) {
    putIfHasText(providerOptions, "openaiOrganization", firstNonBlank(
        GatewayHeaders.value(headers, "openai-organization"),
        headers.get("openai-organization")));
    putIfHasText(providerOptions, "openaiProject", firstNonBlank(
        GatewayHeaders.value(headers, "openai-project"),
        headers.get("openai-project")));
    putIfHasText(providerOptions, "openaiBeta", firstNonBlank(
        GatewayHeaders.value(headers, "openai-beta"),
        headers.get("openai-beta")));
    putIfHasText(providerOptions, "anthropicVersion", firstNonBlank(
        GatewayHeaders.value(headers, "anthropic-version"),
        headers.get("anthropic-version")));
    putIfHasText(providerOptions, "anthropicBeta", firstNonBlank(
        GatewayHeaders.value(headers, "anthropic-beta"),
        headers.get("anthropic-beta")));
    putIfHasText(providerOptions, "workersAiAccountId", GatewayHeaders.value(headers, "workers-ai-account-id"));
  }

  private static void putIfHasText(Map<String, Object> values, String key, String value) {
    if (value != null && !value.isBlank()) {
      values.putIfAbsent(key, value);
    }
  }

  private static String firstNonBlank(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }

  private static String snakeToCamelCase(String value) {
    StringBuilder converted = new StringBuilder();
    boolean uppercaseNext = false;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '_') {
        uppercaseNext = converted.length() > 0;
        continue;
      }
      if (uppercaseNext) {
        converted.append(Character.toUpperCase(character));
        uppercaseNext = false;
      } else {
        converted.append(character);
      }
    }
    return converted.toString();
  }

  private RetrySettings parseTargetRetry(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    return parseRetry(node);
  }

  private List<String> parseTargetForwardHeaders(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    return parseStringList(node);
  }

  private List<String> parseStringList(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return List.of();
    }
    if (node.isTextual()) {
      String value = node.asText();
      if (value.isBlank()) {
        return List.of();
      }
      return java.util.Arrays.stream(value.split(","))
          .map(String::trim)
          .filter(item -> !item.isEmpty())
          .toList();
    }
    if (!node.isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (JsonNode item : node) {
      if (item.isTextual()) {
        values.add(item.asText());
      }
    }
    return values;
  }

  private List<Map<String, Object>> parseObjectList(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<Map<String, Object>> values = new ArrayList<>();
    for (JsonNode item : node) {
      values.add(objectMapper.convertValue(item, MAP_TYPE));
    }
    return values;
  }

  private List<Map<String, Object>> defaultGuardrails(
      JsonNode configNode,
      Map<String, String> headers,
      String headerSuffix) {
    String headerValue = GatewayHeaders.value(headers, headerSuffix);
    if (headerValue != null && !headerValue.isBlank()) {
      return parseObjectList(readArray(headerValue, headerSuffix));
    }
    return parseObjectList(configNode);
  }

  private JsonNode readArray(String json, String headerSuffix) {
    try {
      JsonNode node = objectMapper.readTree(json);
      if (!node.isArray()) {
        throw new IllegalArgumentException("Invalid " + headerSuffix + " header JSON");
      }
      return node;
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Invalid " + headerSuffix + " header JSON", exception);
    }
  }

  private Map<String, String> normalizeHeaders(Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    return headers.entrySet().stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(
            entry -> entry.getKey().toLowerCase(Locale.ROOT),
            Map.Entry::getValue,
            (left, right) -> right));
  }

  private String stripBearer(String value) {
    if (value == null) {
      return null;
    }
    return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7) : value;
  }

  private String textOrNull(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
  }

  private String firstText(JsonNode node, String... fieldNames) {
    for (String fieldName : fieldNames) {
      String value = textOrNull(node.get(fieldName));
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private String defaultTarget(JsonNode node) {
    String aliasValue = textOrNull(field(node, "default_target", "defaultTarget"));
    return aliasValue == null ? textOrNull(node.get("default")) : aliasValue;
  }

  private JsonNode field(JsonNode node, String... fieldNames) {
    JsonNode selected = null;
    var fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      for (String fieldName : fieldNames) {
        if (entry.getKey().equals(fieldName)) {
          selected = entry.getValue();
          break;
        }
      }
    }
    return selected == null ? objectMapper.missingNode() : selected;
  }

  private boolean hasField(JsonNode node, String... fieldNames) {
    return !field(node, fieldNames).isMissingNode();
  }

  private int intValue(JsonNode node, int defaultValue) {
    return node == null || node.isNull() || node.isMissingNode() ? defaultValue : node.asInt();
  }

  private double doubleValue(JsonNode node, double defaultValue) {
    return node == null || node.isNull() || node.isMissingNode() ? defaultValue : node.asDouble();
  }

  private long longValue(JsonNode node, long defaultValue) {
    return node == null || node.isNull() || node.isMissingNode() ? defaultValue : node.asLong();
  }

  private boolean booleanValue(JsonNode node, boolean defaultValue) {
    return node == null || node.isNull() || node.isMissingNode() ? defaultValue : node.asBoolean();
  }
}
