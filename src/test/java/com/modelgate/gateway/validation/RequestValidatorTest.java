package com.modelgate.gateway.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RequestValidatorTest {
  private final RequestValidator validator = new RequestValidator(Set.of("openai", "anthropic"));
  private final RequestValidator extendedValidator =
      new RequestValidator(Set.of("openai", "azure-openai", "bedrock", "workers-ai"));

  @Test
  void requiresProviderOrConfigHeader() {
    ValidationResult result = validator.validate(Map.of("content-type", "application/json"));

    assertThat(result.valid()).isFalse();
    assertThat(result.status()).isEqualTo(400);
    assertThat(result.message()).contains("x-modelgate-config/x-modelgate-provider");
  }

  @Test
  void rejectsConfigWithEmptyTargetsAndNoProvider() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", "{\"targets\":[]}"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("provider");
  }

  @Test
  void rejectsTargetWithoutProvider() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", "{\"targets\":[{\"name\":\"missing-provider\"}]}"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("provider");
  }

  @Test
  void acceptsModelgateProviderHeader() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai"));

    assertThat(result.valid()).isTrue();
  }

  @Test
  void rejectsInvalidProvider() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-portkey-provider", "unknown"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("Invalid provider");
  }

  @Test
  void rejectsMalformedDefaultGuardrailHeaderJson() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-default-input-guardrails", "[not-json"));

    assertThat(result.valid()).isFalse();
    assertThat(result.status()).isEqualTo(400);
    assertThat(result.message()).contains("Invalid default input guardrails");
  }

  @Test
  void rejectsProviderHeaderWithoutDefaultBaseUrlWhenCustomHostIsMissing() {
    ValidationResult result = extendedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "azure-openai"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("custom_host");
  }

  @Test
  void acceptsAzureOpenAiResourceNameWithoutCustomHost() {
    ValidationResult result = extendedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        """
            {
              "provider": "azure-openai",
              "api_key": "sk-test",
              "resource_name": "modelgate-resource",
              "deployment_id": "gpt-4o",
              "api_version": "2024-02-01"
            }
            """));

    assertThat(result.valid()).isTrue();
  }

  @Test
  void rejectsUnsafeAzureOpenAiResourceName() {
    ValidationResult result = extendedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        """
            {
              "provider": "azure-openai",
              "api_key": "sk-test",
              "resource_name": "../metadata.azure.com",
              "deployment_id": "gpt-4o",
              "api_version": "2024-02-01"
            }
            """));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("resource_name");
  }

  @Test
  void acceptsProviderHeaderWithoutDefaultBaseUrlWhenCustomHostIsPresent() {
    ValidationResult result = extendedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "azure-openai",
        "x-modelgate-custom-host", "https://resource.openai.azure.com/openai"));

    assertThat(result.valid()).isTrue();
  }

  @Test
  void acceptsWorkersAiProviderHeaderWhenAccountIdHeaderIsPresent() {
    ValidationResult result = extendedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "workers-ai",
        "x-modelgate-workers-ai-account-id", "account-123"));

    assertThat(result.valid()).isTrue();
  }

  @Test
  void rejectsUnsafeWorkersAiAccountIdHeader() {
    for (String accountId : java.util.List.of("account/123", "../account", "account?x=1", "account#frag", "account 123")) {
      ValidationResult result = extendedValidator.validate(Map.of(
          "content-type", "application/json",
          "x-modelgate-provider", "workers-ai",
          "x-modelgate-workers-ai-account-id", accountId));

      assertThat(result.valid()).describedAs(accountId).isFalse();
      assertThat(result.message()).describedAs(accountId).contains("workers_ai_account_id");
    }
  }

  @Test
  void rejectsForwardHeadersThatForwardTheForwardHeaderItself() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-portkey-provider", "openai",
        "x-portkey-forward-headers", "authorization, x-portkey-forward-headers"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("forward_headers");
  }

  @Test
  void rejectsModelgateForwardHeadersThatForwardEitherForwardHeaderItself() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-forward-headers", "authorization, x-modelgate-forward-headers"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("forward_headers");
  }

  @Test
  void rejectsNonNumericRequestTimeoutHeader() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-request-timeout", "soon"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("request_timeout");
  }

  @Test
  void rejectsZeroRequestTimeoutHeader() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-portkey-provider", "openai",
        "x-portkey-request-timeout", "0"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("request_timeout");
  }

  @Test
  void rejectsNegativeRequestTimeoutInConfigHeader() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", "{\"provider\":\"openai\",\"request_timeout\":-1}"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("request_timeout");
  }

  @Test
  void rejectsNegativeRequestTimeoutInNestedTargetConfig() {
    String config = """
        {
          "strategy": {"mode": "fallback"},
          "targets": [
            {"name": "primary", "provider": "openai", "api_key": "sk-primary", "request_timeout": -1}
          ]
        }
        """;

    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", config));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("request_timeout");
  }

  @Test
  void rejectsUnsafeCustomHostValues() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-portkey-provider", "openai",
        "x-portkey-custom-host", "http://169.254.169.254/latest/meta-data"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("Invalid custom host");
  }

  @Test
  void rejectsAzureMetadataCustomHost() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-custom-host", "http://metadata.azure.com/metadata/instance"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("Invalid custom host");
  }

  @Test
  void rejectsInstanceDataMetadataCustomHost() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-custom-host", "http://instance-data/latest/meta-data"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("Invalid custom host");
  }

  @Test
  void rejectsBareMetadataCustomHost() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-custom-host", "http://metadata/latest"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("Invalid custom host");
  }

  @Test
  void rejectsPrivateNetworkTldsInCustomHost() {
    for (String host : java.util.List.of("service.lan", "service.corp", "service.intranet", "service.home")) {
      ValidationResult result = validator.validate(Map.of(
          "content-type", "application/json",
          "x-modelgate-provider", "openai",
          "x-modelgate-custom-host", "http://" + host + "/v1"));

      assertThat(result.valid()).describedAs(host).isFalse();
      assertThat(result.message()).describedAs(host).contains("Invalid custom host");
    }
  }

  @Test
  void rejectsReservedTldsInCustomHost() {
    for (String host : java.util.List.of("service.test", "service.invalid", "service.onion", "service.localdomain")) {
      ValidationResult result = validator.validate(Map.of(
          "content-type", "application/json",
          "x-modelgate-provider", "openai",
          "x-modelgate-custom-host", "http://" + host + "/v1"));

      assertThat(result.valid()).describedAs(host).isFalse();
      assertThat(result.message()).describedAs(host).contains("Invalid custom host");
    }
  }

  @Test
  void rejectsDecimalIpCustomHostRepresentation() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-custom-host", "http://2130706433/v1"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("Invalid custom host");
  }

  @Test
  void rejectsHexIpCustomHostRepresentation() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-custom-host", "http://0x7f000001/v1"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("Invalid custom host");
  }

  @Test
  void rejectsShortenedIpCustomHostRepresentation() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-custom-host", "http://127.1/v1"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("Invalid custom host");
  }

  @Test
  void rejectsOctalLikeIpCustomHostRepresentation() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-custom-host", "http://0177.0.0.1/v1"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("Invalid custom host");
  }

  @Test
  void rejectsMalformedShortenedNumericHostWithoutThrowing() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-custom-host", "http://9999999999.1/v1"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("Invalid custom host");
  }

  @Test
  void allowsLocalhostCustomHostForTrustedLocalDevelopment() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-portkey-provider", "openai",
        "x-portkey-custom-host", "http://localhost:8787"));

    assertThat(result.valid()).isTrue();
  }

  @Test
  void allowsExplicitlyTrustedPrivateCustomHost() {
    RequestValidator trustedValidator = new RequestValidator(
        Set.of("openai"),
        Set.of("10.0.0.2", "internal-models.example.com"));

    ValidationResult privateIpResult = trustedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-custom-host", "http://10.0.0.2/v1"));
    ValidationResult internalHostResult = trustedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-custom-host", "https://internal-models.example.com/openai"));

    assertThat(privateIpResult.valid()).isTrue();
    assertThat(internalHostResult.valid()).isTrue();
  }

  @Test
  void trustedCustomHostsDoNotBypassMetadataHostBlocks() {
    RequestValidator trustedValidator = new RequestValidator(
        Set.of("openai"),
        Set.of("169.254.169.254", "metadata.google.internal"));

    ValidationResult linkLocalMetadataResult = trustedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-custom-host", "http://169.254.169.254/latest/meta-data"));
    ValidationResult dnsMetadataResult = trustedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-provider", "openai",
        "x-modelgate-custom-host", "http://metadata.google.internal/computeMetadata/v1"));

    assertThat(linkLocalMetadataResult.valid()).isFalse();
    assertThat(dnsMetadataResult.valid()).isFalse();
  }

  @Test
  void rejectsInvalidProviderInConfigHeader() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", "{\"provider\":\"unknown\",\"api_key\":\"sk-test\"}"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("Invalid provider");
  }

  @Test
  void rejectsConfigProviderWithoutDefaultBaseUrlWhenCustomHostIsMissing() {
    ValidationResult result = extendedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", "{\"provider\":\"azure-openai\",\"api_key\":\"sk-test\"}"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("custom_host");
  }

  @Test
  void acceptsConfigProviderWithoutDefaultBaseUrlWhenCustomHostIsPresent() {
    ValidationResult result = extendedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        """
            {
              "provider": "azure-openai",
              "api_key": "sk-test",
              "custom_host": "https://resource.openai.azure.com/openai"
            }
            """));

    assertThat(result.valid()).isTrue();
  }

  @Test
  void acceptsWorkersAiConfigProviderWhenAccountIdIsPresent() {
    ValidationResult result = extendedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        """
            {
              "provider": "workers-ai",
              "api_key": "cf-token",
              "workers_ai_account_id": "account-123"
            }
            """));

    assertThat(result.valid()).isTrue();
  }

  @Test
  void rejectsUnsafeWorkersAiAccountIdInConfig() {
    ValidationResult result = extendedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        """
            {
              "provider": "workers-ai",
              "api_key": "cf-token",
              "workers_ai_account_id": "../account"
            }
            """));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("workers_ai_account_id");
  }

  @Test
  void rejectsNestedTargetProviderWithoutDefaultBaseUrlWhenNoCustomHostCanBeInherited() {
    ValidationResult result = extendedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        """
            {
              "strategy": {"mode": "fallback"},
              "targets": [
                {"name": "primary", "provider": "bedrock", "api_key": "sk-test"}
              ]
            }
            """));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("custom_host");
  }

  @Test
  void acceptsNestedTargetProviderWithoutDefaultBaseUrlWhenTopLevelCustomHostCanBeInherited() {
    ValidationResult result = extendedValidator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        """
            {
              "custom_host": "https://bedrock-runtime.example.com",
              "strategy": {"mode": "fallback"},
              "targets": [
                {"name": "primary", "provider": "bedrock", "api_key": "sk-test"}
              ]
            }
            """));

    assertThat(result.valid()).isTrue();
  }

  @Test
  void rejectsConfigWithoutProviderOrTargetsWhenProviderHeaderIsMissing() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", "{\"cache\":{\"mode\":\"simple\"}}"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("provider");
  }

  @Test
  void rejectsLegacyOptionsConfigContainer() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", "{\"provider\":\"openai\",\"options\":[]}"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("not supported");
  }

  @Test
  void rejectsUnsafeCustomHostInConfigHeader() {
    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        "{\"provider\":\"openai\",\"api_key\":\"sk-test\",\"custom_host\":\"http://169.254.169.254/latest/meta-data\"}"));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("Invalid custom host");
  }

  @Test
  void rejectsUnsafeCustomHostInNestedTargetConfig() {
    String config = """
        {
          "strategy": {"mode": "fallback", "on_status_codes": [500]},
          "targets": [
            {"name": "primary", "provider": "openai", "api_key": "sk-primary", "custom_host": "https://api.openai.com"},
            {"name": "backup", "provider": "openai", "api_key": "sk-backup", "custom_host": "http://10.0.0.2"}
          ]
        }
        """;

    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", config));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("Invalid custom host");
  }

  @Test
  void rejectsInvalidProviderInNestedTargetConfig() {
    String config = """
        {
          "strategy": {"mode": "fallback", "on_status_codes": [500]},
          "targets": [
            {"name": "primary", "provider": "openai", "api_key": "sk-primary"},
            {"name": "backup", "provider": "unknown", "api_key": "sk-backup"}
          ]
        }
        """;

    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", config));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("Invalid provider");
  }

  @Test
  void rejectsConfigForwardHeadersThatForwardTheForwardHeaderItself() {
    String config = """
        {
          "provider": "openai",
          "api_key": "sk-test",
          "forward_headers": ["authorization", "x-modelgate-forward-headers"]
        }
        """;

    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", config));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("forward_headers");
  }

  @Test
  void rejectsNestedTargetConfigForwardHeadersThatForwardTheForwardHeaderItself() {
    String config = """
        {
          "strategy": {"mode": "fallback", "on_status_codes": [500]},
          "targets": [
            {"name": "primary", "provider": "openai", "api_key": "sk-primary"},
            {
              "name": "backup",
              "provider": "openai",
              "api_key": "sk-backup",
              "forward_headers": ["authorization", "x-portkey-forward-headers"]
            }
          ]
        }
        """;

    ValidationResult result = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", config));

    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("forward_headers");
  }

  @Test
  void rejectsCamelCaseSecurityFieldsInConfigHeaderAndNestedTargets() {
    String unsafeCustomHostConfig = """
        {
          "provider": "openai",
          "apiKey": "sk-test",
          "customHost": "http://169.254.169.254/latest/meta-data"
        }
        """;
    String unsafeForwardHeadersConfig = """
        {
          "strategy": {"mode": "fallback"},
          "targets": [
            {
              "name": "backup",
              "provider": "openai",
              "apiKey": "sk-backup",
              "forwardHeaders": ["authorization", "x-modelgate-forward-headers"]
            }
          ]
        }
        """;

    ValidationResult customHostResult = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", unsafeCustomHostConfig));
    ValidationResult forwardHeadersResult = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", unsafeForwardHeadersConfig));

    assertThat(customHostResult.valid()).isFalse();
    assertThat(customHostResult.message()).contains("Invalid custom host");
    assertThat(forwardHeadersResult.valid()).isFalse();
    assertThat(forwardHeadersResult.message()).contains("forward_headers");
  }

  @Test
  void validatesLaterSecurityAliasesAfterCamelCaseNormalization() {
    String unsafeCustomHostConfig = """
        {
          "provider": "openai",
          "apiKey": "sk-test",
          "custom_host": "http://localhost:8787",
          "customHost": "http://169.254.169.254/latest/meta-data"
        }
        """;
    String unsafeForwardHeadersConfig = """
        {
          "provider": "openai",
          "apiKey": "sk-test",
          "forward_headers": ["authorization"],
          "forwardHeaders": ["x-modelgate-forward-headers"]
        }
        """;

    ValidationResult customHostResult = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", unsafeCustomHostConfig));
    ValidationResult forwardHeadersResult = validator.validate(Map.of(
        "content-type", "application/json",
        "x-modelgate-config", unsafeForwardHeadersConfig));

    assertThat(customHostResult.valid()).isFalse();
    assertThat(customHostResult.message()).contains("Invalid custom host");
    assertThat(forwardHeadersResult.valid()).isFalse();
    assertThat(forwardHeadersResult.message()).contains("forward_headers");
  }
}
