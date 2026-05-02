package com.modelgate.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.gateway.config.GatewayConfigParser;
import com.modelgate.gateway.provider.ProviderRequest;
import com.modelgate.gateway.provider.ProviderRequestFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderLiveValidationScenarioConfigTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void buildsConfigWithApiKeyAndBaseUrlOverrideFromEnvironment() throws Exception {
    JsonNode scenario = scenario("""
        {
          "provider": "openai",
          "api_key_env": "OPENAI_API_KEY",
          "base_url_env": "MODELGATE_LIVE_OPENAI_BASE_URL"
        }
        """);

    String config = ProviderLiveValidationScenarioConfig.gatewayConfig(
        scenario,
        Map.of(
            "OPENAI_API_KEY", "sk-live",
            "MODELGATE_LIVE_OPENAI_BASE_URL", "https://api.openai.com/v1"));

    assertThat(objectMapper.readTree(config)).isEqualTo(objectMapper.readTree("""
        {
          "provider": "openai",
          "api_key": "sk-live",
          "custom_host": "https://api.openai.com/v1"
        }
        """));
  }

  @Test
  void omitsCustomHostWhenBaseUrlEnvironmentValueIsBlankOrAbsent() throws Exception {
    JsonNode scenario = scenario("""
        {
          "provider": "openai",
          "api_key_env": "OPENAI_API_KEY",
          "base_url_env": "MODELGATE_LIVE_OPENAI_BASE_URL"
        }
        """);

    JsonNode blankConfig = objectMapper.readTree(ProviderLiveValidationScenarioConfig.gatewayConfig(
        scenario,
        Map.of(
            "OPENAI_API_KEY", "sk-live",
            "MODELGATE_LIVE_OPENAI_BASE_URL", " ")));
    JsonNode absentConfig = objectMapper.readTree(ProviderLiveValidationScenarioConfig.gatewayConfig(
        scenario,
        Map.of("OPENAI_API_KEY", "sk-live")));

    assertThat(blankConfig.has("custom_host")).isFalse();
    assertThat(absentConfig.has("custom_host")).isFalse();
  }

  @Test
  void resolvesScenarioConfigPlaceholdersWithFallbacks() throws Exception {
    JsonNode scenario = scenario("""
        {
          "provider": "sagemaker",
          "config": {
            "aws_region": "${MODELGATE_LIVE_SAGEMAKER_REGION:-us-east-1}",
            "aws_access_key_id": "${AWS_ACCESS_KEY_ID}",
            "aws_session_token": "${AWS_SESSION_TOKEN:-}"
          }
        }
        """);

    String config = ProviderLiveValidationScenarioConfig.gatewayConfig(
        scenario,
        Map.of("AWS_ACCESS_KEY_ID", "akid"));

    assertThat(objectMapper.readTree(config)).isEqualTo(objectMapper.readTree("""
        {
          "provider": "sagemaker",
          "aws_region": "us-east-1",
          "aws_access_key_id": "akid",
          "aws_session_token": ""
        }
        """));
  }

  @Test
  void preservesTypedConfigValuesAndOnlyResolvesSupportedTextPlaceholders() throws Exception {
    JsonNode scenario = scenario("""
        {
          "provider": "workers-ai",
          "config": {
            "workers_ai_account_id": "${CLOUDFLARE_ACCOUNT_ID}",
            "enabled": true,
            "limit": 3,
            "unset": null,
            "literal": "${lowercase_name:-unchanged}"
          }
        }
        """);

    JsonNode config = objectMapper.readTree(ProviderLiveValidationScenarioConfig.gatewayConfig(
        scenario,
        Map.of("CLOUDFLARE_ACCOUNT_ID", "acct-123")));

    assertThat(config.path("workers_ai_account_id").asText()).isEqualTo("acct-123");
    assertThat(config.path("enabled").isBoolean()).isTrue();
    assertThat(config.path("limit").isInt()).isTrue();
    assertThat(config.path("unset").isNull()).isTrue();
    assertThat(config.path("literal").asText()).isEqualTo("${lowercase_name:-unchanged}");
  }

  @Test
  void trimsBaseUrlEnvironmentValueBeforeWritingCustomHost() throws Exception {
    JsonNode scenario = scenario("""
        {
          "provider": "openai",
          "base_url_env": "MODELGATE_LIVE_OPENAI_BASE_URL"
        }
        """);

    JsonNode config = objectMapper.readTree(ProviderLiveValidationScenarioConfig.gatewayConfig(
        scenario,
        Map.of("MODELGATE_LIVE_OPENAI_BASE_URL", "  https://api.openai.com/v1  ")));

    assertThat(config.path("custom_host").asText()).isEqualTo("https://api.openai.com/v1");
  }

  @Test
  void resolvesEndpointTextWithoutJsonRoundTrip() throws Exception {
    JsonNode scenario = scenario("""
        {
          "endpoint": "/v1/endpoints/${SAGEMAKER_ENDPOINT_NAME}/invocations"
        }
        """);

    String endpoint = ProviderLiveValidationScenarioConfig.resolvedText(
        scenario.path("endpoint"),
        Map.of("SAGEMAKER_ENDPOINT_NAME", "endpoint-a"));

    assertThat(endpoint).isEqualTo("/v1/endpoints/endpoint-a/invocations");
  }

  @Test
  void baseUrlEndingInVersionPrefixDoesNotDuplicateGatewayVersionPath() throws Exception {
    JsonNode scenario = scenario("""
        {
          "provider": "openai",
          "api_key_env": "OPENAI_API_KEY",
          "base_url_env": "MODELGATE_LIVE_OPENAI_BASE_URL"
        }
        """);
    String config = ProviderLiveValidationScenarioConfig.gatewayConfig(
        scenario,
        Map.of(
            "OPENAI_API_KEY", "sk-live",
            "MODELGATE_LIVE_OPENAI_BASE_URL", "https://api.openai.com/v1"));
    ProviderRequestFactory factory = new ProviderRequestFactory(new GatewayConfigParser(objectMapper));

    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{\"model\":\"gpt-4o-mini\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", config));

    assertThat(request.url()).isEqualTo("https://api.openai.com/v1/chat/completions");
  }

  private JsonNode scenario(String json) throws Exception {
    return objectMapper.readTree(json);
  }
}
