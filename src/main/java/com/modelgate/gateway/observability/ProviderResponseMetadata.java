package com.modelgate.gateway.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record ProviderResponseMetadata(
    String provider,
    String model,
    int promptTokens,
    int completionTokens,
    int totalTokens,
    int cachedTokens,
    int reasoningTokens,
    int promptAudioTokens,
    int completionAudioTokens,
    String finishReason,
    int toolCallCount,
    boolean refused,
    String refusal,
    int safetySignals,
    boolean safetyFlagged) {
  public static ProviderResponseMetadata empty() {
    return new ProviderResponseMetadata(
        "",
        "",
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        "",
        0,
        false,
        "",
        0,
        false);
  }

  public static ProviderResponseMetadata fromJson(ObjectMapper objectMapper, String body) {
    if (body == null || body.isBlank()) {
      return empty();
    }
    try {
      JsonNode root = objectMapper.readTree(body);
      return fromJson(root);
    } catch (Exception ignored) {
      return empty();
    }
  }

  public static ProviderResponseMetadata fromJson(JsonNode root) {
    if (root == null || root.isMissingNode() || root.isNull()) {
      return empty();
    }
    JsonNode usage = root.path("usage");
    int promptTokens = firstInt(
        usage.path("prompt_tokens"),
        usage.path("input_tokens"),
        usage.path("promptTokens"));
    int completionTokens = firstInt(
        usage.path("completion_tokens"),
        usage.path("output_tokens"),
        usage.path("completionTokens"));
    int totalTokens = firstInt(
        usage.path("total_tokens"),
        usage.path("totalTokens"));
    if (totalTokens == 0 && (promptTokens > 0 || completionTokens > 0)) {
      totalTokens = promptTokens + completionTokens;
    }

    JsonNode promptDetails = usage.path("prompt_tokens_details");
    JsonNode completionDetails = usage.path("completion_tokens_details");
    int cachedTokens = firstInt(
        promptDetails.path("cached_tokens"),
        usage.path("cache_read_input_tokens"),
        usage.path("cache_creation_input_tokens"));
    int reasoningTokens = firstInt(
        completionDetails.path("reasoning_tokens"),
        usage.path("reasoning_tokens"));
    int promptAudioTokens = firstInt(promptDetails.path("audio_tokens"));
    int completionAudioTokens = firstInt(completionDetails.path("audio_tokens"));

    String finishReason = "";
    int toolCallCount = 0;
    String refusal = textual(root.path("refusal"));
    int safetySignals = safetySignalCount(root);
    boolean safetyFlagged = safetyFlagged(root);

    JsonNode choices = root.path("choices");
    if (choices.isArray()) {
      for (JsonNode choice : choices) {
        if (finishReason.isBlank()) {
          finishReason = normalizeFinishReason(textual(choice.path("finish_reason")));
        }
        JsonNode message = choice.path("message");
        JsonNode delta = choice.path("delta");
        toolCallCount += arraySize(message.path("tool_calls"));
        toolCallCount += arraySize(delta.path("tool_calls"));
        if (refusal.isBlank()) {
          refusal = firstText(message.path("refusal"), delta.path("refusal"));
        }
        safetySignals += safetySignalCount(choice);
        safetyFlagged = safetyFlagged || safetyFlagged(choice);
      }
    }

    return new ProviderResponseMetadata(
        textual(root.path("provider")),
        textual(root.path("model")),
        promptTokens,
        completionTokens,
        totalTokens,
        cachedTokens,
        reasoningTokens,
        promptAudioTokens,
        completionAudioTokens,
        finishReason,
        toolCallCount,
        !refusal.isBlank(),
        refusal,
        safetySignals,
        safetyFlagged);
  }

  public Map<String, Object> tokenUsage() {
    Map<String, Object> usage = new LinkedHashMap<>();
    usage.put("prompt_tokens", promptTokens);
    usage.put("completion_tokens", completionTokens);
    usage.put("total_tokens", totalTokens);
    usage.put("cached_tokens", cachedTokens);
    usage.put("reasoning_tokens", reasoningTokens);
    usage.put("prompt_audio_tokens", promptAudioTokens);
    usage.put("completion_audio_tokens", completionAudioTokens);
    return usage;
  }

  public Map<String, Object> responseMetadata() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("finishReason", finishReason);
    metadata.put("toolCallCount", toolCallCount);
    metadata.put("hasToolCalls", toolCallCount > 0);
    metadata.put("refused", refused);
    metadata.put("refusal", refusal);
    metadata.put("safetySignals", safetySignals);
    metadata.put("safetyFlagged", safetyFlagged);
    return metadata;
  }

  public Map<String, Object> cost() {
    return unconfiguredCost();
  }

  public Map<String, Object> cost(Object pricingConfig) {
    return cost(pricingConfig, null);
  }

  public Map<String, Object> cost(Object pricingConfig, String fallbackModel) {
    Map<?, ?> root = mapValue(pricingConfig);
    if (root == null) {
      return unconfiguredCost();
    }
    Map<?, ?> effectiveRoot = firstMap(root, "pricing", "pricing_config", "pricingConfig");
    if (effectiveRoot == null) {
      effectiveRoot = root;
    }
    String effectiveModel = model.isBlank() ? (fallbackModel == null ? "" : fallbackModel) : model;
    Map<?, ?> modelConfig = modelPricing(effectiveRoot, effectiveModel);
    if (modelConfig == null) {
      return unconfiguredCost();
    }

    Rate inputRate = firstRate(modelConfig,
        "input_per_million",
        "inputPerMillion",
        "prompt_per_million",
        "promptPerMillion",
        "input_per_1k",
        "inputPer1k");
    Rate cachedRate = firstRate(modelConfig,
        "cached_input_per_million",
        "cachedInputPerMillion",
        "cache_read_input_per_million",
        "cacheReadInputPerMillion",
        "cached_input_per_1k",
        "cachedInputPer1k");
    Rate outputRate = firstRate(modelConfig,
        "output_per_million",
        "outputPerMillion",
        "completion_per_million",
        "completionPerMillion",
        "output_per_1k",
        "outputPer1k");
    Rate reasoningRate = firstRate(modelConfig,
        "reasoning_per_million",
        "reasoningPerMillion",
        "reasoning_per_1k",
        "reasoningPer1k");
    if (inputRate == null && cachedRate == null && outputRate == null && reasoningRate == null) {
      return unconfiguredCost();
    }

    int inputTokens = cachedRate == null ? promptTokens : Math.max(0, promptTokens - cachedTokens);
    int outputTokens = reasoningRate == null ? completionTokens : Math.max(0, completionTokens - reasoningTokens);
    BigDecimal inputCost = tokenCost(inputTokens, inputRate);
    BigDecimal cachedCost = tokenCost(cachedTokens, cachedRate);
    BigDecimal outputCost = tokenCost(outputTokens, outputRate);
    BigDecimal reasoningCost = tokenCost(reasoningTokens, reasoningRate);
    BigDecimal totalCost = sum(inputCost, cachedCost, outputCost, reasoningCost);

    Map<String, Object> cost = new LinkedHashMap<>();
    String currency = stringValue(effectiveRoot.get("currency"));
    if (currency.isBlank()) {
      currency = stringValue(modelConfig.get("currency"));
    }
    if (currency.isBlank()) {
      currency = "USD";
    }
    cost.put("currency", currency);
    cost.put("input_cost_usd", money(inputCost));
    cost.put("cached_input_cost_usd", money(cachedCost));
    cost.put("output_cost_usd", money(outputCost));
    cost.put("reasoning_cost_usd", money(reasoningCost));
    cost.put("total_cost_usd", money(totalCost));
    cost.put("source", "config");
    cost.put("model", effectiveModel);
    return cost;
  }

  private static Map<String, Object> unconfiguredCost() {
    Map<String, Object> cost = new LinkedHashMap<>();
    cost.put("currency", "USD");
    cost.put("input_cost_usd", null);
    cost.put("cached_input_cost_usd", null);
    cost.put("output_cost_usd", null);
    cost.put("reasoning_cost_usd", null);
    cost.put("total_cost_usd", null);
    cost.put("source", "not_configured");
    return cost;
  }

  private static int firstInt(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      if (node != null && node.isNumber()) {
        return node.asInt();
      }
    }
    return 0;
  }

  private static Map<?, ?> mapValue(Object value) {
    return value instanceof Map<?, ?> map ? map : null;
  }

  private static Map<?, ?> firstMap(Map<?, ?> map, String... keys) {
    for (String key : keys) {
      Object value = map.get(key);
      if (value instanceof Map<?, ?> nested) {
        return nested;
      }
    }
    return null;
  }

  private static Map<?, ?> modelPricing(Map<?, ?> root, String model) {
    Map<?, ?> models = firstMap(root, "models", "model_pricing", "modelPricing");
    if (models == null) {
      return hasRate(root) ? root : null;
    }
    Object exact = models.get(model);
    if (exact instanceof Map<?, ?> exactMap) {
      return exactMap;
    }
    Object defaultModel = models.get("default");
    if (defaultModel instanceof Map<?, ?> defaultMap) {
      return defaultMap;
    }
    return null;
  }

  private static boolean hasRate(Map<?, ?> map) {
    return firstRate(
        map,
        "input_per_million",
        "inputPerMillion",
        "prompt_per_million",
        "promptPerMillion",
        "output_per_million",
        "outputPerMillion",
        "completion_per_million",
        "completionPerMillion",
        "input_per_1k",
        "inputPer1k",
        "output_per_1k",
        "outputPer1k") != null;
  }

  private static Rate firstRate(Map<?, ?> map, String... keys) {
    for (String key : keys) {
      BigDecimal value = decimalValue(map.get(key));
      if (value != null) {
        BigDecimal divisor = key.toLowerCase(Locale.ROOT).contains("1k")
            ? BigDecimal.valueOf(1_000)
            : BigDecimal.valueOf(1_000_000);
        return new Rate(value, divisor);
      }
    }
    return null;
  }

  private static BigDecimal decimalValue(Object value) {
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Number number) {
      return BigDecimal.valueOf(number.doubleValue());
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return new BigDecimal(text.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static BigDecimal tokenCost(int tokens, Rate rate) {
    if (tokens <= 0 || rate == null) {
      return null;
    }
    return rate.price()
        .multiply(BigDecimal.valueOf(tokens))
        .divide(rate.divisor(), 12, RoundingMode.HALF_UP);
  }

  private static BigDecimal sum(BigDecimal... values) {
    BigDecimal total = BigDecimal.ZERO;
    boolean hasValue = false;
    for (BigDecimal value : values) {
      if (value != null) {
        total = total.add(value);
        hasValue = true;
      }
    }
    return hasValue ? total : null;
  }

  private static String money(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  private static String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String firstText(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      String text = textual(node);
      if (!text.isBlank()) {
        return text;
      }
    }
    return "";
  }

  private static String textual(JsonNode node) {
    return node != null && node.isTextual() ? node.asText("") : "";
  }

  private static int arraySize(JsonNode node) {
    return node != null && node.isArray() ? node.size() : 0;
  }

  private static int safetySignalCount(JsonNode root) {
    int count = 0;
    count += arraySize(root.path("safetyRatings"));
    count += arraySize(root.path("safety_ratings"));
    count += arraySize(root.path("safety"));
    if (root.path("promptFeedback").isObject()) {
      count++;
    }
    if (root.path("content_filter_results").isObject()) {
      count++;
    }
    return count;
  }

  private static boolean safetyFlagged(JsonNode root) {
    if (root == null || root.isMissingNode() || root.isNull()) {
      return false;
    }
    if (root.path("blocked").asBoolean(false) || root.path("safetyFlagged").asBoolean(false)) {
      return true;
    }
    if (root.path("finish_reason").asText("").equalsIgnoreCase("content_filter")) {
      return true;
    }
    for (String field : new String[] {"safetyRatings", "safety_ratings", "safety"}) {
      JsonNode ratings = root.path(field);
      if (!ratings.isArray()) {
        continue;
      }
      for (JsonNode rating : ratings) {
        if (rating.path("blocked").asBoolean(false)
            || !rating.path("isSafe").asBoolean(true)
            || rating.path("flagged").asBoolean(false)) {
          return true;
        }
      }
    }
    return root.path("content_filter_results").toString().toLowerCase(Locale.ROOT).contains("filtered");
  }

  private static String normalizeFinishReason(String providerReason) {
    String normalized = providerReason == null ? "" : providerReason.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "complete", "stop", "end_turn", "eos" -> "stop";
      case "max_tokens", "length" -> "length";
      case "tool_use", "function_call", "tool_calls" -> "tool_calls";
      default -> normalized;
    };
  }

  private record Rate(BigDecimal price, BigDecimal divisor) {
  }
}
