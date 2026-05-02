package com.modelgate.gateway.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

final class ProviderLiveValidationSelector {
  private ProviderLiveValidationSelector() {}

  static boolean matches(JsonNode scenario, String filter) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    String normalizedFilter = filter.trim().toLowerCase(Locale.ROOT);
    if ("all".equals(normalizedFilter) || "*".equals(normalizedFilter)) {
      return true;
    }
    String haystack = searchableText(scenario);
    for (String term : filter.split(",")) {
      String normalizedTerm = term.trim().toLowerCase(Locale.ROOT);
      if (!normalizedTerm.isBlank() && haystack.contains(normalizedTerm)) {
        return true;
      }
    }
    return false;
  }

  static boolean hasExplicitFilter(String filter) {
    return filter != null && !filter.isBlank();
  }

  private static String searchableText(JsonNode scenario) {
    StringBuilder text = new StringBuilder();
    append(text, scenario.path("provider").asText(""));
    append(text, scenario.path("name").asText(""));
    JsonNode tags = scenario.path("tags");
    if (tags.isArray()) {
      tags.forEach(tag -> append(text, tag.asText("")));
    }
    return text.toString().toLowerCase(Locale.ROOT);
  }

  private static void append(StringBuilder builder, String value) {
    if (value != null && !value.isBlank()) {
      builder.append(' ').append(value);
    }
  }
}
