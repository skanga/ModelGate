package com.modelgate.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayConfigParserTest {
  private final GatewayConfigParser parser = new GatewayConfigParser(new ObjectMapper());

  @Test
  void parsesNestedTargetsAndNormalizesSnakeCaseHeaders() {
    String json = """
        {
          "strategy": {"mode": "fallback", "on_status_codes": [429, 500]},
          "retry": {"attempts": 2, "use_retry_after_header": true},
          "request_timeout": 1200,
          "forward_headers": ["x-client-tenant", "x-modelgate-metadata"],
          "input_guardrails": [
            {"default.contains": {"operator": "none", "words": ["secret"]}, "deny": true}
          ],
          "targets": [
            {"name": "primary", "provider": "openai", "api_key": "sk-primary"},
            {
              "name": "backup",
              "provider": "anthropic",
              "api_key": "sk-backup",
              "weight": 3,
              "retry": {"attempts": 1, "on_status_codes": [503]},
              "request_timeout": 750,
              "forward_headers": ["x-target-header"]
            }
          ]
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.strategy()).isNotNull();
    assertThat(config.strategy().mode()).isEqualTo(StrategyMode.FALLBACK);
    assertThat(config.strategy().onStatusCodes()).containsExactly(429, 500);
    assertThat(config.retry().attempts()).isEqualTo(2);
    assertThat(config.retry().useRetryAfterHeader()).isTrue();
    assertThat(config.requestTimeoutMillis()).isEqualTo(1200);
    assertThat(config.forwardHeaders()).containsExactly("x-client-tenant", "x-modelgate-metadata");
    assertThat(config.inputGuardrails()).hasSize(1);
    assertThat(config.targets()).extracting(Target::name).containsExactly("primary", "backup");
    assertThat(config.targets().get(1).provider()).isEqualTo("anthropic");
    assertThat(config.targets().get(1).weight()).isEqualTo(3.0);
    assertThat(config.targets().get(1).retry().attempts()).isEqualTo(1);
    assertThat(config.targets().get(1).retry().onStatusCodes()).containsExactly(503);
    assertThat(config.targets().get(1).requestTimeoutMillis()).isEqualTo(750);
    assertThat(config.targets().get(1).forwardHeaders()).containsExactly("x-target-header");
  }

  @Test
  void buildsSingleProviderConfigFromHeadersWhenConfigOmitsProviderDetails() {
    String json = """
        {"retry": {"attempts": 1}}
        """;
    Map<String, String> headers = Map.of(
        "x-portkey-provider", "openai",
        "authorization", "Bearer sk-from-header");

    GatewayConfig config = parser.parse(json, headers);

    assertThat(config.provider()).isEqualTo("openai");
    assertThat(config.apiKey()).isEqualTo("sk-from-header");
    assertThat(config.retry().attempts()).isEqualTo(1);
  }

  @Test
  void modelgateProviderHeaderTakesPrecedenceOverPortkeyCompatibilityHeader() {
    GatewayConfig config = parser.parse("{}", Map.of(
        "x-portkey-provider", "anthropic",
        "x-modelgate-provider", "openai"));

    assertThat(config.provider()).isEqualTo("openai");
  }

  @Test
  void parsesWorkersAiAccountIdFromModelgateHeader() {
    GatewayConfig config = parser.parse("{}", Map.of(
        "x-modelgate-provider", "workers-ai",
        "x-modelgate-workers-ai-account-id", "account-123"));

    assertThat(config.provider()).isEqualTo("workers-ai");
    assertThat(config.providerOptions().stringValue("workersAiAccountId")).isEqualTo("account-123");
  }

  @Test
  void parsesCamelCaseDirectProviderFields() {
    String json = """
        {
          "provider": "openai",
          "apiKey": "sk-test",
          "customHost": "http://localhost:9001",
          "requestTimeout": 1500,
          "forwardHeaders": ["x-client-tenant"]
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.apiKey()).isEqualTo("sk-test");
    assertThat(config.customHost()).isEqualTo("http://localhost:9001");
    assertThat(config.requestTimeoutMillis()).isEqualTo(1500);
    assertThat(config.forwardHeaders()).containsExactly("x-client-tenant");
  }

  @Test
  void preservesProviderSpecificOptionsWithCamelCaseKeys() {
    String json = """
        {
          "provider": "azure-ai",
          "api_key": "sk-provider",
          "resource_name": "resource-a",
          "deployment_id": "deploy-a",
          "api_version": "2024-10-21",
          "azure_ad_token": "Bearer aad",
          "azure_auth_mode": "entra",
          "azure_managed_client_id": "managed-client",
          "azure_workload_client_id": "workload-client",
          "azure_entra_client_id": "entra-client",
          "azure_entra_client_secret": "entra-secret",
          "azure_entra_tenant_id": "entra-tenant",
          "azure_model_name": "azure-model",
          "azure_entra_scope": "scope-a",
          "azure_extra_params": "pass-through",
          "azure_foundry_url": "https://foundry.example",
          "azure_deployment_name": "foundry-deploy",
          "stability_client_id": "stability-client",
          "stability_client_user_id": "stability-user",
          "stability_client_version": "stability-version",
          "aws_secret_access_key": "aws-secret",
          "aws_access_key_id": "aws-access",
          "aws_session_token": "aws-session",
          "aws_region": "us-east-1",
          "aws_auth_type": "apiKey",
          "aws_role_arn": "role-arn",
          "aws_external_id": "external-id",
          "aws_s3_bucket": "bucket-a",
          "aws_s3_object_key": "object-a",
          "aws_bedrock_model": "bedrock-model",
          "aws_server_side_encryption": "AES256",
          "aws_server_side_encryption_kms_key_id": "kms-key",
          "amzn_sagemaker_custom_attributes": "custom-attrs",
          "amzn_sagemaker_target_model": "target-model",
          "amzn_sagemaker_target_variant": "target-variant",
          "amzn_sagemaker_target_container_hostname": "target-container",
          "amzn_sagemaker_inference_id": "inference-id",
          "amzn_sagemaker_enable_explanations": "enabled",
          "amzn_sagemaker_inference_component": "component-a",
          "amzn_sagemaker_session_id": "session-a",
          "amzn_sagemaker_model_name": "sage-model",
          "workers_ai_account_id": "workers-account",
          "openai_organization": "org-a",
          "openai_project": "project-a",
          "openai_beta": "assistants=v2",
          "huggingface_base_url": "https://hf.example",
          "vertex_project_id": "vertex-project",
          "vertex_region": "us-central1",
          "vertex_storage_bucket_name": "vertex-bucket",
          "vertex_model_name": "vertex-model",
          "vertex_batch_endpoint": "/batch",
          "fireworks_account_id": "fireworks-account",
          "fireworks_file_length": "42",
          "anthropic_beta": "beta-a",
          "anthropic_version": "2023-06-01",
          "anthropic_api_key": "sk-anthropic",
          "snowflake_account": "snowflake-a",
          "oracle_api_version": "20231130",
          "oracle_region": "us-ashburn-1",
          "oracle_compartment_id": "compartment-a",
          "oracle_serving_mode": "ON_DEMAND",
          "oracle_tenancy": "tenancy-a",
          "oracle_user": "user-a",
          "oracle_fingerprint": "fingerprint-a",
          "oracle_private_key": "private-key-a",
          "oracle_key_passphrase": "passphrase-a",
          "mistral_fim_completion": "true",
          "strict_open_ai_compliance": true
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.providerOptions().asMap())
        .containsEntry("provider", "azure-ai")
        .containsEntry("apiKey", "sk-provider")
        .containsEntry("resourceName", "resource-a")
        .containsEntry("deploymentId", "deploy-a")
        .containsEntry("apiVersion", "2024-10-21")
        .containsEntry("azureAdToken", "Bearer aad")
        .containsEntry("azureAuthMode", "entra")
        .containsEntry("azureManagedClientId", "managed-client")
        .containsEntry("azureWorkloadClientId", "workload-client")
        .containsEntry("azureEntraClientId", "entra-client")
        .containsEntry("azureEntraClientSecret", "entra-secret")
        .containsEntry("azureEntraTenantId", "entra-tenant")
        .containsEntry("azureModelName", "azure-model")
        .containsEntry("azureEntraScope", "scope-a")
        .containsEntry("azureExtraParameters", "pass-through")
        .containsEntry("azureFoundryUrl", "https://foundry.example")
        .containsEntry("azureDeploymentName", "foundry-deploy")
        .containsEntry("stabilityClientId", "stability-client")
        .containsEntry("stabilityClientUserId", "stability-user")
        .containsEntry("stabilityClientVersion", "stability-version")
        .containsEntry("awsSecretAccessKey", "aws-secret")
        .containsEntry("awsAccessKeyId", "aws-access")
        .containsEntry("awsSessionToken", "aws-session")
        .containsEntry("awsRegion", "us-east-1")
        .containsEntry("awsAuthType", "apiKey")
        .containsEntry("awsRoleArn", "role-arn")
        .containsEntry("awsExternalId", "external-id")
        .containsEntry("awsS3Bucket", "bucket-a")
        .containsEntry("awsS3ObjectKey", "object-a")
        .containsEntry("awsBedrockModel", "bedrock-model")
        .containsEntry("awsServerSideEncryption", "AES256")
        .containsEntry("awsServerSideEncryptionKMSKeyId", "kms-key")
        .containsEntry("amznSagemakerCustomAttributes", "custom-attrs")
        .containsEntry("amznSagemakerTargetModel", "target-model")
        .containsEntry("amznSagemakerTargetVariant", "target-variant")
        .containsEntry("amznSagemakerTargetContainerHostname", "target-container")
        .containsEntry("amznSagemakerInferenceId", "inference-id")
        .containsEntry("amznSagemakerEnableExplanations", "enabled")
        .containsEntry("amznSagemakerInferenceComponent", "component-a")
        .containsEntry("amznSagemakerSessionId", "session-a")
        .containsEntry("amznSagemakerModelName", "sage-model")
        .containsEntry("workersAiAccountId", "workers-account")
        .containsEntry("openaiOrganization", "org-a")
        .containsEntry("openaiProject", "project-a")
        .containsEntry("openaiBeta", "assistants=v2")
        .containsEntry("huggingfaceBaseUrl", "https://hf.example")
        .containsEntry("vertexProjectId", "vertex-project")
        .containsEntry("vertexRegion", "us-central1")
        .containsEntry("vertexStorageBucketName", "vertex-bucket")
        .containsEntry("vertexModelName", "vertex-model")
        .containsEntry("vertexBatchEndpoint", "/batch")
        .containsEntry("fireworksAccountId", "fireworks-account")
        .containsEntry("fireworksFileLength", "42")
        .containsEntry("anthropicBeta", "beta-a")
        .containsEntry("anthropicVersion", "2023-06-01")
        .containsEntry("anthropicApiKey", "sk-anthropic")
        .containsEntry("snowflakeAccount", "snowflake-a")
        .containsEntry("oracleApiVersion", "20231130")
        .containsEntry("oracleRegion", "us-ashburn-1")
        .containsEntry("oracleCompartmentId", "compartment-a")
        .containsEntry("oracleServingMode", "ON_DEMAND")
        .containsEntry("oracleTenancy", "tenancy-a")
        .containsEntry("oracleUser", "user-a")
        .containsEntry("oracleFingerprint", "fingerprint-a")
        .containsEntry("oraclePrivateKey", "private-key-a")
        .containsEntry("oracleKeyPassphrase", "passphrase-a")
        .containsEntry("mistralFimCompletion", "true")
        .containsEntry("strictOpenAiCompliance", true);
  }

  @Test
  void preservesProviderSpecificOptionsOnTargets() {
    String json = """
        {
          "strategy": {"mode": "fallback"},
          "targets": [
            {
              "name": "anthropic-target",
              "provider": "anthropic",
              "api_key": "sk-target",
              "anthropic_beta": "target-beta",
              "anthropic_version": "target-version",
              "openai_project": "target-project"
            }
          ]
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.targets().getFirst().providerOptions().asMap())
        .containsEntry("provider", "anthropic")
        .containsEntry("apiKey", "sk-target")
        .containsEntry("anthropicBeta", "target-beta")
        .containsEntry("anthropicVersion", "target-version")
        .containsEntry("openaiProject", "target-project");
  }

  @Test
  void laterDirectFieldAliasesWinAfterCamelCaseNormalization() {
    String json = """
        {
          "provider": "openai",
          "api_key": "sk-snake",
          "apiKey": "sk-camel",
          "custom_host": "http://localhost:9001",
          "customHost": "http://localhost:9002",
          "request_timeout": 1000,
          "requestTimeout": 1500,
          "forward_headers": ["x-snake-tenant"],
          "forwardHeaders": ["x-camel-tenant"]
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.apiKey()).isEqualTo("sk-camel");
    assertThat(config.customHost()).isEqualTo("http://localhost:9002");
    assertThat(config.requestTimeoutMillis()).isEqualTo(1500);
    assertThat(config.forwardHeaders()).containsExactly("x-camel-tenant");
  }

  @Test
  void splitsCommaSeparatedForwardHeadersInConfigString() {
    String json = """
        {
          "provider": "openai",
          "api_key": "sk-test",
          "forward_headers": "x-client-tenant, x-request-id"
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.forwardHeaders()).containsExactly("x-client-tenant", "x-request-id");
  }

  @Test
  void parsesConditionalRoutingThenAndDefaultFields() {
    String json = """
        {
          "strategy": {
            "mode": "conditional",
            "conditions": [
              {"query": {"metadata.tier": {"$eq": "gold"}}, "then": "premium"}
            ],
            "default": "standard"
          },
          "targets": [
            {"name": "premium", "provider": "openai", "api_key": "sk-premium"},
            {"name": "standard", "provider": "anthropic", "api_key": "sk-standard"}
          ]
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.strategy().mode()).isEqualTo(StrategyMode.CONDITIONAL);
    assertThat(config.strategy().conditions()).hasSize(1);
    assertThat(config.strategy().conditions().getFirst().target()).isEqualTo("premium");
    assertThat(config.strategy().defaultTarget()).isEqualTo("standard");
  }

  @Test
  void parsesCamelCaseStrategyFields() {
    String json = """
        {
          "strategy": {
            "mode": "fallback",
            "onStatusCodes": [429, 503],
            "defaultTarget": "backup"
          },
          "targets": [
            {"name": "primary", "provider": "openai", "apiKey": "sk-primary"},
            {"name": "backup", "provider": "anthropic", "apiKey": "sk-backup"}
          ]
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.strategy().mode()).isEqualTo(StrategyMode.FALLBACK);
    assertThat(config.strategy().onStatusCodes()).containsExactly(429, 503);
    assertThat(config.strategy().defaultTarget()).isEqualTo("backup");
  }

  @Test
  void laterStrategyAndRetryAliasesWinAfterCamelCaseNormalization() {
    String json = """
        {
          "provider": "openai",
          "apiKey": "sk-test",
          "strategy": {
            "mode": "fallback",
            "on_status_codes": [500],
            "onStatusCodes": [429],
            "default_target": "snake",
            "defaultTarget": "camel"
          },
          "retry": {
            "attempts": 2,
            "on_status_codes": [502],
            "onStatusCodes": [503],
            "use_retry_after_header": false,
            "useRetryAfterHeader": true
          }
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.strategy().onStatusCodes()).containsExactly(429);
    assertThat(config.strategy().defaultTarget()).isEqualTo("camel");
    assertThat(config.retry().onStatusCodes()).containsExactly(503);
    assertThat(config.retry().useRetryAfterHeader()).isTrue();
  }

  @Test
  void parsesCamelCaseRetryFields() {
    String json = """
        {
          "provider": "openai",
          "apiKey": "sk-test",
          "retry": {"attempts": 2, "onStatusCodes": [429], "useRetryAfterHeader": true}
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.retry().attempts()).isEqualTo(2);
    assertThat(config.retry().onStatusCodes()).containsExactly(429);
    assertThat(config.retry().useRetryAfterHeader()).isTrue();
  }

  @Test
  void parsesOutputGuardrails() {
    String json = """
        {
          "provider": "openai",
          "api_key": "sk-test",
          "output_guardrails": [
            {"default.contains": {"operator": "none", "words": ["Apple"]}, "deny": true}
          ]
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.outputGuardrails()).hasSize(1);
    assertThat(config.outputGuardrails().getFirst()).containsKey("default.contains");
  }

  @Test
  void parsesDefaultGuardrailsAsExecutableHookListsAndExcludesThemFromProviderOptions() {
    String json = """
        {
          "provider": "openai",
          "api_key": "sk-test",
          "default_input_guardrails": [
            {"id": "default-input", "default.contains": {"operator": "any", "words": ["hello"]}}
          ],
          "default_output_guardrails": [
            {"id": "default-output", "default.contains": {"operator": "any", "words": ["Apple"]}}
          ]
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.defaultInputGuardrails()).hasSize(1);
    assertThat(config.defaultInputGuardrails().getFirst()).containsEntry("id", "default-input");
    assertThat(config.defaultOutputGuardrails()).hasSize(1);
    assertThat(config.defaultOutputGuardrails().getFirst()).containsEntry("id", "default-output");
    assertThat(config.providerOptions().asMap())
        .doesNotContainKeys("defaultInputGuardrails", "defaultOutputGuardrails");
  }

  @Test
  void parsesDefaultGuardrailHeadersWithModelgatePrecedenceOverPortkeyAliases() {
    GatewayConfig config = parser.parse(
        "{\"provider\":\"openai\",\"api_key\":\"sk-test\"}",
        Map.of(
            "x-portkey-default-input-guardrails",
            "[{\"id\":\"portkey-input\",\"default.contains\":{\"operator\":\"any\",\"words\":[\"portkey\"]}}]",
            "x-modelgate-default-input-guardrails",
            "[{\"id\":\"modelgate-input\",\"default.contains\":{\"operator\":\"any\",\"words\":[\"modelgate\"]}}]",
            "x-portkey-default-output-guardrails",
            "[{\"id\":\"portkey-output\",\"default.contains\":{\"operator\":\"any\",\"words\":[\"portkey\"]}}]",
            "x-modelgate-default-output-guardrails",
            "[{\"id\":\"modelgate-output\",\"default.contains\":{\"operator\":\"any\",\"words\":[\"modelgate\"]}}]"));

    assertThat(config.defaultInputGuardrails()).hasSize(1);
    assertThat(config.defaultInputGuardrails().getFirst()).containsEntry("id", "modelgate-input");
    assertThat(config.defaultOutputGuardrails()).hasSize(1);
    assertThat(config.defaultOutputGuardrails().getFirst()).containsEntry("id", "modelgate-output");
  }

  @Test
  void parsesPortkeyHookAliasesAsGuardrails() {
    String json = """
        {
          "provider": "openai",
          "api_key": "sk-test",
          "before_request_hooks": [
            {"checks": [{"id": "contains", "parameters": {"operator": "any", "words": ["hello"]}}]}
          ],
          "after_request_hooks": [
            {"checks": [{"id": "notNull", "parameters": {}}]}
          ]
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.inputGuardrails()).hasSize(1);
    assertThat(config.inputGuardrails().getFirst()).containsKey("checks");
    assertThat(config.outputGuardrails()).hasSize(1);
    assertThat(config.outputGuardrails().getFirst()).containsKey("checks");
    assertThat(config.providerOptions().asMap())
        .doesNotContainKeys("beforeRequestHooks", "afterRequestHooks");
  }

  @Test
  void parsesSimpleCacheMaxAgeAsMillis() {
    String json = """
        {
          "provider": "openai",
          "api_key": "sk-test",
          "cache": {"mode": "simple", "max_age": 5000}
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.cache().enabled()).isTrue();
    assertThat(config.cache().mode()).isEqualTo("simple");
    assertThat(config.cache().ttlMillis()).isEqualTo(5000);
  }

  @Test
  void parsesRedisCacheModeAsEnabled() {
    String json = """
        {
          "provider": "openai",
          "api_key": "sk-test",
          "cache": {"mode": "redis", "max_age": 5000}
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.cache().enabled()).isTrue();
    assertThat(config.cache().mode()).isEqualTo("redis");
    assertThat(config.cache().ttlMillis()).isEqualTo(5000);
  }

  @Test
  void parsesCamelCaseCacheMaxAgeAsMillis() {
    String json = """
        {
          "provider": "openai",
          "apiKey": "sk-test",
          "cache": {"mode": "simple", "maxAge": 7500}
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.cache().enabled()).isTrue();
    assertThat(config.cache().ttlMillis()).isEqualTo(7500);
  }

  @Test
  void laterCacheMaxAgeAliasWinsAfterCamelCaseNormalization() {
    String json = """
        {
          "provider": "openai",
          "apiKey": "sk-test",
          "cache": {"mode": "simple", "max_age": 5000, "maxAge": 7500}
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.cache().ttlMillis()).isEqualTo(7500);
  }

  @Test
  void parsesTargetCustomHost() {
    String json = """
        {
          "strategy": {"mode": "fallback", "on_status_codes": [500]},
          "targets": [
            {"name": "primary", "provider": "openai", "api_key": "sk-primary", "custom_host": "http://localhost:9001"}
          ]
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.targets()).hasSize(1);
    assertThat(config.targets().getFirst().customHost()).isEqualTo("http://localhost:9001");
  }

  @Test
  void parsesCamelCaseTargetFields() {
    String json = """
        {
          "strategy": {"mode": "fallback"},
          "targets": [
            {
              "name": "primary",
              "provider": "openai",
              "apiKey": "sk-primary",
              "customHost": "http://localhost:9002",
              "requestTimeout": 850,
              "forwardHeaders": ["x-target-tenant"],
              "retry": {"attempts": 1, "onStatusCodes": [503]}
            }
          ]
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    Target target = config.targets().getFirst();
    assertThat(target.apiKey()).isEqualTo("sk-primary");
    assertThat(target.customHost()).isEqualTo("http://localhost:9002");
    assertThat(target.requestTimeoutMillis()).isEqualTo(850);
    assertThat(target.forwardHeaders()).containsExactly("x-target-tenant");
    assertThat(target.retry().onStatusCodes()).containsExactly(503);
  }

  @Test
  void laterTargetFieldAliasesWinAfterCamelCaseNormalization() {
    String json = """
        {
          "strategy": {"mode": "fallback"},
          "targets": [
            {
              "name": "primary",
              "provider": "openai",
              "api_key": "sk-snake",
              "apiKey": "sk-camel",
              "custom_host": "http://localhost:9001",
              "customHost": "http://localhost:9002",
              "request_timeout": 700,
              "requestTimeout": 850,
              "forward_headers": ["x-snake-tenant"],
              "forwardHeaders": ["x-camel-tenant"]
            }
          ]
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    Target target = config.targets().getFirst();
    assertThat(target.apiKey()).isEqualTo("sk-camel");
    assertThat(target.customHost()).isEqualTo("http://localhost:9002");
    assertThat(target.requestTimeoutMillis()).isEqualTo(850);
    assertThat(target.forwardHeaders()).containsExactly("x-camel-tenant");
  }

  @Test
  void preservesFractionalAndZeroTargetWeights() {
    String json = """
        {
          "strategy": {"mode": "loadbalance"},
          "targets": [
            {"name": "disabled", "provider": "openai", "api_key": "sk-disabled", "weight": 0},
            {"name": "partial", "provider": "openai", "api_key": "sk-partial", "weight": 0.7}
          ]
        }
        """;

    GatewayConfig config = parser.parse(json, Map.of());

    assertThat(config.targets().get(0).weight()).isZero();
    assertThat(config.targets().get(1).weight()).isEqualTo(0.7);
  }
}
