package com.modelgate.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderLiveValidationOutputSanitizerTest {
  @Test
  void redactsSecretBearingJsonFieldsAndAuthorizationHeaders() {
    String sanitized = ProviderLiveValidationOutputSanitizer.sanitize("""
        {
          "api_key": "sk-live-secret",
          "aws_secret_access_key": "aws-secret",
          "aws_session_token": "session-secret",
          "authorization": "Bearer provider-secret",
          "nested": {"token": "nested-token"},
          "safe": "visible"
        }
        """);

    assertThat(sanitized).contains("\"safe\": \"visible\"");
    assertThat(sanitized).contains("\"api_key\": \"[REDACTED]\"");
    assertThat(sanitized).contains("\"aws_secret_access_key\": \"[REDACTED]\"");
    assertThat(sanitized).contains("\"aws_session_token\": \"[REDACTED]\"");
    assertThat(sanitized).contains("\"authorization\": \"[REDACTED]\"");
    assertThat(sanitized).contains("\"token\": \"[REDACTED]\"");
    assertThat(sanitized).doesNotContain("sk-live-secret");
    assertThat(sanitized).doesNotContain("aws-secret");
    assertThat(sanitized).doesNotContain("session-secret");
    assertThat(sanitized).doesNotContain("provider-secret");
    assertThat(sanitized).doesNotContain("nested-token");
  }

  @Test
  void redactsHeaderLikePlainText() {
    String sanitized = ProviderLiveValidationOutputSanitizer.sanitize("""
        authorization: Bearer provider-secret
        x-api-key: sk-live-secret
        normal: visible
        """);

    assertThat(sanitized).contains("normal: visible");
    assertThat(sanitized).contains("authorization: [REDACTED]");
    assertThat(sanitized).contains("x-api-key: [REDACTED]");
    assertThat(sanitized).doesNotContain("provider-secret");
    assertThat(sanitized).doesNotContain("sk-live-secret");
  }
}
