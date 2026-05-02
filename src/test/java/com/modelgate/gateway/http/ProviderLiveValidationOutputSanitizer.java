package com.modelgate.gateway.http;

import java.util.Set;
import java.util.regex.Pattern;

final class ProviderLiveValidationOutputSanitizer {
  private static final Set<String> SECRET_KEYS = Set.of(
      "api_key",
      "apikey",
      "x-api-key",
      "authorization",
      "aws_access_key_id",
      "aws_secret_access_key",
      "aws_session_token",
      "token",
      "access_token",
      "refresh_token",
      "secret");

  private ProviderLiveValidationOutputSanitizer() {}

  static String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String sanitized = value;
    for (String key : SECRET_KEYS) {
      sanitized = redactJsonStringField(sanitized, key);
      sanitized = redactHeaderLine(sanitized, key);
    }
    return sanitized;
  }

  private static String redactJsonStringField(String value, String key) {
    Pattern pattern = Pattern.compile(
        "(?i)(\"" + Pattern.quote(key) + "\"\\s*:\\s*\")([^\"]*)(\")");
    return pattern.matcher(value).replaceAll("$1[REDACTED]$3");
  }

  private static String redactHeaderLine(String value, String key) {
    Pattern pattern = Pattern.compile(
        "(?im)^(" + Pattern.quote(key) + "\\s*:\\s*)(.*)$");
    return pattern.matcher(value).replaceAll("$1[REDACTED]");
  }
}
