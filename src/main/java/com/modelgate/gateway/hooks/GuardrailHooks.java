package com.modelgate.gateway.hooks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.gateway.headers.GatewayHeaders;
import com.modelgate.gateway.plugins.HookContext;
import com.modelgate.gateway.plugins.PluginRegistry;
import com.modelgate.gateway.plugins.PluginResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GuardrailHooks {
  private static final Set<String> METADATA_KEYS = Set.of(
      "deny",
      "async",
      "id",
      "type",
      "on_fail",
      "onFail",
      "on_success",
      "onSuccess",
      "sequential",
      "guardrail_version_id",
      "guardrailVersionId");
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final PluginRegistry registry;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public GuardrailHooks(PluginRegistry registry) {
    this.registry = registry;
  }

  public HookDecision evaluateInput(String requestBody, List<Map<String, Object>> guardrails) {
    return evaluateInput(requestBody, guardrails, Map.of(), null);
  }

  public HookDecision evaluateInput(
      String requestBody,
      List<Map<String, Object>> guardrails,
      Map<String, String> headers,
      String requestType) {
    if (guardrails == null || guardrails.isEmpty()) {
      return HookDecision.allowed(null);
    }
    Map<String, Object> requestJson = readObject(requestBody);
    Map<String, Object> metadata = readObject(GatewayHeaders.value(headers, "metadata"));
    String requestText = extractRequestText(requestJson);
    String transformedBody = null;
    List<Map<String, Object>> hookResults = new ArrayList<>();

    for (Map<String, Object> guardrail : guardrails) {
      if (booleanValue(guardrail.get("async"))) {
        continue;
      }
      boolean deny = booleanValue(guardrail.get("deny"));
      long startedAt = System.currentTimeMillis();
      List<Map<String, Object>> checkResults = new ArrayList<>();
      boolean guardrailVerdict = true;
      boolean transformed = false;
      for (CheckSpec check : checks(guardrail)) {
        HookContext context = HookContext.forRequest(requestText, requestJson, metadata, headers, requestType);
        PluginResult result = registry.execute(check.pluginId(), context, check.parameters());
        if (result.transformed() && result.transformedData() != null) {
          requestJson = result.transformedData().requestJson();
          requestText = extractRequestText(requestJson);
          transformedBody = writeObject(requestJson);
          transformed = true;
        }
        checkResults.add(checkResult(check.pluginId(), result));
        guardrailVerdict = guardrailVerdict && result.verdict();
      }
      Map<String, Object> hookResult = hookResult(guardrail, checkResults, guardrailVerdict, transformed, startedAt, deny && !guardrailVerdict);
      hookResults.add(hookResult);
      if (!guardrailVerdict && deny) {
        return HookDecision.denied(denyBody(
            "before_request_hooks",
            hookResults));
      }
    }
    return HookDecision.allowed(transformedBody, hookResults, List.of());
  }

  public HookDecision evaluateOutput(String responseBody, List<Map<String, Object>> guardrails) {
    return evaluateOutput(responseBody, guardrails, "", Map.of(), null);
  }

  public HookDecision evaluateOutput(
      String responseBody,
      List<Map<String, Object>> guardrails,
      String requestBody,
      Map<String, String> headers,
      String requestType) {
    if (guardrails == null || guardrails.isEmpty()) {
      return HookDecision.allowed(null);
    }
    Map<String, Object> requestJson = readObject(requestBody);
    Map<String, Object> responseJson = readObject(responseBody);
    String responseText = extractResponseText(responseJson);
    String transformedBody = null;
    List<Map<String, Object>> hookResults = new ArrayList<>();

    for (Map<String, Object> guardrail : guardrails) {
      if (booleanValue(guardrail.get("async"))) {
        continue;
      }
      boolean deny = booleanValue(guardrail.get("deny"));
      long startedAt = System.currentTimeMillis();
      List<Map<String, Object>> checkResults = new ArrayList<>();
      boolean guardrailVerdict = true;
      boolean transformed = false;
      for (CheckSpec check : checks(guardrail)) {
        PluginResult result = registry.execute(
            check.pluginId(),
            HookContext.forOutput(responseText, requestJson, responseJson, headers, requestType),
            check.parameters());
        if (result.transformed() && result.transformedData() != null && !result.transformedData().responseJson().isEmpty()) {
          responseJson = result.transformedData().responseJson();
          responseText = extractResponseText(responseJson);
          transformedBody = writeObject(responseJson);
          transformed = true;
        }
        checkResults.add(checkResult(check.pluginId(), result));
        guardrailVerdict = guardrailVerdict && result.verdict();
      }
      Map<String, Object> hookResult = hookResult(guardrail, checkResults, guardrailVerdict, transformed, startedAt, deny && !guardrailVerdict);
      hookResults.add(hookResult);
      if (!guardrailVerdict && deny) {
        return HookDecision.denied(denyBody(
            "after_request_hooks",
            hookResults));
      }
    }
    return HookDecision.allowed(transformedBody, List.of(), hookResults);
  }

  private Map<String, Object> readObject(String json) {
    try {
      if (json == null || json.isBlank()) {
        return Map.of();
      }
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (JsonProcessingException exception) {
      return Map.of();
    }
  }

  private String writeObject(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize transformed hook body", exception);
    }
  }

  private List<CheckSpec> checks(Map<String, Object> guardrail) {
    Object explicitChecks = guardrail.get("checks");
    if (explicitChecks instanceof List<?> list) {
      List<CheckSpec> checks = new ArrayList<>();
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> rawCheck)) {
          continue;
        }
        Map<String, Object> check = stringObjectMap(rawCheck);
        if (check.containsKey("is_enabled") && !booleanValue(check.get("is_enabled"))) {
          continue;
        }
        if (check.containsKey("isEnabled") && !booleanValue(check.get("isEnabled"))) {
          continue;
        }
        String pluginId = normalizePluginId(stringValue(check.get("id"), ""));
        if (pluginId.isBlank()) {
          continue;
        }
        checks.add(new CheckSpec(pluginId, parameters(check.get("parameters"))));
      }
      return checks;
    }
    List<CheckSpec> checks = new ArrayList<>();
    guardrail.forEach((key, value) -> {
      if (!METADATA_KEYS.contains(key) && !"checks".equals(key)) {
        checks.add(new CheckSpec(normalizePluginId(key), parameters(value)));
      }
    });
    return checks;
  }

  @SuppressWarnings("unchecked")
  private Map<String, ?> parameters(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      map.forEach((key, nestedValue) -> result.put(String.valueOf(key), nestedValue));
      return result;
    }
    return Map.of();
  }

  private Map<String, Object> stringObjectMap(Map<?, ?> map) {
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  private String normalizePluginId(String id) {
    if (id == null || id.isBlank()) {
      return "";
    }
    return id.contains(".") ? id : "default." + id;
  }

  private String extractRequestText(Map<String, Object> requestJson) {
    Object prompt = requestJson.get("prompt");
    if (prompt instanceof String text) {
      return text;
    }
    Object input = requestJson.get("input");
    if (input instanceof String text) {
      return text;
    }
    if (input instanceof List<?> list) {
      return String.join("\n", list.stream().map(String::valueOf).toList());
    }
    Object messages = requestJson.get("messages");
    if (messages instanceof List<?> list && !list.isEmpty()) {
      Object last = list.getLast();
      if (last instanceof Map<?, ?> message) {
        Object content = message.get("content");
        if (content instanceof String text) {
          return text;
        }
        if (content instanceof List<?> parts) {
          return parts.stream()
              .map(this::textFromContentPart)
              .filter(text -> !text.isBlank())
              .reduce((left, right) -> left + "\n" + right)
              .orElse("");
        }
      }
    }
    return "";
  }

  private String textFromContentPart(Object part) {
    if (part instanceof Map<?, ?> map) {
      Object text = map.get("text");
      return text == null ? "" : String.valueOf(text);
    }
    return "";
  }

  private String extractResponseText(Map<String, Object> responseJson) {
    Object contentValue = responseJson.get("content");
    if (contentValue instanceof String text) {
      return text;
    }
    if (contentValue instanceof List<?> content) {
      return content.stream()
          .map(this::textFromContentPart)
          .filter(text -> !text.isBlank())
          .reduce((left, right) -> left + "\n" + right)
          .orElse("");
    }
    Object choices = responseJson.get("choices");
    if (!(choices instanceof List<?> list) || list.isEmpty()) {
      return "";
    }
    Object firstChoice = list.getFirst();
    JsonNode choice = objectMapper.valueToTree(firstChoice);
    JsonNode text = choice.get("text");
    if (text != null && !text.isNull()) {
      return text.asText();
    }
    JsonNode content = choice.path("message").path("content");
    if (!content.isMissingNode() && !content.isNull()) {
      return content.asText();
    }
    return "";
  }

  private Map<String, Object> checkResult(String pluginId, PluginResult result) {
    Map<String, Object> check = new LinkedHashMap<>();
    check.put("id", pluginId);
    check.put("verdict", result.verdict());
    check.put("error", result.error() == null ? null : Map.of("message", result.error()));
    check.put("data", result.data());
    check.put("transformed", result.transformed());
    return check;
  }

  private Map<String, Object> hookResult(
      Map<String, Object> guardrail,
      List<Map<String, Object>> checks,
      boolean verdict,
      boolean transformed,
      long startedAt,
      boolean deny) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("verdict", verdict);
    result.put("id", stringValue(guardrail.get("id"), "guardrail"));
    result.put("transformed", transformed);
    result.put("checks", checks);
    result.put("feedback", feedback(guardrail, checks, verdict));
    result.put("execution_time", Math.max(0, System.currentTimeMillis() - startedAt));
    result.put("async", booleanValue(guardrail.get("async")));
    result.put("type", stringValue(guardrail.get("type"), "guardrail"));
    result.put("created_at", Instant.ofEpochMilli(startedAt).toString());
    result.put("deny", deny);
    return result;
  }

  private String denyBody(String hookResultKey, List<Map<String, Object>> hookResults) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("message", "The guardrail checks defined in the config failed. You can find more information in the `hook_results` object.");
    error.put("type", "hooks_failed");
    error.put("param", null);
    error.put("code", null);

    Map<String, Object> hookResultsBody = new LinkedHashMap<>();
    hookResultsBody.put("before_request_hooks", "before_request_hooks".equals(hookResultKey) ? hookResults : List.of());
    hookResultsBody.put("after_request_hooks", "after_request_hooks".equals(hookResultKey) ? hookResults : List.of());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", error);
    body.put("hook_results", hookResultsBody);
    return writeObject(body);
  }

  private Map<String, Object> feedback(
      Map<String, Object> guardrail,
      List<Map<String, Object>> checks,
      boolean verdict) {
    Object action = verdict
        ? firstPresent(guardrail, "on_success", "onSuccess")
        : firstPresent(guardrail, "on_fail", "onFail");
    if (!(action instanceof Map<?, ?> actionMap)) {
      return null;
    }
    Object feedback = actionMap.get("feedback");
    if (!(feedback instanceof Map<?, ?> feedbackMap)) {
      return null;
    }

    Map<String, Object> result = new LinkedHashMap<>();
    if (feedbackMap.containsKey("value")) {
      result.put("value", feedbackMap.get("value"));
    }
    if (feedbackMap.containsKey("weight")) {
      result.put("weight", feedbackMap.get("weight"));
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    Object configuredMetadata = feedbackMap.get("metadata");
    if (configuredMetadata instanceof Map<?, ?> configuredMetadataMap) {
      configuredMetadataMap.forEach((key, value) -> metadata.put(String.valueOf(key), value));
    }
    metadata.put("successfulChecks", checkIds(checks, true, false));
    metadata.put("failedChecks", checkIds(checks, false, false));
    metadata.put("erroredChecks", checkIds(checks, false, true));
    result.put("metadata", metadata);
    return result;
  }

  private Object firstPresent(Map<String, Object> values, String first, String second) {
    return values.containsKey(first) ? values.get(first) : values.get(second);
  }

  private String checkIds(List<Map<String, Object>> checks, boolean successful, boolean errored) {
    return checks.stream()
        .filter(check -> successful
            ? Boolean.TRUE.equals(check.get("verdict"))
            : Boolean.FALSE.equals(check.get("verdict")) && errored == (check.get("error") != null))
        .map(check -> String.valueOf(check.get("id")))
        .reduce((left, right) -> left + ", " + right)
        .orElse("");
  }

  private boolean booleanValue(Object value) {
    return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
  }

  private String stringValue(Object value, String fallback) {
    if (value == null) {
      return fallback;
    }
    String text = String.valueOf(value);
    return text.isBlank() ? fallback : text;
  }

  private record CheckSpec(String pluginId, Map<String, ?> parameters) {}
}
