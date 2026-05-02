package com.modelgate.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.gateway.config.GatewayConfig;
import com.modelgate.gateway.config.GatewayConfigParser;
import com.modelgate.gateway.config.ProviderOptions;
import com.modelgate.gateway.config.RetrySettings;
import com.modelgate.gateway.config.Target;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

class ProviderRequestFactoryTest {
  private final GatewayConfigParser parser = new GatewayConfigParser(new ObjectMapper());
  private final ProviderRequestFactory factory = new ProviderRequestFactory(parser);

  @Test
  void buildsOpenAiChatCompletionRequestFromProviderHeadersAndCustomHost() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{\"model\":\"gpt-4o-mini\"}",
        Map.of(
            "content-type", "application/json",
            "authorization", "Bearer sk-test",
            "x-portkey-provider", "openai",
            "x-portkey-custom-host", "http://localhost:9999",
            "x-portkey-request-timeout", "2500"));

    assertThat(request.url()).isEqualTo("http://localhost:9999/v1/chat/completions");
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.body()).isEqualTo("{\"model\":\"gpt-4o-mini\"}");
    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-test");
    assertThat(request.headers()).containsEntry("content-type", "application/json");
    assertThat(request.timeout()).isEqualTo(Duration.ofMillis(2500));
  }

  @Test
  void buildsRequestFromModelgateHeadersBeforePortkeyCompatibilityHeaders() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{\"model\":\"gpt-4o-mini\"}",
        Map.of(
            "content-type", "application/json",
            "authorization", "Bearer sk-test",
            "x-portkey-provider", "openai",
            "x-portkey-custom-host", "http://localhost:9998",
            "x-portkey-request-timeout", "2500",
            "x-modelgate-provider", "openai",
            "x-modelgate-custom-host", "http://localhost:9999",
            "x-modelgate-request-timeout", "1500"));

    assertThat(request.url()).isEqualTo("http://localhost:9999/v1/chat/completions");
    assertThat(request.timeout()).isEqualTo(Duration.ofMillis(1500));
  }

  @Test
  void buildsGetModelsRequestWithoutContentTypeHeader() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/models",
        "GET",
        "",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"openai\",\"api_key\":\"sk-models\"}"));

    assertThat(request.url()).isEqualTo("https://api.openai.com/v1/models");
    assertThat(request.method()).isEqualTo("GET");
    assertThat(request.body()).isEmpty();
    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-models");
    assertThat(request.headers()).doesNotContainKey("content-type");
  }

  @Test
  void appliesRetrySettingsFromPortkeyConfigHeader() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{}",
        Map.of(
            "authorization", "Bearer sk-test",
            "x-portkey-provider", "openai",
            "x-portkey-custom-host", "http://localhost:9999",
            "x-portkey-config", "{\"retry\":{\"attempts\":2,\"on_status_codes\":[429],\"use_retry_after_header\":true}}"));

    assertThat(request.retryPolicy().attempts()).isEqualTo(2);
    assertThat(request.retryPolicy().onStatusCodes()).containsExactly(429);
    assertThat(request.retryPolicy().useRetryAfterHeader()).isTrue();
  }

  @Test
  void buildsRequestsFromProviderDefaultBaseUrls() {
    List<ProviderDefault> providerDefaults = List.of(
        new ProviderDefault("anthropic", "https://api.anthropic.com/v1"),
        new ProviderDefault("cohere", "https://api.cohere.ai"),
        new ProviderDefault("mistral-ai", "https://api.mistral.ai/v1"),
        new ProviderDefault("groq", "https://api.groq.com/openai/v1"),
        new ProviderDefault("together-ai", "https://api.together.xyz"),
        new ProviderDefault("deepseek", "https://api.deepseek.com"),
        new ProviderDefault("x-ai", "https://api.x.ai/v1"),
        new ProviderDefault("voyage", "https://api.voyageai.com/v1"),
        new ProviderDefault("jina", "https://api.jina.ai/v1"),
        new ProviderDefault("openrouter", "https://openrouter.ai/api"),
        new ProviderDefault("perplexity-ai", "https://api.perplexity.ai"),
        new ProviderDefault("stability-ai", "https://api.stability.ai"),
        new ProviderDefault("replicate", "https://api.replicate.com/v1"),
        new ProviderDefault("huggingface", "https://api-inference.huggingface.co"),
        new ProviderDefault("anyscale", "https://api.endpoints.anyscale.com/v1"),
        new ProviderDefault("cerebras", "https://api.cerebras.ai/v1"),
        new ProviderDefault("deepinfra", "https://api.deepinfra.com/v1/openai"),
        new ProviderDefault("deepbricks", "https://api.deepbricks.ai/v1"),
        new ProviderDefault("novita-ai", "https://api.novita.ai/v3/openai"),
        new ProviderDefault("lemonfox-ai", "https://api.lemonfox.ai/v1"));

    SoftAssertions.assertSoftly(softly -> {
      for (ProviderDefault providerDefault : providerDefaults) {
        try {
          ProviderRequest request = factory.forEndpoint(
              "/chat/completions",
              "{}",
              Map.of(
                  "content-type", "application/json",
                  "x-modelgate-config",
                  """
                      {"provider":"%s","api_key":"sk-provider"}
                      """.formatted(providerDefault.provider())));

          softly.assertThat(request.url())
              .describedAs(providerDefault.provider())
              .isEqualTo(providerDefault.baseUrl() + "/chat/completions");
          if ("anthropic".equals(providerDefault.provider())) {
            softly.assertThat(request.headers())
                .describedAs(providerDefault.provider())
                .containsEntry("x-api-key", "sk-provider")
                .containsEntry("anthropic-version", "2023-06-01");
          } else {
            softly.assertThat(request.headers())
                .describedAs(providerDefault.provider())
                .containsEntry("authorization", "Bearer sk-provider");
          }
        } catch (RuntimeException exception) {
          softly.fail(providerDefault.provider() + " should have a default base URL", exception);
        }
      }
    });
  }

  @Test
  void buildsDirectChatCompletionUrlsForInitialOpenAiCompatibleProviders() {
    List<ProviderDefault> providerDefaults = List.of(
        new ProviderDefault("openai", "https://api.openai.com/v1/chat/completions"),
        new ProviderDefault("gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"),
        new ProviderDefault("groq", "https://api.groq.com/openai/v1/chat/completions"),
        new ProviderDefault("nvidia-nim", "https://integrate.api.nvidia.com/v1/chat/completions"),
        new ProviderDefault("nvidia", "https://integrate.api.nvidia.com/v1/chat/completions"),
        new ProviderDefault("sambanova", "https://api.sambanova.ai/v1/chat/completions"),
        new ProviderDefault("cerebras", "https://api.cerebras.ai/v1/chat/completions"),
        new ProviderDefault("inception", "https://api.inceptionlabs.ai/v1/chat/completions"));

    SoftAssertions.assertSoftly(softly -> {
      for (ProviderDefault providerDefault : providerDefaults) {
        ProviderRequest request = factory.forEndpoint(
            "/v1/chat/completions",
            "{}",
            Map.of(
                "content-type", "application/json",
                "x-modelgate-config",
                """
                    {"provider":"%s","api_key":"sk-provider"}
                    """.formatted(providerDefault.provider())));

        softly.assertThat(request.url())
            .describedAs(providerDefault.provider())
            .isEqualTo(providerDefault.baseUrl());
        softly.assertThat(request.headers())
            .describedAs(providerDefault.provider())
            .containsEntry("authorization", "Bearer sk-provider");
      }
    });
  }

  @Test
  void mapsLambdaOpenAiCompatibleChatRequests() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "llama3.1-8b-instruct",
              "messages": [{"role": "user", "content": "Hello"}],
              "max_completion_tokens": 32,
              "stream": true
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"lambda\",\"api_key\":\"lambda-key\"}"));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo("https://api.lambdalabs.com/v1/chat/completions");
    assertThat(request.headers()).containsEntry("authorization", "Bearer lambda-key");
    assertThat(request.headers()).containsEntry("content-type", "application/json");
    assertThat(body.path("model").asText()).isEqualTo("llama3.1-8b-instruct");
    assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("Hello");
    assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(32);
    assertThat(body.path("stream").asBoolean()).isTrue();
  }

  @Test
  void maps302AiOpenAiCompatibleChatRequests() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "gpt-4o-mini",
              "messages": [{"role": "user", "content": "Hello"}],
              "temperature": 0.2,
              "stream": true
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"302ai\",\"api_key\":\"302ai-key\"}"));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo("https://api.302.ai/v1/chat/completions");
    assertThat(request.headers()).containsEntry("authorization", "Bearer 302ai-key");
    assertThat(request.headers()).containsEntry("content-type", "application/json");
    assertThat(body.path("model").asText()).isEqualTo("gpt-4o-mini");
    assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("Hello");
    assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
    assertThat(body.path("stream").asBoolean()).isTrue();
  }

  @Test
  void buildsAnthropicMessagesRequestWithAnthropicHeaders() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "claude-3-5-sonnet-latest",
              "messages": [
                {"role": "system", "content": "Be terse."},
                {"role": "user", "content": "Hello"}
              ],
              "max_tokens": 64
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "anthropic",
                  "api_key": "sk-anthropic",
                  "anthropic_version": "2023-06-01",
                  "anthropic_beta": "messages-2023-12-15"
                }
                """));

    assertThat(request.url()).isEqualTo("https://api.anthropic.com/v1/messages");
    assertThat(request.headers()).containsEntry("x-api-key", "sk-anthropic");
    assertThat(request.headers()).containsEntry("anthropic-version", "2023-06-01");
    assertThat(request.headers()).containsEntry("anthropic-beta", "messages-2023-12-15");
    assertThat(request.headers()).doesNotContainKey("authorization");
    assertThat(request.body()).contains("\"system\":[{\"type\":\"text\",\"text\":\"Be terse.\"}]");
    assertThat(request.body()).contains("\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]");
  }

  @Test
  void buildsAnthropicMessagesCountTokensRequestWithCustomHostAndMessagesBodyTransform() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/messages/count_tokens",
        """
            {
              "model": "claude-3-5-sonnet-latest",
              "system": "Be terse.",
              "messages": [
                {"role": "user", "content": "Hello"}
              ],
              "max_tokens": 64,
              "stop_sequences": ["END"],
              "top_k": 5,
              "thinking": {"type": "enabled", "budget_tokens": 256},
              "metadata": {"user_id": "user-a"}
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "anthropic",
                  "api_key": "sk-anthropic",
                  "custom_host": "https://anthropic.example",
                  "anthropic_version": "2023-06-01"
                }
                """));

    assertThat(request.url()).isEqualTo("https://anthropic.example/messages/count_tokens");
    assertThat(request.headers()).containsEntry("x-api-key", "sk-anthropic");
    assertThat(request.headers()).containsEntry("anthropic-version", "2023-06-01");
    assertThat(request.body()).contains("\"system\":\"Be terse.\"");
    assertThat(request.body()).contains("\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]");
    assertThat(request.body()).contains("\"stop_sequences\":[\"END\"]");
    assertThat(request.body()).contains("\"top_k\":5");
    assertThat(request.body()).contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":256}");
    assertThat(request.body()).contains("\"metadata\":{\"user_id\":\"user-a\"}");
  }

  @Test
  void buildsRequestsFromAdditionalProviderDefaultBaseUrls() {
    List<ProviderDefault> providerDefaults = List.of(
        new ProviderDefault("302ai", "https://api.302.ai"),
        new ProviderDefault("ai21", "https://api.ai21.com/studio/v1"),
        new ProviderDefault("aibadgr", "https://aibadgr.com/api/v1"),
        new ProviderDefault("bytez", "https://api.bytez.com"),
        new ProviderDefault("cometapi", "https://api.cometapi.com/v1"),
        new ProviderDefault("dashscope", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
        new ProviderDefault("featherless-ai", "https://api.featherless.ai/v1"),
        new ProviderDefault("google", "https://generativelanguage.googleapis.com"),
        new ProviderDefault("github", "https://models.inference.ai.azure.com"),
        new ProviderDefault("hyperbolic", "https://api.hyperbolic.xyz"),
        new ProviderDefault("inference-net", "https://api.inference.net/v1"),
        new ProviderDefault("iointelligence", "https://api.intelligence.io.solutions/api/v1"),
        new ProviderDefault("kluster-ai", "https://api.kluster.ai/v1"),
        new ProviderDefault("krutrim", "https://cloud.olakrutrim.com/v1"),
        new ProviderDefault("lambda", "https://api.lambdalabs.com/v1"),
        new ProviderDefault("lepton", "https://api.lepton.ai"),
        new ProviderDefault("lingyi", "https://api.lingyiwanwu.com"),
        new ProviderDefault("matterai", "https://api.matterai.so/v1"),
        new ProviderDefault("modal", "https://api.modal.com/v1"),
        new ProviderDefault("monsterapi", "https://llm.monsterapi.ai/v1"),
        new ProviderDefault("moonshot", "https://api.moonshot.cn"),
        new ProviderDefault("ncompass", "https://api.ncompass.tech/v1"),
        new ProviderDefault("nebius", "https://api.studio.nebius.ai/v1"),
        new ProviderDefault("nextbit", "https://api.nextbit256.com/v1"),
        new ProviderDefault("nomic", "https://api-atlas.nomic.ai/v1"),
        new ProviderDefault("nscale", "https://inference.api.nscale.com/v1"),
        new ProviderDefault("ovhcloud", "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1"),
        new ProviderDefault("palm", "https://generativelanguage.googleapis.com/v1beta3"),
        new ProviderDefault("predibase", "https://serving.app.predibase.com"),
        new ProviderDefault("recraft-ai", "https://external.api.recraft.ai/v1"),
        new ProviderDefault("reka-ai", "https://api.reka.ai"),
        new ProviderDefault("sambanova", "https://api.sambanova.ai/v1"),
        new ProviderDefault("segmind", "https://api.segmind.com/v1"),
        new ProviderDefault("siliconflow", "https://api.siliconflow.cn/v1"),
        new ProviderDefault("tripo3d", "https://api.tripo3d.ai/v2/openapi"),
        new ProviderDefault("upstage", "https://api.upstage.ai/v1/solar"),
        new ProviderDefault("z-ai", "https://api.z.ai/api/paas/v4"),
        new ProviderDefault("zhipu", "https://open.bigmodel.cn/api/paas/v4"));

    SoftAssertions.assertSoftly(softly -> {
      for (ProviderDefault providerDefault : providerDefaults) {
        try {
          ProviderRequest request = factory.forEndpoint(
              "/chat/completions",
              "{}",
              Map.of(
                  "content-type", "application/json",
                  "x-modelgate-config",
                  """
                      {"provider":"%s","api_key":"sk-provider"}
                      """.formatted(providerDefault.provider())));

          softly.assertThat(request.url())
              .describedAs(providerDefault.provider())
              .isEqualTo(providerDefault.baseUrl() + "/chat/completions");
          if ("google".equals(providerDefault.provider()) || "palm".equals(providerDefault.provider())) {
            softly.assertThat(request.headers())
                .describedAs(providerDefault.provider())
                .doesNotContainKey("authorization");
          } else if ("reka-ai".equals(providerDefault.provider()) || "segmind".equals(providerDefault.provider())) {
            softly.assertThat(request.headers())
                .describedAs(providerDefault.provider())
                .containsEntry("x-api-key", "sk-provider")
                .doesNotContainKey("authorization");
          } else if ("bytez".equals(providerDefault.provider())) {
            softly.assertThat(request.headers())
                .describedAs(providerDefault.provider())
                .containsEntry("authorization", "Key sk-provider")
                .containsKey("user-agent");
          } else {
            softly.assertThat(request.headers())
                .describedAs(providerDefault.provider())
                .containsEntry("authorization", "Bearer sk-provider");
          }
        } catch (RuntimeException exception) {
          softly.fail(providerDefault.provider() + " should have a default base URL", exception);
        }
      }
    });
  }

  @Test
  void usesCustomHostFromConfigForDirectProviderRequests() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            "{\"provider\":\"openai\",\"api_key\":\"sk-config\",\"custom_host\":\"http://localhost:9997\"}"));

    assertThat(request.url()).isEqualTo("http://localhost:9997/v1/chat/completions");
    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-config");
  }

  @Test
  void forwardsHeadersNamedByDirectConfigWithoutForwardingOtherControlHeaders() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-client-tenant", "acme",
            "x-modelgate-metadata", "{\"tier\":\"gold\"}",
            "x-modelgate-config",
            """
                {
                  "provider": "openai",
                  "api_key": "sk-config",
                  "custom_host": "http://localhost:9997",
                  "forward_headers": ["x-client-tenant", "x-modelgate-metadata"]
                }
                """));

    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-config");
    assertThat(request.headers()).containsEntry("x-client-tenant", "acme");
    assertThat(request.headers()).containsEntry("x-modelgate-metadata", "{\"tier\":\"gold\"}");
    assertThat(request.headers()).doesNotContainKey("x-modelgate-config");
  }

  @Test
  void addsOpenAiProviderOptionHeaders() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "openai",
                  "api_key": "sk-config",
                  "openai_organization": "org-a",
                  "openai_project": "project-a",
                  "openai_beta": "assistants=v2"
                }
                """));

    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-config");
    assertThat(request.headers()).containsEntry("OpenAI-Organization", "org-a");
    assertThat(request.headers()).containsEntry("OpenAI-Project", "project-a");
    assertThat(request.headers()).containsEntry("OpenAI-Beta", "assistants=v2");
  }

  @Test
  void addsDeepbricksProviderOptionHeaders() {
    ProviderRequest request = factory.forEndpoint(
        "/chat/completions",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "deepbricks",
                  "api_key": "sk-config",
                  "openai_organization": "org-a",
                  "openai_project": "project-a"
                }
                """));

    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-config");
    assertThat(request.headers()).containsEntry("Deepbricks-Organization", "org-a");
    assertThat(request.headers()).containsEntry("Deepbricks-Project", "project-a");
  }

  @Test
  void azureOpenAiApiKeyModeUsesApiKeyHeaderAndOpenAiBeta() {
    ProviderRequest request = factory.forEndpoint(
        "/deployments/gpt/chat/completions",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "azure-openai",
                  "api_key": "sk-config",
                  "custom_host": "https://resource.openai.azure.com/openai",
                  "openai_beta": "assistants=v2"
                }
                """));

    assertThat(request.headers()).containsEntry("api-key", "sk-config");
    assertThat(request.headers()).containsEntry("OpenAI-Beta", "assistants=v2");
    assertThat(request.headers()).doesNotContainKey("authorization");
  }

  @Test
  void azureOpenAiDirectChatUsesResourceDeploymentAndApiVersion() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{\"model\":\"gpt-4o-mini\"}",
        Map.of(
            "content-type", "application/json",
            "authorization", "Bearer sk-caller",
            "x-modelgate-config",
            """
                {
                  "provider": "azure-openai",
                  "api_key": "sk-config",
                  "resource_name": "modelgate-resource",
                  "deployment_id": "gpt-4o",
                  "api_version": "2024-02-01"
                }
                """));

    assertThat(request.url()).isEqualTo(
        "https://modelgate-resource.openai.azure.com/openai/deployments/gpt-4o/chat/completions?api-version=2024-02-01");
    assertThat(request.headers()).containsEntry("api-key", "sk-config");
    assertThat(request.headers()).doesNotContainKey("authorization");
  }

  @Test
  void azureOpenAiDirectEmbeddingsAndAudioRoutesUseDeploymentPrefix() {
    byte[] audioBytes = new byte[] {0x52, 0x49, 0x46, 0x46, 0x01, (byte) 0xff};
    Map<String, String> headers = Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        """
            {
              "provider": "azure-openai",
              "api_key": "sk-config",
              "resource_name": "modelgate-resource",
              "deployment_id": "embed-deploy",
              "api_version": "2024-02-01"
            }
            """);

    ProviderRequest embeddings = factory.forEndpoint(
        "/v1/embeddings",
        "{\"model\":\"text-embedding-3-small\",\"input\":\"hello\"}",
        headers);
    ProviderRequest audio = factory.forEndpointRawBody(
        "/v1/audio/transcriptions",
        "POST",
        audioBytes,
        Map.of(
            "content-type", "audio/wav",
            "x-modelgate-config",
            """
                {
                  "provider": "azure-openai",
                  "api_key": "sk-config",
                  "resource_name": "modelgate-resource",
                  "deployment_id": "whisper-deploy",
                  "api_version": "2024-02-01"
                }
                """),
        false);

    assertThat(embeddings.url()).isEqualTo(
        "https://modelgate-resource.openai.azure.com/openai/deployments/embed-deploy/embeddings?api-version=2024-02-01");
    assertThat(audio.url()).isEqualTo(
        "https://modelgate-resource.openai.azure.com/openai/deployments/whisper-deploy/audio/transcriptions?api-version=2024-02-01");
    assertThat(audio.headers()).containsEntry("content-type", "audio/wav");
    assertThat(audio.bodyBytes()).isEqualTo(audioBytes);
  }

  @Test
  void azureOpenAiApiVersionV1UsesV1PrefixWithoutApiVersionQuery() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{\"model\":\"gpt-4o-mini\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "azure-openai",
                  "api_key": "sk-config",
                  "resource_name": "modelgate-resource",
                  "deployment_id": "gpt-4o",
                  "api_version": "v1"
                }
                """));

    assertThat(request.url()).isEqualTo(
        "https://modelgate-resource.openai.azure.com/openai/v1/chat/completions");
  }

  @Test
  void azureOpenAiNonInferenceBatchAndFileRoutesUseRootOpenAiPaths() {
    Map<String, String> headers = Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        """
            {
              "provider": "azure-openai",
              "api_key": "sk-config",
              "custom_host": "https://resource.openai.azure.com/openai",
              "deployment_id": "gpt-4o",
              "api_version": "2024-02-01"
            }
            """);

    ProviderRequest batch = factory.forEndpoint("/v1/batches/batch-1", "GET", "", headers);
    ProviderRequest fileContent = factory.forEndpoint("/v1/files/file-out/content", "GET", "", headers);

    assertThat(batch.url()).isEqualTo(
        "https://resource.openai.azure.com/openai/batches/batch-1?api-version=2024-02-01");
    assertThat(fileContent.url()).isEqualTo(
        "https://resource.openai.azure.com/openai/files/file-out/content?api-version=2024-02-01");
  }

  @Test
  void azureOpenAiModelsRouteUsesRootOpenAiPath() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/models",
        "GET",
        "",
        Map.of(
            "x-modelgate-config",
            """
                {
                  "provider": "azure-openai",
                  "api_key": "sk-config",
                  "custom_host": "https://resource.openai.azure.com/openai",
                  "deployment_id": "gpt-4o",
                  "api_version": "2024-02-01"
                }
                """));

    assertThat(request.url()).isEqualTo(
        "https://resource.openai.azure.com/openai/models?api-version=2024-02-01");
  }

  @Test
  void azureOpenAiResponsesRoutesUseRootOpenAiPaths() {
    Map<String, String> headers = Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        """
            {
              "provider": "azure-openai",
              "api_key": "sk-config",
              "custom_host": "https://resource.openai.azure.com/openai",
              "deployment_id": "gpt-4o",
              "api_version": "2024-02-01"
            }
            """);

    ProviderRequest create = factory.forEndpoint("/v1/responses", "POST", "{}", headers);
    ProviderRequest retrieve = factory.forEndpoint("/v1/responses/resp-1", "GET", "", headers);
    ProviderRequest delete = factory.forEndpoint("/v1/responses/resp-1", "DELETE", "", headers);
    ProviderRequest inputItems = factory.forEndpoint("/v1/responses/resp-1/input_items?limit=10", "GET", "", headers);

    assertThat(create.url()).isEqualTo(
        "https://resource.openai.azure.com/openai/responses?api-version=2024-02-01");
    assertThat(retrieve.url()).isEqualTo(
        "https://resource.openai.azure.com/openai/responses/resp-1?api-version=2024-02-01");
    assertThat(delete.url()).isEqualTo(
        "https://resource.openai.azure.com/openai/responses/resp-1?api-version=2024-02-01");
    assertThat(inputItems.url()).isEqualTo(
        "https://resource.openai.azure.com/openai/responses/resp-1/input_items?limit=10&api-version=2024-02-01");
  }

  @Test
  void azureOpenAiFineTuningRoutesUseRootOpenAiPaths() {
    Map<String, String> headers = Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        """
            {
              "provider": "azure-openai",
              "api_key": "sk-config",
              "custom_host": "https://resource.openai.azure.com/openai",
              "deployment_id": "gpt-4o",
              "api_version": "2024-02-01"
            }
            """);

    ProviderRequest create = factory.forEndpoint("/v1/fine_tuning/jobs", "POST", "{}", headers);
    ProviderRequest list = factory.forEndpoint("/v1/fine_tuning/jobs?limit=25&after=cursor-a", "GET", "", headers);
    ProviderRequest retrieve = factory.forEndpoint("/v1/fine_tuning/jobs/ft-1", "GET", "", headers);
    ProviderRequest cancel = factory.forEndpoint("/v1/fine_tuning/jobs/ft-1/cancel", "POST", "{}", headers);

    assertThat(create.url()).isEqualTo(
        "https://resource.openai.azure.com/openai/fine_tuning/jobs?api-version=2024-02-01");
    assertThat(list.url()).isEqualTo(
        "https://resource.openai.azure.com/openai/fine_tuning/jobs?limit=25&after=cursor-a&api-version=2024-02-01");
    assertThat(retrieve.url()).isEqualTo(
        "https://resource.openai.azure.com/openai/fine_tuning/jobs/ft-1?api-version=2024-02-01");
    assertThat(cancel.url()).isEqualTo(
        "https://resource.openai.azure.com/openai/fine_tuning/jobs/ft-1/cancel?api-version=2024-02-01");
  }

  @Test
  void azureOpenAiApiKeyModeDoesNotForwardCallerAuthorizationHeader() {
    ProviderRequest request = factory.forEndpoint(
        "/deployments/gpt/chat/completions",
        "{}",
        Map.of(
            "content-type", "application/json",
            "authorization", "Bearer sk-caller",
            "x-modelgate-config",
            """
                {
                  "provider": "azure-openai",
                  "api_key": "sk-config",
                  "custom_host": "https://resource.openai.azure.com/openai"
                }
                """));

    assertThat(request.headers()).containsEntry("api-key", "sk-config");
    assertThat(request.headers()).doesNotContainKey("authorization");
  }

  @Test
  void azureOpenAiAdTokenModeUsesAuthorizationHeader() {
    ProviderRequest request = factory.forEndpoint(
        "/deployments/gpt/chat/completions",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "azure-openai",
                  "api_key": "sk-config",
                  "custom_host": "https://resource.openai.azure.com/openai",
                  "azure_ad_token": "Bearer aad-token"
                }
                """));

    assertThat(request.headers()).containsEntry("authorization", "Bearer aad-token");
    assertThat(request.headers()).doesNotContainKey("api-key");
  }

  @Test
  void azureAiInferenceAddsDeterministicProviderHeaders() {
    ProviderRequest request = factory.forEndpoint(
        "/chat/completions",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "azure-ai",
                  "api_key": "sk-config",
                  "custom_host": "https://models.inference.ai.azure.com",
                  "azure_deployment_name": "deployment-a"
                }
                """));

    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-config");
    assertThat(request.headers()).containsEntry("extra-parameters", "drop");
    assertThat(request.headers()).containsEntry("azureml-model-deployment", "deployment-a");
  }

  @Test
  void azureAiInferenceAnthropicFoundryUsesXApiKeyAndAnthropicVersion() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/messages",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "azure-ai",
                  "api_key": "sk-config",
                  "custom_host": "https://foundry.example/anthropic",
                  "azure_foundry_url": "https://foundry.example/anthropic",
                  "anthropic_version": "2023-06-01"
                }
                """));

    assertThat(request.headers()).containsEntry("x-api-key", "sk-config");
    assertThat(request.headers()).containsEntry("anthropic-version", "2023-06-01");
    assertThat(request.headers()).containsEntry("extra-parameters", "drop");
    assertThat(request.headers()).doesNotContainKey("authorization");
  }

  @Test
  void azureAiInferenceUsesFoundryUrlApiVersionAndOpenAiCompatibleBodyRules() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "gpt-4o",
              "messages": [
                {"role": "developer", "content": "Policy"},
                {"role": "user", "content": "Hello"}
              ],
              "temperature": 4,
              "top_p": 2,
              "presence_penalty": -4,
              "frequency_penalty": 4,
              "max_completion_tokens": 64
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "azure-ai",
                  "api_key": "sk-config",
                  "azure_foundry_url": "https://foundry.example/models",
                  "azure_api_version": "2024-05-01-preview"
                }
                """));

    JsonNode body = readJson(request.body());

    assertThat(request.url())
        .isEqualTo("https://foundry.example/models/chat/completions?api-version=2024-05-01-preview");
    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-config");
    assertThat(request.headers()).containsEntry("extra-parameters", "drop");
    assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
    assertThat(body.path("temperature").asDouble()).isEqualTo(2.0d);
    assertThat(body.path("top_p").asDouble()).isEqualTo(1.0d);
    assertThat(body.path("presence_penalty").asDouble()).isEqualTo(-2.0d);
    assertThat(body.path("frequency_penalty").asDouble()).isEqualTo(2.0d);
    assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(64);
  }

  @Test
  void azureAiInferenceRoutesNonInferenceEndpointsThroughFoundryOpenAiOrigin() {
    ProviderRequest listFiles = factory.forEndpoint(
        "/v1/files?purpose=batch",
        "GET",
        "",
        Map.of(
            "x-modelgate-config",
            """
                {
                  "provider": "azure-ai",
                  "api_key": "sk-config",
                  "azure_foundry_url": "https://foundry.example/deployments/model-a",
                  "azure_api_version": "2024-05-01-preview"
                }
                """));
    ProviderRequest cancelBatch = factory.forEndpoint(
        "/v1/batches/batch-1/cancel",
        "POST",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "azure-ai",
                  "api_key": "sk-config",
                  "azure_foundry_url": "https://foundry.example/deployments/model-a",
                  "azure_api_version": "2024-05-01-preview"
                }
                """));

    assertThat(listFiles.url())
        .isEqualTo("https://foundry.example/openai/files?purpose=batch&api-version=2024-05-01-preview");
    assertThat(cancelBatch.url())
        .isEqualTo("https://foundry.example/openai/batches/batch-1/cancel?api-version=2024-05-01-preview");
  }

  @Test
  void azureAiInferenceAnthropicFoundryChatCompletionsUsesMessagesEndpointAndNativeBody() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "claude-3-5-sonnet",
              "messages": [
                {"role": "system", "content": "Be concise."},
                {"role": "user", "content": "Hello"}
              ],
              "max_tokens": 32
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "azure-ai",
                  "api_key": "sk-config",
                  "azure_foundry_url": "https://foundry.example/anthropic",
                  "anthropic_version": "2023-06-01"
                }
                """));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo("https://foundry.example/anthropic/v1/messages");
    assertThat(request.headers()).containsEntry("x-api-key", "sk-config");
    assertThat(request.headers()).containsEntry("anthropic-version", "2023-06-01");
    assertThat(body.path("system").get(0).path("text").asText()).isEqualTo("Be concise.");
    assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
    assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("Hello");
  }

  @Test
  void azureAiInferenceEntraAuthExchangesTokenAndUsesBearerAuthorization() throws Exception {
    AtomicReference<String> tokenRequestBody = new AtomicReference<>("");
    HttpServer tokenServer = tokenServer(
        tokenRequestBody,
        "{\"access_token\":\"entra-access-token\",\"expires_in\":3600}");
    String previousTemplate = System.getProperty("modelgate.azure.entra.tokenUrlTemplate");
    System.setProperty(
        "modelgate.azure.entra.tokenUrlTemplate",
        "http://127.0.0.1:" + tokenServer.getAddress().getPort() + "/%s/token");
    try {
      ProviderRequest request = factory.forEndpoint(
          "/v1/chat/completions",
          "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
          Map.of(
              "content-type", "application/json",
              "x-modelgate-config",
              """
                  {
                    "provider": "azure-ai",
                    "api_key": "fallback-key",
                    "azure_foundry_url": "https://foundry.example/models",
                    "azure_auth_mode": "entra",
                    "azure_entra_tenant_id": "tenant-a",
                    "azure_entra_client_id": "client-a",
                    "azure_entra_client_secret": "secret-a",
                    "azure_entra_scope": "https://scope.example/.default"
                  }
                  """));

      assertThat(request.headers()).containsEntry("authorization", "Bearer entra-access-token");
      assertThat(tokenRequestBody.get()).contains("grant_type=client_credentials");
      assertThat(tokenRequestBody.get()).contains("client_id=client-a");
      assertThat(tokenRequestBody.get()).contains("client_secret=secret-a");
      assertThat(tokenRequestBody.get()).contains("scope=https%3A%2F%2Fscope.example%2F.default");
    } finally {
      restoreSystemProperty("modelgate.azure.entra.tokenUrlTemplate", previousTemplate);
      tokenServer.stop(0);
    }
  }

  @Test
  void configuredApiKeyWinsOverCallerAuthorizationHeader() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{}",
        Map.of(
            "content-type", "application/json",
            "authorization", "Bearer sk-caller",
            "x-modelgate-config",
            """
                {
                  "provider": "openai",
                  "api_key": "sk-config"
                }
                """));

    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-config");
  }

  @Test
  void targetApiKeyWinsOverCallerAuthorizationHeader() {
    ProviderRequest request = factory.forTarget(
        Target.builder()
            .name("target")
            .provider("openai")
            .apiKey("sk-target")
            .customHost("http://localhost:9998")
            .build(),
        "/v1/chat/completions",
        "POST",
        "{}",
        Map.of(
            "content-type", "application/json",
            "authorization", "Bearer sk-caller"),
        false,
        GatewayConfig.builder().provider("openai").build());

    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-target");
  }

  @Test
  void configuredApiKeyWinsEvenWhenAuthorizationIsExplicitlyForwarded() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{}",
        Map.of(
            "content-type", "application/json",
            "authorization", "Bearer sk-caller",
            "x-modelgate-config",
            """
                {
                  "provider": "openai",
                  "api_key": "sk-config",
                  "forward_headers": ["authorization"]
                }
                """));

    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-config");
  }

  @Test
  void targetApiKeyWinsEvenWhenAuthorizationIsExplicitlyForwarded() {
    ProviderRequest request = factory.forTarget(
        Target.builder()
            .name("target")
            .provider("openai")
            .apiKey("sk-target")
            .customHost("http://localhost:9998")
            .forwardHeaders(List.of("authorization"))
            .build(),
        "/v1/chat/completions",
        "POST",
        "{}",
        Map.of(
            "content-type", "application/json",
            "authorization", "Bearer sk-caller"),
        false,
        GatewayConfig.builder().provider("openai").build());

    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-target");
  }

  @Test
  void targetInheritsTopLevelCustomHostWhenTargetOmitsCustomHost() {
    GatewayConfig inheritedConfig = GatewayConfig.builder()
        .provider("openai")
        .customHost("http://localhost:9997")
        .build();

    ProviderRequest request = factory.forTarget(
        Target.builder()
            .name("target")
            .provider("openai")
            .apiKey("sk-target")
            .build(),
        "/v1/chat/completions",
        "POST",
        "{}",
        Map.of("content-type", "application/json"),
        false,
        inheritedConfig);

    assertThat(request.url()).isEqualTo("http://localhost:9997/v1/chat/completions");
  }

  @Test
  void targetInheritsTopLevelApiKeyAndProviderOptionHeadersWhenOmitted() {
    GatewayConfig inheritedConfig = GatewayConfig.builder()
        .provider("openai")
        .apiKey("sk-inherited")
        .providerOptions(new ProviderOptions(Map.of(
            "apiKey", "sk-inherited",
            "openaiProject", "project-inherited")))
        .build();

    ProviderRequest request = factory.forTarget(
        Target.builder()
            .name("target")
            .provider("openai")
            .customHost("http://localhost:9998")
            .providerOptions(new ProviderOptions(Map.of("provider", "openai")))
            .build(),
        "/v1/chat/completions",
        "POST",
        "{}",
        Map.of("content-type", "application/json"),
        false,
        inheritedConfig);

    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-inherited");
    assertThat(request.headers()).containsEntry("OpenAI-Project", "project-inherited");
  }

  @Test
  void providerOptionHeadersCanComeFromRequestHeaders() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{}",
        Map.of(
            "content-type", "application/json",
            "authorization", "Bearer sk-test",
            "x-modelgate-provider", "openai",
            "x-modelgate-openai-organization", "org-header",
            "x-modelgate-openai-project", "project-header",
            "openai-beta", "assistants=v2"));

    assertThat(request.headers()).containsEntry("OpenAI-Organization", "org-header");
    assertThat(request.headers()).containsEntry("OpenAI-Project", "project-header");
    assertThat(request.headers()).containsEntry("OpenAI-Beta", "assistants=v2");
  }

  @Test
  void stripsGatewayV1PrefixWhenBaseUrlEndsInOpenAiCompatibilitySegment() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "novita-ai",
                  "api_key": "sk-config"
                }
                """));

    assertThat(request.url()).isEqualTo("https://api.novita.ai/v3/openai/chat/completions");
  }

  @Test
  void buildsFireworksInferenceUrlsFromDefaultBaseUrl() {
    ProviderRequest chatRequest = factory.forEndpoint(
        "/v1/chat/completions",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"fireworks-ai\",\"api_key\":\"sk-fireworks\"}"));
    ProviderRequest embeddingsRequest = factory.forEndpoint(
        "/v1/embeddings",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"fireworks-ai\",\"api_key\":\"sk-fireworks\"}"));

    assertThat(chatRequest.url()).isEqualTo("https://api.fireworks.ai/inference/v1/chat/completions");
    assertThat(embeddingsRequest.url()).isEqualTo("https://api.fireworks.ai/inference/v1/embeddings");
    assertThat(chatRequest.headers()).containsEntry("authorization", "Bearer sk-fireworks");
  }

  @Test
  void mapsWorkersAiRequestsToAccountModelEndpointAndTransformsChatBody() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "@cf/meta/llama-3.1-8b-instruct",
              "messages": [
                {"role": "developer", "content": "Policy"},
                {"role": "user", "content": "Hello"}
              ],
              "stream": true,
              "max_completion_tokens": 32
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "workers-ai",
                  "api_key": "cf-token",
                  "workers_ai_account_id": "account-123"
                }
                """));

    JsonNode body = new ObjectMapper().readTree(request.body());

    assertThat(request.url())
        .isEqualTo("https://api.cloudflare.com/client/v4/accounts/account-123/ai/run/@cf/meta/llama-3.1-8b-instruct");
    assertThat(request.headers()).containsEntry("authorization", "Bearer cf-token");
    assertThat(body.has("model")).isFalse();
    assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
    assertThat(body.path("max_tokens").asInt()).isEqualTo(32);
  }

  @Test
  void mapsWorkersAiEmbeddingsToAccountModelEndpointAndTextArrayBody() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/embeddings",
        """
            {
              "model": "@cf/baai/bge-base-en-v1.5",
              "input": "hello"
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "workers-ai",
                  "api_key": "cf-token",
                  "workers_ai_account_id": "account-123"
                }
                """));

    JsonNode body = new ObjectMapper().readTree(request.body());

    assertThat(request.url())
        .isEqualTo("https://api.cloudflare.com/client/v4/accounts/account-123/ai/run/@cf/baai/bge-base-en-v1.5");
    assertThat(body.has("model")).isFalse();
    assertThat(body.path("text").get(0).asText()).isEqualTo("hello");
  }

  @Test
  void mapsCohereChatAndEmbeddingsEndpointsAndEmbeddingBody() throws Exception {
    ProviderRequest chatRequest = factory.forEndpoint(
        "/v1/chat/completions",
        "{\"model\":\"command-r\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"cohere\",\"api_key\":\"co-key\"}"));
    ProviderRequest embedRequest = factory.forEndpoint(
        "/v1/embeddings",
        """
            {
              "model": "embed-v4.0",
              "input": ["alpha", {"text": "beta"}, {"image": {"base64": "aW1n"}}],
              "input_type": "search_document",
              "encoding_format": "float"
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"cohere\",\"api_key\":\"co-key\"}"));
    ProviderRequest completionRequest = factory.forEndpoint(
        "/v1/completions",
        """
            {
              "model": "command",
              "prompt": "Hello",
              "top_p": 0.5,
              "top_k": 20,
              "n": 2,
              "stop": ["END"]
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"cohere\",\"api_key\":\"co-key\"}"));

    JsonNode body = new ObjectMapper().readTree(embedRequest.body());
    JsonNode completionBody = new ObjectMapper().readTree(completionRequest.body());

    assertThat(chatRequest.url()).isEqualTo("https://api.cohere.ai/v2/chat");
    assertThat(embedRequest.url()).isEqualTo("https://api.cohere.ai/v2/embed");
    assertThat(completionRequest.url()).isEqualTo("https://api.cohere.ai/v1/generate");
    assertThat(body.path("texts").get(0).asText()).isEqualTo("alpha");
    assertThat(body.path("texts").get(1).asText()).isEqualTo("beta");
    assertThat(body.path("images").get(0).asText()).isEqualTo("aW1n");
    assertThat(body.path("embedding_types").get(0).asText()).isEqualTo("float");
    assertThat(body.has("input")).isFalse();
    assertThat(body.has("encoding_format")).isFalse();
    assertThat(completionBody.path("p").asDouble()).isEqualTo(0.5);
    assertThat(completionBody.path("k").asInt()).isEqualTo(20);
    assertThat(completionBody.path("num_generations").asInt()).isEqualTo(2);
    assertThat(completionBody.path("end_sequences").get(0).asText()).isEqualTo("END");
    assertThat(completionBody.has("top_p")).isFalse();
    assertThat(completionBody.has("top_k")).isFalse();
    assertThat(completionBody.has("n")).isFalse();
    assertThat(completionBody.has("stop")).isFalse();
  }

  @Test
  void mapsCohereFilesAndBatchesEndpointsAndBatchBody() throws Exception {
    Map<String, String> headers = Map.of(
        "content-type", "application/json",
        "x-modelgate-config", "{\"provider\":\"cohere\",\"api_key\":\"co-key\"}");
    ProviderRequest uploadFile = factory.forEndpoint("/v1/files", "POST", "", headers);
    ProviderRequest listFiles = factory.forEndpoint("/v1/files", "GET", "", headers);
    ProviderRequest retrieveFile = factory.forEndpoint("/v1/files/file-123", "GET", "", headers);
    ProviderRequest deleteFile = factory.forEndpoint("/v1/files/file-123", "DELETE", "", headers);
    ProviderRequest createBatch = factory.forEndpoint(
        "/v1/batches",
        "POST",
        """
            {
              "model": "embed-v4.0",
              "input_file_id": "dataset-123",
              "input_type": "search_document",
              "name": "nightly",
              "embedding_types": ["float"],
              "truncate": "END"
            }
            """,
        headers);
    ProviderRequest listBatches = factory.forEndpoint("/v1/batches", "GET", "", headers);
    ProviderRequest retrieveBatch = factory.forEndpoint("/v1/batches/batch-123", "GET", "", headers);
    ProviderRequest cancelBatch = factory.forEndpoint("/v1/batches/batch-123/cancel", "POST", "", headers);

    JsonNode batchBody = new ObjectMapper().readTree(createBatch.body());

    assertThat(uploadFile.url()).startsWith("https://api.cohere.ai/v1/datasets?name=portkey-");
    assertThat(uploadFile.url()).contains("type=embed-input");
    assertThat(uploadFile.url()).contains("keep_fields=custom_id,id");
    assertThat(listFiles.url()).isEqualTo("https://api.cohere.ai/v1/datasets");
    assertThat(retrieveFile.url()).isEqualTo("https://api.cohere.ai/v1/datasets/file-123");
    assertThat(deleteFile.url()).isEqualTo("https://api.cohere.ai/v1/datasets/file-123");
    assertThat(createBatch.url()).isEqualTo("https://api.cohere.ai/v1/embed-jobs");
    assertThat(listBatches.url()).isEqualTo("https://api.cohere.ai/v1/embed-jobs");
    assertThat(retrieveBatch.url()).isEqualTo("https://api.cohere.ai/v1/embed-jobs/batch-123");
    assertThat(cancelBatch.url()).isEqualTo("https://api.cohere.ai/v1/embed-jobs/batch-123/cancel");
    assertThat(createBatch.headers()).containsEntry("authorization", "Bearer co-key");
    assertThat(batchBody.path("dataset_id").asText()).isEqualTo("dataset-123");
    assertThat(batchBody.has("input_file_id")).isFalse();
  }

  @Test
  void transformsMistralChatBodyForProviderAliases() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "mistralai.mistral-large-latest",
              "messages": [
                {"role": "developer", "content": "Policy"},
                {"role": "user", "content": "Hello"}
              ],
              "tool_choice": "required",
              "seed": 7,
              "safe_mode": true,
              "max_completion_tokens": 64
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"mistral-ai\",\"api_key\":\"mistral-key\"}"));

    JsonNode body = new ObjectMapper().readTree(request.body());

    assertThat(request.url()).isEqualTo("https://api.mistral.ai/v1/chat/completions");
    assertThat(body.path("model").asText()).isEqualTo("mistral-large-latest");
    assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
    assertThat(body.path("tool_choice").asText()).isEqualTo("any");
    assertThat(body.path("random_seed").asInt()).isEqualTo(7);
    assertThat(body.path("safe_prompt").asBoolean()).isTrue();
    assertThat(body.path("max_tokens").asInt()).isEqualTo(64);
    assertThat(body.has("seed")).isFalse();
    assertThat(body.has("safe_mode")).isFalse();
  }

  @Test
  void wrapsSingleEmbeddingInputForVoyageJinaAndMistral() throws Exception {
    List<ProviderDefault> providers = List.of(
        new ProviderDefault("voyage", "https://api.voyageai.com/v1/embeddings"),
        new ProviderDefault("jina", "https://api.jina.ai/v1/embeddings"),
        new ProviderDefault("mistral-ai", "https://api.mistral.ai/v1/embeddings"));

    SoftAssertions.assertSoftly(softly -> {
      for (ProviderDefault provider : providers) {
        ProviderRequest request = factory.forEndpoint(
            "/v1/embeddings",
            "{\"model\":\"embed-model\",\"input\":\"hello\"}",
            Map.of(
                "content-type", "application/json",
                "x-modelgate-config",
                """
                    {"provider":"%s","api_key":"provider-key"}
                    """.formatted(provider.provider())));
        JsonNode body = readJson(request.body());

        softly.assertThat(request.url()).describedAs(provider.provider()).isEqualTo(provider.baseUrl());
        softly.assertThat(body.path("input").isArray()).describedAs(provider.provider()).isTrue();
        softly.assertThat(body.path("input").path(0).asText()).describedAs(provider.provider()).isEqualTo("hello");
      }
    });
  }

  @Test
  void mapsFireworksImageGenerationToModelEndpoint() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/images/generations",
        "{\"model\":\"accounts/fireworks/models/stable-diffusion-xl-1024-v1-0\",\"prompt\":\"mountain\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"fireworks-ai\",\"api_key\":\"sk-fireworks\"}"));

    assertThat(request.url())
        .isEqualTo("https://api.fireworks.ai/inference/v1/image_generation/accounts/fireworks/models/stable-diffusion-xl-1024-v1-0");
  }

  @Test
  void mapsBedrockConverseChatToRuntimeEndpointAndBody() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "us.anthropic.claude-3-sonnet-20240229-v1:0",
              "messages": [
                {"role": "system", "content": "Use short answers."},
                {"role": "user", "content": "Hello"}
              ],
              "max_completion_tokens": 64,
              "stop": "END",
              "temperature": 0.2,
              "top_p": 0.9
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "bedrock",
                  "api_key": "bedrock-key",
                  "aws_region": "us-west-2",
                  "aws_auth_type": "apiKey"
                }
                """));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo(
        "https://bedrock-runtime.us-west-2.amazonaws.com/model/anthropic.claude-3-sonnet-20240229-v1%3A0/converse");
    assertThat(request.headers()).containsEntry("authorization", "Bearer bedrock-key");
    assertThat(body.has("model")).isFalse();
    assertThat(body.path("system").get(0).path("text").asText()).isEqualTo("Use short answers.");
    assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
    assertThat(body.path("messages").get(0).path("content").get(0).path("text").asText()).isEqualTo("Hello");
    assertThat(body.path("inferenceConfig").path("maxTokens").asInt()).isEqualTo(64);
    assertThat(body.path("inferenceConfig").path("stopSequences").get(0).asText()).isEqualTo("END");
    assertThat(body.path("inferenceConfig").path("temperature").asDouble()).isEqualTo(0.2);
    assertThat(body.path("inferenceConfig").path("topP").asDouble()).isEqualTo(0.9);
  }

  @Test
  void mapsBedrockInvokeModelsToInvokeEndpoint() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/completions",
        "{\"model\":\"cohere.command-text-v14\",\"prompt\":\"Hello\",\"max_tokens\":10}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            "{\"provider\":\"bedrock\",\"api_key\":\"bedrock-key\",\"aws_region\":\"us-east-1\",\"aws_auth_type\":\"apiKey\"}"));

    assertThat(request.url()).isEqualTo(
        "https://bedrock-runtime.us-east-1.amazonaws.com/model/cohere.command-text-v14/invoke");
  }

  @Test
  void mapsBedrockAnthropicMessagesCountTokensToCountTokensEndpointAndInvokeModelPayload() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/messages/count_tokens",
        """
            {
              "model": "anthropic.claude-3-sonnet-20240229-v1:0",
              "system": "Be terse.",
              "messages": [
                {"role": "user", "content": "Hello"}
              ],
              "max_tokens": 64,
              "anthropic_version": "2023-06-01",
              "stop_sequences": ["END"],
              "top_k": 5,
              "metadata": {"user_id": "user-a"},
              "extra": "drop-me"
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "bedrock",
                  "api_key": "bedrock-key",
                  "aws_region": "us-west-2",
                  "aws_auth_type": "apiKey"
                }
                """));

    JsonNode body = readJson(request.body());
    JsonNode invokeBody = readJson(new String(
        Base64.getDecoder().decode(body.path("input").path("invokeModel").path("body").asText()),
        StandardCharsets.UTF_8));

    assertThat(request.url()).isEqualTo(
        "https://bedrock-runtime.us-west-2.amazonaws.com/model/anthropic.claude-3-sonnet-20240229-v1%3A0/count-tokens");
    assertThat(request.headers()).containsEntry("authorization", "Bearer bedrock-key");
    assertThat(invokeBody.has("model")).isFalse();
    assertThat(invokeBody.has("extra")).isFalse();
    assertThat(invokeBody.path("anthropic_version").asText()).isEqualTo("2023-06-01");
    assertThat(invokeBody.path("system").asText()).isEqualTo("Be terse.");
    assertThat(invokeBody.path("messages").get(0).path("content").asText()).isEqualTo("Hello");
    assertThat(invokeBody.path("max_tokens").asInt()).isEqualTo(64);
    assertThat(invokeBody.path("stop_sequences").get(0).asText()).isEqualTo("END");
    assertThat(invokeBody.path("top_k").asInt()).isEqualTo(5);
    assertThat(invokeBody.path("metadata").path("user_id").asText()).isEqualTo("user-a");
  }

  @Test
  void mapsBedrockDefaultMessagesCountTokensToCountTokensEndpointAndConversePayload() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/messages/count_tokens",
        """
            {
              "model": "meta.llama3-8b-instruct-v1%3A0",
              "system": [
                {"type": "text", "text": "Use short answers.", "cache_control": {"type": "ephemeral"}}
              ],
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "text", "text": "Hello", "cache_control": {"type": "ephemeral"}},
                    {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "aW1n"}},
                    {"type": "document", "source": {"type": "url", "media_type": "application/pdf", "url": "s3://bucket/doc.pdf"}},
                    {"type": "thinking", "thinking": "work", "signature": "sig"},
                    {"type": "redacted_thinking", "data": "redacted"},
                    {"type": "tool_use", "id": "tool-1", "name": "lookup", "input": {"city": "NYC"}},
                    {
                      "type": "tool_result",
                      "tool_use_id": "tool-1",
                      "is_error": true,
                      "content": [
                        {"type": "text", "text": "failed"}
                      ]
                    }
                  ]
                }
              ],
              "metadata": {"trace_id": "trace-1"},
              "max_completion_tokens": 64,
              "temperature": 0.2,
              "top_p": 0.9,
              "stop_sequences": ["END"]
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "bedrock",
                  "api_key": "bedrock-key",
                  "aws_region": "us-west-2",
                  "aws_auth_type": "apiKey"
                }
                """));

    JsonNode body = readJson(request.body());
    JsonNode converse = body.path("input").path("converse");

    assertThat(request.url()).isEqualTo(
        "https://bedrock-runtime.us-west-2.amazonaws.com/model/meta.llama3-8b-instruct-v1%3A0/count-tokens");
    assertThat(request.headers()).containsEntry("authorization", "Bearer bedrock-key");
    assertThat(converse.has("model")).isFalse();
    assertThat(converse.path("system").get(0).path("text").asText()).isEqualTo("Use short answers.");
    assertThat(converse.path("system").get(1).path("cachePoint").path("type").asText()).isEqualTo("default");
    assertThat(converse.path("messages").get(0).path("role").asText()).isEqualTo("user");
    assertThat(converse.path("messages").get(0).path("content").get(0).path("text").asText()).isEqualTo("Hello");
    assertThat(converse.path("messages").get(0).path("content").get(1).path("cachePoint").path("type").asText())
        .isEqualTo("default");
    assertThat(converse.path("messages").get(0).path("content").get(2).path("image").path("format").asText())
        .isEqualTo("png");
    assertThat(converse.path("messages").get(0).path("content").get(2).path("image").path("source").path("bytes").asText())
        .isEqualTo("aW1n");
    assertThat(converse.path("messages").get(0).path("content").get(3).path("document").path("format").asText())
        .isEqualTo("pdf");
    assertThat(converse.path("messages").get(0).path("content").get(3).path("document").path("source")
        .path("s3Location").path("uri").asText()).isEqualTo("s3://bucket/doc.pdf");
    assertThat(converse.path("messages").get(0).path("content").get(4).path("reasoningContent")
        .path("reasoningText").path("text").asText()).isEqualTo("work");
    assertThat(converse.path("messages").get(0).path("content").get(5).path("reasoningContent")
        .path("redactedContent").asText()).isEqualTo("redacted");
    assertThat(converse.path("messages").get(0).path("content").get(6).path("toolUse").path("toolUseId").asText())
        .isEqualTo("tool-1");
    assertThat(converse.path("messages").get(0).path("content").get(7).path("toolResult").path("status").asText())
        .isEqualTo("error");
    assertThat(converse.path("requestMetadata").path("trace_id").asText()).isEqualTo("trace-1");
    assertThat(converse.path("inferenceConfig").path("maxTokens").asInt()).isEqualTo(64);
    assertThat(converse.path("inferenceConfig").path("temperature").asDouble()).isEqualTo(0.2);
    assertThat(converse.path("inferenceConfig").path("topP").asDouble()).isEqualTo(0.9);
    assertThat(converse.path("inferenceConfig").path("stopSequences").get(0).asText()).isEqualTo("END");
  }

  @Test
  void mapsVertexGeminiChatToGenerateContentAndBody() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "google.gemini-1.5-pro",
              "messages": [
                {"role": "system", "content": "Be factual."},
                {"role": "user", "content": [{"type": "text", "text": "Hi"}]},
                {"role": "assistant", "content": "Hello"}
              ],
              "max_completion_tokens": 128,
              "temperature": 0.3,
              "top_p": 0.8,
              "stop": ["END"]
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "vertex-ai",
                  "api_key": "vertex-token",
                  "vertex_project_id": "project-a",
                  "vertex_region": "us-central1"
                }
                """));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo(
        "https://us-central1-aiplatform.googleapis.com/v1/projects/project-a/locations/us-central1/publishers/google/models/gemini-1.5-pro:generateContent");
    assertThat(request.headers()).containsEntry("authorization", "Bearer vertex-token");
    assertThat(body.has("model")).isFalse();
    assertThat(body.path("systemInstruction").path("role").asText()).isEqualTo("system");
    assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText()).isEqualTo("Be factual.");
    assertThat(body.path("contents").get(0).path("role").asText()).isEqualTo("user");
    assertThat(body.path("contents").get(0).path("parts").get(0).path("text").asText()).isEqualTo("Hi");
    assertThat(body.path("contents").get(1).path("role").asText()).isEqualTo("model");
    assertThat(body.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(128);
    assertThat(body.path("generationConfig").path("stopSequences").get(0).asText()).isEqualTo("END");
  }

  @Test
  void mapsVertexAnthropicMessagesCountTokensToCountTokensRawPredictAndNativeBody() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/messages/count_tokens",
        """
            {
              "model": "anthropic.claude-3-5-sonnet-v2@20241022",
              "system": "Be concise.",
              "messages": [{"role": "user", "content": "Count this"}],
              "max_tokens": 32,
              "stop_sequences": ["END"],
              "top_k": 5,
              "thinking": {"type": "enabled", "budget_tokens": 128},
              "metadata": {"user_id": "user-a"},
              "extra": "drop-me"
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "vertex-ai",
                  "api_key": "vertex-token",
                  "vertex_project_id": "project-a",
                  "vertex_region": "us-east5"
                }
                """));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo(
        "https://us-east5-aiplatform.googleapis.com/v1/projects/project-a/locations/us-east5/publishers/anthropic/models/count-tokens:rawPredict");
    assertThat(request.headers()).containsEntry("authorization", "Bearer vertex-token");
    assertThat(body.path("model").asText()).isEqualTo("claude-3-5-sonnet-v2@20241022");
    assertThat(body.path("system").asText()).isEqualTo("Be concise.");
    assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("Count this");
    assertThat(body.path("stop_sequences").get(0).asText()).isEqualTo("END");
    assertThat(body.path("top_k").asInt()).isEqualTo(5);
    assertThat(body.path("thinking").path("budget_tokens").asInt()).isEqualTo(128);
    assertThat(body.path("metadata").path("user_id").asText()).isEqualTo("user-a");
    assertThat(body.has("extra")).isFalse();
  }

  @Test
  void addsVertexAnthropicBetaHeaderFromProviderOptionsOrRequestBody() {
    ProviderRequest configBetaRequest = factory.forEndpoint(
        "/v1/messages/count_tokens",
        "{\"model\":\"anthropic.claude-3-haiku@20240307\",\"messages\":[],\"max_tokens\":1}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "vertex-ai",
                  "api_key": "vertex-token",
                  "vertex_project_id": "project-a",
                  "vertex_region": "us-east5",
                  "anthropic_beta": "config-beta"
                }
                """));
    ProviderRequest bodyBetaRequest = factory.forEndpoint(
        "/v1/messages/count_tokens",
        """
            {
              "model": "anthropic.claude-3-haiku@20240307",
              "messages": [],
              "max_tokens": 1,
              "anthropic_beta": "body-beta"
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "vertex-ai",
                  "api_key": "vertex-token",
                  "vertex_project_id": "project-a",
                  "vertex_region": "us-east5"
                }
                """));
    ProviderRequest configWinsRequest = factory.forEndpoint(
        "/v1/messages/count_tokens",
        """
            {
              "model": "anthropic.claude-3-haiku@20240307",
              "messages": [],
              "max_tokens": 1,
              "anthropic_beta": "body-beta"
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "vertex-ai",
                  "api_key": "vertex-token",
                  "vertex_project_id": "project-a",
                  "vertex_region": "us-east5",
                  "anthropic_beta": "config-beta"
                }
                """));
    ProviderRequest blankConfigSuppressesBodyRequest = factory.forEndpoint(
        "/v1/messages/count_tokens",
        """
            {
              "model": "anthropic.claude-3-haiku@20240307",
              "messages": [],
              "max_tokens": 1,
              "anthropic_beta": "body-beta"
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "vertex-ai",
                  "api_key": "vertex-token",
                  "vertex_project_id": "project-a",
                  "vertex_region": "us-east5",
                  "anthropic_beta": ""
                }
                """));

    assertThat(configBetaRequest.headers()).containsEntry("anthropic-beta", "config-beta");
    assertThat(bodyBetaRequest.headers()).containsEntry("anthropic-beta", "body-beta");
    assertThat(configWinsRequest.headers()).containsEntry("anthropic-beta", "config-beta");
    assertThat(blankConfigSuppressesBodyRequest.headers()).doesNotContainKey("anthropic-beta");
  }

  @Test
  void mapsVertexNonInferenceBatchAndFineTuneEndpoints() {
    Map<String, String> headers = Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        """
            {
              "provider": "vertex-ai",
              "api_key": "vertex-token",
              "vertex_project_id": "project-a",
              "vertex_region": "us-east5"
            }
            """);
    ProviderRequest listBatches = factory.forEndpoint("/v1/batches?limit=50&after=token-a", "GET", "", headers);
    ProviderRequest retrieveBatch = factory.forEndpoint("/v1/batches/batch-1", "GET", "", headers);
    ProviderRequest cancelBatch = factory.forEndpoint("/v1/batches/batch-1/cancel", "POST", "{}", headers);
    ProviderRequest createFineTune = factory.forEndpoint("/v1/fine_tuning/jobs", "POST", "{}", headers);
    ProviderRequest retrieveFineTune = factory.forEndpoint("/v1/fine_tuning/jobs/tune-1", "GET", "", headers);
    ProviderRequest cancelFineTune = factory.forEndpoint("/v1/fine_tuning/jobs/tune-1/cancel", "POST", "{}", headers);

    assertThat(listBatches.url()).isEqualTo(
        "https://us-east5-aiplatform.googleapis.com/v1/projects/project-a/locations/us-east5/batchPredictionJobs?pageSize=50&pageToken=token-a");
    assertThat(retrieveBatch.url()).isEqualTo(
        "https://us-east5-aiplatform.googleapis.com/v1/projects/project-a/locations/us-east5/batchPredictionJobs/batch-1");
    assertThat(cancelBatch.url()).isEqualTo(
        "https://us-east5-aiplatform.googleapis.com/v1/projects/project-a/locations/us-east5/batchPredictionJobs/batch-1:cancel");
    assertThat(createFineTune.url()).isEqualTo(
        "https://us-east5-aiplatform.googleapis.com/v1/projects/project-a/locations/us-east5/tuningJobs");
    assertThat(retrieveFineTune.url()).isEqualTo(
        "https://us-east5-aiplatform.googleapis.com/v1/projects/project-a/locations/us-east5/tuningJobs/tune-1");
    assertThat(cancelFineTune.url()).isEqualTo(
        "https://us-east5-aiplatform.googleapis.com/v1/projects/project-a/locations/us-east5/tuningJobs/tune-1:cancel");
  }

  @Test
  void mapsVertexListFineTunesAndUsesServiceAccountProjectForInferenceRoutes() {
    Map<String, String> headers = Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        """
            {
              "provider": "vertex-ai",
              "api_key": "vertex-token",
              "vertex_project_id": "header-project",
              "vertex_region": "global",
              "vertex_service_account_json": {
                "project_id": "service-project"
              }
            }
            """);

    ProviderRequest listFineTunes = factory.forEndpoint(
        "/v1/fine_tuning/jobs?limit=25&after=cursor-a",
        "GET",
        "",
        headers);
    ProviderRequest chat = factory.forEndpoint(
        "/v1/chat/completions",
        "{\"model\":\"google.gemini-1.5-pro\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
        headers);

    assertThat(listFineTunes.url()).isEqualTo(
        "https://aiplatform.googleapis.com/v1/projects/service-project/locations/global/tuningJobs?pageSize=25&pageToken=cursor-a");
    assertThat(chat.url()).isEqualTo(
        "https://aiplatform.googleapis.com/v1/projects/service-project/locations/global/publishers/google/models/gemini-1.5-pro:generateContent");
  }

  @Test
  void transformsVertexCreateBatchAndFineTuneBodies() throws Exception {
    Map<String, String> headers = Map.of(
        "content-type", "application/json",
        "x-modelgate-config",
        """
            {
              "provider": "vertex-ai",
              "api_key": "vertex-token",
              "vertex_project_id": "project-a",
              "vertex_region": "us-central1"
            }
            """);
    ProviderRequest batch = factory.forEndpoint(
        "/v1/batches",
        """
            {
              "model": "google.gemini-1.5-pro",
              "input_file_id": "gs%3A%2F%2Fbucket-a%2Finput%2Frequests.jsonl",
              "output_data_config": "gs%3A%2F%2Fbucket-a%2Foutput%2F",
              "job_name": "batch-a",
              "provider_options": {
                "labels": {"team": "evals"}
              }
            }
            """,
        headers);
    ProviderRequest fineTune = factory.forEndpoint(
        "/v1/fine_tuning/jobs",
        """
            {
              "model": "publishers/google/models/gemini-1.5-pro",
              "training_file": "gs%3A%2F%2Fbucket-a%2Ftrain.jsonl",
              "validation_file": "gs%3A%2F%2Fbucket-a%2Fvalidation.jsonl",
              "suffix": "ft-a",
              "hyperparameters": {
                "n_epochs": 3,
                "learning_rate_multiplier": 0.2,
                "batch_size": 16
              }
            }
            """,
        headers);

    JsonNode batchBody = readJson(batch.body());
    JsonNode fineTuneBody = readJson(fineTune.body());

    assertThat(batchBody.path("model").asText()).isEqualTo("publishers/google/models/gemini-1.5-pro");
    assertThat(batchBody.at("/inputConfig/instancesFormat").asText()).isEqualTo("jsonl");
    assertThat(batchBody.at("/inputConfig/gcsSource/uris").asText()).isEqualTo("gs://bucket-a/input/requests.jsonl");
    assertThat(batchBody.at("/outputConfig/predictionsFormat").asText()).isEqualTo("jsonl");
    assertThat(batchBody.at("/outputConfig/gcsDestination/outputUriPrefix").asText()).isEqualTo("gs://bucket-a/output/");
    assertThat(batchBody.path("displayName").asText()).isEqualTo("batch-a");
    assertThat(batchBody.at("/instanceConfig/excludedFields").get(0).asText()).isEqualTo("requestId");
    assertThat(batchBody.at("/labels/team").asText()).isEqualTo("evals");

    assertThat(fineTuneBody.path("baseModel").asText()).isEqualTo("publishers/google/models/gemini-1.5-pro");
    assertThat(fineTuneBody.path("tunedModelDisplayName").asText()).isEqualTo("ft-a");
    assertThat(fineTuneBody.at("/supervisedTuningSpec/training_dataset_uri").asText()).isEqualTo("gs://bucket-a/train.jsonl");
    assertThat(fineTuneBody.at("/supervisedTuningSpec/validation_dataset_uri").asText()).isEqualTo("gs://bucket-a/validation.jsonl");
    assertThat(fineTuneBody.at("/supervisedTuningSpec/hyperParameters/epochCount").asInt()).isEqualTo(3);
    assertThat(fineTuneBody.at("/supervisedTuningSpec/hyperParameters/learningRateMultiplier").asDouble()).isEqualTo(0.2d);
    assertThat(fineTuneBody.at("/supervisedTuningSpec/hyperParameters/adapterSize").asInt()).isEqualTo(16);
  }

  @Test
  void mapsVertexFileEndpointsThroughGoogleStorage() {
    Map<String, String> headers = Map.of(
        "x-modelgate-config",
        """
            {
              "provider": "vertex-ai",
              "api_key": "vertex-token",
              "vertex_project_id": "project-a",
              "vertex_region": "us-east5"
            }
            """);
    ProviderRequest content = factory.forEndpoint(
        "/v1/files/gs%3A%2F%2Fbucket-a%2Ffolder%2Ffile.jsonl/content",
        "GET",
        "",
        headers);
    ProviderRequest metadata = factory.forEndpoint(
        "/v1/files/gs%3A%2F%2Fbucket-a%2Ffolder%2Ffile.jsonl",
        "GET",
        "",
        headers);

    assertThat(content.url()).isEqualTo("https://storage.googleapis.com/bucket-a/folder/file.jsonl");
    assertThat(content.method()).isEqualTo("GET");
    assertThat(content.headers()).containsEntry("authorization", "Bearer vertex-token");
    assertThat(metadata.url()).isEqualTo("https://storage.googleapis.com/bucket-a/folder/file.jsonl");
    assertThat(metadata.method()).isEqualTo("HEAD");
    assertThat(metadata.headers()).containsEntry("authorization", "Bearer vertex-token");
  }

  @Test
  void vertexServiceAccountJsonExchangesJwtForAccessToken() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    String privateKeyPem = pemPrivateKey(keyPair);
    AtomicReference<String> tokenRequestBody = new AtomicReference<>("");
    HttpServer tokenServer = tokenServer(
        tokenRequestBody,
        "{\"access_token\":\"vertex-access-token\",\"expires_in\":3600}");
    String previousTokenUrl = System.getProperty("modelgate.vertex.tokenUrl");
    System.setProperty("modelgate.vertex.tokenUrl", "http://127.0.0.1:" + tokenServer.getAddress().getPort() + "/token");
    try {
      ProviderRequest request = factory.forEndpoint(
          "/v1/chat/completions",
          "{\"model\":\"google.gemini-1.5-pro\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
          Map.of(
              "content-type", "application/json",
              "x-modelgate-config",
              """
                  {
                    "provider": "vertex-ai",
                    "api_key": "fallback-token",
                    "vertex_project_id": "header-project",
                    "vertex_region": "us-central1",
                    "vertex_service_account_json": {
                      "project_id": "service-project",
                      "private_key_id": "key-a",
                      "client_email": "svc@example.iam.gserviceaccount.com",
                      "private_key": "%s"
                    }
                  }
                  """.formatted(privateKeyPem.replace("\n", "\\n"))));

      assertThat(request.headers()).containsEntry("authorization", "Bearer vertex-access-token");
      assertThat(tokenRequestBody.get()).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");
      assertThat(tokenRequestBody.get()).contains("assertion=");
      assertThat(request.url()).contains("/projects/service-project/");
    } finally {
      restoreSystemProperty("modelgate.vertex.tokenUrl", previousTokenUrl);
      tokenServer.stop(0);
    }
  }

  @Test
  void mapsSagemakerRuntimeEndpointAndProviderHeaders() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/endpoints/my-endpoint/invocations",
        "{\"inputs\":\"hello\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "sagemaker",
                  "aws_region": "eu-west-1",
                  "amzn_sagemaker_custom_attributes": "trace=abc",
                  "amzn_sagemaker_target_model": "model-a",
                  "amzn_sagemaker_target_variant": "variant-a",
                  "amzn_sagemaker_session_id": "session-a"
                }
                """));

    assertThat(request.url())
        .isEqualTo("https://runtime.sagemaker.eu-west-1.amazonaws.com/endpoints/my-endpoint/invocations");
    assertThat(request.headers()).containsEntry("x-amzn-sagemaker-custom-attributes", "trace=abc");
    assertThat(request.headers()).containsEntry("x-amzn-sagemaker-target-model", "model-a");
    assertThat(request.headers()).containsEntry("x-amzn-sagemaker-target-variant", "variant-a");
    assertThat(request.headers()).containsEntry("x-amzn-sagemaker-session-id", "session-a");
  }

  @Test
  void sagemakerSignsInvocationWithStaticAwsCredentials() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/endpoints/my-endpoint/invocations",
        "{\"inputs\":\"hello\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "sagemaker",
                  "api_key": "ignored-when-aws-creds-present",
                  "aws_region": "eu-west-1",
                  "aws_access_key_id": "AKIA_TEST",
                  "aws_secret_access_key": "secret",
                  "aws_session_token": "session-token",
                  "amzn_sagemaker_target_model": "model-a"
                }
                """));

    assertThat(request.url())
        .isEqualTo("https://runtime.sagemaker.eu-west-1.amazonaws.com/endpoints/my-endpoint/invocations");
    assertThat(request.headers()).containsEntry("host", "runtime.sagemaker.eu-west-1.amazonaws.com");
    assertThat(request.headers()).containsEntry("x-amz-security-token", "session-token");
    assertThat(request.headers()).containsEntry("x-amzn-sagemaker-target-model", "model-a");
    assertThat(request.headers()).containsKey("x-amz-date");
    assertThat(request.headers()).containsKey("x-amz-content-sha256");
    assertThat(request.headers().get("authorization"))
        .startsWith("AWS4-HMAC-SHA256 Credential=AKIA_TEST/")
        .contains("/eu-west-1/sagemaker/aws4_request")
        .contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date;x-amz-security-token")
        .contains("Signature=");
    assertThat(request.headers().get("authorization")).doesNotContain("Bearer ignored-when-aws-creds-present");
    assertThat(request.headers().get("authorization")).doesNotContain("x-amzn-sagemaker-target-model");
  }

  @Test
  void mapsStabilityImageGenerationEndpointsHeadersAndV1Body() throws Exception {
    ProviderRequest v1Request = factory.forEndpoint(
        "/v1/images/generations",
        """
            {
              "model": "stable-diffusion-xl-v1",
              "prompt": "mountain",
              "n": 2,
              "size": "768x512",
              "style": "photographic"
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"stability-ai\",\"api_key\":\"stability-key\"}"));
    ProviderRequest v2Request = factory.forEndpoint(
        "/v1/images/generations",
        "{\"model\":\"core\",\"prompt\":\"mountain\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"stability-ai\",\"api_key\":\"stability-key\"}"));

    JsonNode v1Body = readJson(v1Request.body());

    assertThat(v1Request.url())
        .isEqualTo("https://api.stability.ai/v1/generation/stable-diffusion-xl-v1/text-to-image");
    assertThat(v1Request.headers()).containsEntry("authorization", "Bearer stability-key");
    assertThat(v1Body.path("text_prompts").get(0).path("text").asText()).isEqualTo("mountain");
    assertThat(v1Body.path("text_prompts").get(0).path("weight").asInt()).isEqualTo(1);
    assertThat(v1Body.path("samples").asInt()).isEqualTo(2);
    assertThat(v1Body.path("width").asInt()).isEqualTo(768);
    assertThat(v1Body.path("height").asInt()).isEqualTo(512);
    assertThat(v1Body.path("style_preset").asText()).isEqualTo("photographic");
    assertThat(v2Request.url()).isEqualTo("https://api.stability.ai/v2beta/stable-image/generate/core");
    assertThat(v2Request.headers().get("content-type")).startsWith("multipart/form-data; boundary=");
    assertThat(v2Request.headers()).containsEntry("accept", "application/json");
    assertThat(v2Request.body()).contains("Content-Disposition: form-data; name=\"prompt\"");
    assertThat(v2Request.body()).contains("mountain");
    assertThat(v2Request.body()).doesNotContain("\"model\":\"core\"");
  }

  @Test
  void mapsStabilityMultipartImageGenerationByModelFieldWithoutChangingBodyBytes() {
    byte[] multipartBody = """
        --mg
        Content-Disposition: form-data; name="model"

        core
        --mg
        Content-Disposition: form-data; name="prompt"

        mountain
        --mg--
        """.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);

    ProviderRequest request = factory.forEndpointRawBody(
        "/v1/images/generations",
        "POST",
        multipartBody,
        Map.of(
            "content-type", "multipart/form-data; boundary=mg",
            "x-modelgate-config", "{\"provider\":\"stability-ai\",\"api_key\":\"stability-key\"}"),
        false);

    assertThat(request.url()).isEqualTo("https://api.stability.ai/v2beta/stable-image/generate/core");
    assertThat(request.headers()).containsEntry("authorization", "Bearer stability-key");
    assertThat(request.headers()).containsEntry("content-type", "multipart/form-data; boundary=mg");
    assertThat(request.headers()).containsEntry("accept", "application/json");
    assertThat(request.bodyBytes()).isEqualTo(multipartBody);
  }

  @Test
  void mapsStabilityTargetMultipartImageGenerationByModelFieldWithoutChangingBodyBytes() {
    byte[] multipartBody = """
        --mg
        Content-Disposition: form-data; name="model"

        core
        --mg
        Content-Disposition: form-data; name="prompt"

        mountain
        --mg--
        """.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    Target target = Target.builder()
        .provider("stability-ai")
        .apiKey("stability-key")
        .build();

    ProviderRequest request = factory.forTargetRawBody(
        target,
        "/v1/images/generations",
        "POST",
        multipartBody,
        Map.of("content-type", "multipart/form-data; boundary=mg"),
        false,
        null);

    assertThat(request.url()).isEqualTo("https://api.stability.ai/v2beta/stable-image/generate/core");
    assertThat(request.headers()).containsEntry("authorization", "Bearer stability-key");
    assertThat(request.headers()).containsEntry("content-type", "multipart/form-data; boundary=mg");
    assertThat(request.headers()).containsEntry("accept", "application/json");
    assertThat(request.bodyBytes()).isEqualTo(multipartBody);
  }

  @Test
  void mapsHuggingFaceInferenceEndpointsAndDeveloperMessages() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "mistralai/Mixtral-8x7B-Instruct-v0.1",
              "messages": [
                {"role": "developer", "content": "Policy"},
                {"role": "user", "content": "Hello"}
              ],
              "max_completion_tokens": 25
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"huggingface\",\"api_key\":\"hf-key\"}"));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo(
        "https://api-inference.huggingface.co/models/mistralai/Mixtral-8x7B-Instruct-v0.1/v1/chat/completions");
    assertThat(request.headers()).containsEntry("authorization", "Bearer hf-key");
    assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
    assertThat(body.path("max_tokens").asInt()).isEqualTo(25);
    assertThat(body.has("max_completion_tokens")).isFalse();
  }

  @Test
  void mapsOpenRouterPerplexityAndDeepSeekEdgeCases() throws Exception {
    ProviderRequest openRouter = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "openrouter/auto",
              "messages": [{"role": "developer", "content": "Policy"}],
              "reasoning": {"max_tokens": 128},
              "reasoning_effort": "high",
              "stream_options": {"include_usage": true}
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"openrouter\",\"api_key\":\"or-key\"}"));
    ProviderRequest perplexity = factory.forEndpoint(
        "/v1/chat/completions",
        "{\"model\":\"sonar\",\"messages\":[{\"role\":\"developer\",\"content\":\"Policy\"}],\"frequency_penalty\":1.1,\"max_completion_tokens\":50}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"perplexity-ai\",\"api_key\":\"pplx-key\"}"));
    ProviderRequest deepSeek = factory.forEndpoint(
        "/v1/chat/completions",
        "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"developer\",\"content\":\"Policy\"}],\"max_completion_tokens\":50}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"deepseek\",\"api_key\":\"deep-key\"}"));

    JsonNode openRouterBody = readJson(openRouter.body());
    JsonNode perplexityBody = readJson(perplexity.body());
    JsonNode deepSeekBody = readJson(deepSeek.body());

    assertThat(openRouter.url()).isEqualTo("https://openrouter.ai/api/v1/chat/completions");
    assertThat(openRouter.headers()).containsEntry("HTTP-Referer", "https://portkey.ai/");
    assertThat(openRouter.headers()).containsKey("X-Title");
    assertThat(openRouterBody.path("messages").get(0).path("role").asText()).isEqualTo("system");
    assertThat(openRouterBody.path("reasoning").path("max_tokens").asInt()).isEqualTo(128);
    assertThat(openRouterBody.path("reasoning").path("effort").asText()).isEqualTo("high");
    assertThat(openRouterBody.path("usage").path("include").asBoolean()).isTrue();
    assertThat(openRouterBody.has("stream_options")).isFalse();
    assertThat(openRouterBody.has("reasoning_effort")).isFalse();
    assertThat(perplexity.url()).isEqualTo("https://api.perplexity.ai/chat/completions");
    assertThat(perplexityBody.path("messages").get(0).path("role").asText()).isEqualTo("system");
    assertThat(perplexityBody.path("repetition_penalty").asDouble()).isEqualTo(1.1);
    assertThat(perplexityBody.path("max_tokens").asInt()).isEqualTo(50);
    assertThat(deepSeek.url()).isEqualTo("https://api.deepseek.com/v1/chat/completions");
    assertThat(deepSeekBody.path("messages").get(0).path("role").asText()).isEqualTo("system");
    assertThat(deepSeekBody.path("max_tokens").asInt()).isEqualTo(50);
  }

  @Test
  void mapsVertexThinkingAndImageConfigGenerationOptions() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "google.gemini-2.5-flash-image-preview",
              "messages": [{"role": "user", "content": "draw"}],
              "thinking": {"type": "enabled", "budget_tokens": 256},
              "image_config": {"aspect_ratio": "16:9", "image_size": "2K"}
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "vertex-ai",
                  "api_key": "vertex-token",
                  "vertex_project_id": "project-a",
                  "vertex_region": "us-central1"
                }
                """));

    JsonNode body = readJson(request.body());

    assertThat(body.path("generationConfig").path("thinking_config").path("include_thoughts").asBoolean()).isTrue();
    assertThat(body.path("generationConfig").path("thinking_config").path("thinking_budget").asInt()).isEqualTo(256);
    assertThat(body.path("generationConfig").path("imageConfig").path("aspectRatio").asText()).isEqualTo("16:9");
    assertThat(body.path("generationConfig").path("imageConfig").path("imageSize").asText()).isEqualTo("2K");
  }

  @Test
  void mapsNativeGoogleGeminiChatAndEmbeddingRequests() throws Exception {
    ProviderRequest chat = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "gemini-1.5-pro",
              "messages": [
                {"role": "developer", "content": "Policy"},
                {"role": "user", "content": "Hello"}
              ],
              "temperature": 0.2,
              "max_completion_tokens": 64,
              "response_format": {"type": "json_object"}
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"google\",\"api_key\":\"google-key\"}"));
    ProviderRequest embedding = factory.forEndpoint(
        "/v1/embeddings",
        "{\"model\":\"text-embedding-004\",\"input\":[\"alpha\",\"beta\"]}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"google\",\"api_key\":\"google-key\"}"));

    JsonNode chatBody = readJson(chat.body());
    JsonNode embeddingBody = readJson(embedding.body());

    assertThat(chat.url())
        .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent?key=google-key");
    assertThat(chat.headers()).doesNotContainKey("authorization");
    assertThat(chatBody.path("systemInstruction").path("role").asText()).isEqualTo("system");
    assertThat(chatBody.path("contents").get(0).path("role").asText()).isEqualTo("user");
    assertThat(chatBody.path("contents").get(0).path("parts").get(0).path("text").asText()).isEqualTo("Hello");
    assertThat(chatBody.path("generationConfig").path("temperature").asDouble()).isEqualTo(0.2);
    assertThat(chatBody.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(64);
    assertThat(chatBody.path("generationConfig").path("responseMimeType").asText()).isEqualTo("application/json");
    assertThat(embedding.url())
        .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent?key=google-key");
    assertThat(embedding.headers()).doesNotContainKey("authorization");
    assertThat(embeddingBody.path("content").path("parts").get(0).path("text").asText()).isEqualTo("alpha");
    assertThat(embeddingBody.path("content").path("parts").get(1).path("text").asText()).isEqualTo("beta");
    assertThat(embeddingBody.has("model")).isFalse();
    assertThat(embeddingBody.has("input")).isFalse();
  }

  @Test
  void transformsBedrockTitanEmbeddingBody() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/embeddings",
        """
            {
              "model": "amazon.titan-embed-text-v2:0",
              "input": "hello",
              "dimensions": 512,
              "encoding_format": "float",
              "normalize": true
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            "{\"provider\":\"bedrock\",\"api_key\":\"bedrock-key\",\"aws_region\":\"us-east-1\",\"aws_auth_type\":\"apiKey\"}"));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo(
        "https://bedrock-runtime.us-east-1.amazonaws.com/model/amazon.titan-embed-text-v2%3A0/invoke");
    assertThat(body.path("inputText").asText()).isEqualTo("hello");
    assertThat(body.path("dimensions").asInt()).isEqualTo(512);
    assertThat(body.path("embeddingTypes").get(0).asText()).isEqualTo("float");
    assertThat(body.path("normalize").asBoolean()).isTrue();
    assertThat(body.has("model")).isFalse();
    assertThat(body.has("input")).isFalse();
  }

  @Test
  void transformsBedrockAnthropicCompletionBody() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/completions",
        """
            {
              "model": "anthropic.claude-v2",
              "prompt": "Hello",
              "max_tokens": 50,
              "stop": "END",
              "user": "user-a"
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            "{\"provider\":\"bedrock\",\"api_key\":\"bedrock-key\",\"aws_region\":\"us-east-1\",\"aws_auth_type\":\"apiKey\"}"));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo("https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-v2/invoke");
    assertThat(body.path("prompt").asText()).isEqualTo("\n\nHuman: Hello\n\nAssistant:");
    assertThat(body.path("max_tokens_to_sample").asInt()).isEqualTo(50);
    assertThat(body.path("stop_sequences").get(0).asText()).isEqualTo("END");
    assertThat(body.path("metadata").path("user_id").asText()).isEqualTo("user-a");
    assertThat(body.has("model")).isFalse();
    assertThat(body.has("max_tokens")).isFalse();
  }

  @Test
  void transformsVertexEmbeddingsAndImageGenerationBodies() throws Exception {
    ProviderRequest embeddings = factory.forEndpoint(
        "/v1/embeddings",
        """
            {
              "model": "google.text-embedding-004",
              "input": ["alpha", "beta"],
              "task_type": "RETRIEVAL_DOCUMENT",
              "dimensions": 256
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "vertex-ai",
                  "api_key": "vertex-token",
                  "vertex_project_id": "project-a",
                  "vertex_region": "us-central1"
                }
                """));
    ProviderRequest image = factory.forEndpoint(
        "/v1/images/generations",
        """
            {
              "model": "google.imagegeneration@006",
              "prompt": ["mountain", "river"],
              "n": 2,
              "quality": "hd",
              "style": "photographic",
              "aspectRatio": "16:9"
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "vertex-ai",
                  "api_key": "vertex-token",
                  "vertex_project_id": "project-a",
                  "vertex_region": "us-central1"
                }
                """));

    JsonNode embeddingsBody = readJson(embeddings.body());
    JsonNode imageBody = readJson(image.body());

    assertThat(embeddings.url()).isEqualTo(
        "https://us-central1-aiplatform.googleapis.com/v1/projects/project-a/locations/us-central1/publishers/google/models/text-embedding-004:predict");
    assertThat(embeddingsBody.path("instances").get(0).path("content").asText()).isEqualTo("alpha");
    assertThat(embeddingsBody.path("instances").get(0).path("task_type").asText()).isEqualTo("RETRIEVAL_DOCUMENT");
    assertThat(embeddingsBody.path("parameters").path("outputDimensionality").asInt()).isEqualTo(256);
    assertThat(embeddingsBody.has("model")).isFalse();
    assertThat(image.url()).isEqualTo(
        "https://us-central1-aiplatform.googleapis.com/v1/projects/project-a/locations/us-central1/publishers/google/models/imagegeneration@006:predict");
    assertThat(imageBody.path("instances").get(0).path("prompt").asText()).isEqualTo("mountain");
    assertThat(imageBody.path("instances").get(1).path("prompt").asText()).isEqualTo("river");
    assertThat(imageBody.path("parameters").path("sampleCount").asInt()).isEqualTo(2);
    assertThat(imageBody.path("parameters").path("outputOptions").path("compressionQuality").asInt()).isEqualTo(100);
    assertThat(imageBody.path("parameters").path("sampleImageStyle").asText()).isEqualTo("photographic");
    assertThat(imageBody.path("parameters").path("aspectRatio").asText()).isEqualTo("16:9");
  }

  @Test
  void transformsWorkersAiImageGenerationBody() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/images/generations",
        """
            {
              "model": "@cf/stabilityai/stable-diffusion-xl-base-1.0",
              "prompt": "mountain",
              "negative_prompt": "blur",
              "steps": 8,
              "size": "1024x768",
              "seed": 42
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "workers-ai",
                  "api_key": "cf-token",
                  "workers_ai_account_id": "account-123"
                }
                """));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo(
        "https://api.cloudflare.com/client/v4/accounts/account-123/ai/run/@cf/stabilityai/stable-diffusion-xl-base-1.0");
    assertThat(body.path("prompt").asText()).isEqualTo("mountain");
    assertThat(body.path("negative_prompt").asText()).isEqualTo("blur");
    assertThat(body.path("num_steps").asInt()).isEqualTo(8);
    assertThat(body.path("steps").asInt()).isEqualTo(8);
    assertThat(body.path("width").asInt()).isEqualTo(1024);
    assertThat(body.path("height").asInt()).isEqualTo(768);
    assertThat(body.path("seed").asInt()).isEqualTo(42);
    assertThat(body.has("model")).isFalse();
    assertThat(body.has("size")).isFalse();
  }

  @Test
  void transformsBedrockConverseToolsAndToolChoice() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "anthropic.claude-3-sonnet-20240229-v1:0",
              "messages": [{"role": "user", "content": "weather"}],
              "tools": [
                {
                  "type": "function",
                  "function": {
                    "name": "get_weather",
                    "description": "Get weather",
                    "parameters": {"type": "object", "properties": {"city": {"type": "string"}}}
                  }
                }
              ],
              "tool_choice": {"type": "function", "function": {"name": "get_weather"}}
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            "{\"provider\":\"bedrock\",\"api_key\":\"bedrock-key\",\"aws_region\":\"us-east-1\",\"aws_auth_type\":\"apiKey\"}"));

    JsonNode body = readJson(request.body());

    assertThat(body.path("toolConfig").path("tools").get(0).path("toolSpec").path("name").asText())
        .isEqualTo("get_weather");
    assertThat(body.path("toolConfig").path("tools").get(0).path("toolSpec").path("description").asText())
        .isEqualTo("Get weather");
    assertThat(body.path("toolConfig").path("tools").get(0).path("toolSpec").path("inputSchema").path("json")
        .path("properties").path("city").path("type").asText()).isEqualTo("string");
    assertThat(body.path("toolConfig").path("toolChoice").path("tool").path("name").asText()).isEqualTo("get_weather");
  }

  @Test
  void transformsVertexToolsAndStructuredGenerationConfig() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "google.gemini-1.5-pro",
              "messages": [{"role": "user", "content": "weather"}],
              "tools": [
                {
                  "type": "function",
                  "function": {
                    "name": "get_weather",
                    "description": "Get weather",
                    "parameters": {
                      "type": "object",
                      "additionalProperties": false,
                      "properties": {"city": {"type": "string"}}
                    },
                    "strict": true
                  }
                }
              ],
              "tool_choice": {"type": "function", "function": {"name": "get_weather"}},
              "response_format": {"type": "json_object"},
              "logprobs": true,
              "top_logprobs": 3,
              "modalities": ["text", "image"],
              "reasoning_effort": "high"
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "vertex-ai",
                  "api_key": "vertex-token",
                  "vertex_project_id": "project-a",
                  "vertex_region": "us-central1"
                }
                """));

    JsonNode body = readJson(request.body());

    assertThat(body.path("tools").get(0).path("functionDeclarations").get(0).path("name").asText())
        .isEqualTo("get_weather");
    assertThat(body.path("tools").get(0).path("functionDeclarations").get(0).path("parameters")
        .has("additionalProperties")).isFalse();
    assertThat(body.path("tools").get(0).path("functionDeclarations").get(0).has("strict")).isFalse();
    assertThat(body.path("tool_config").path("function_calling_config").path("mode").asText()).isEqualTo("ANY");
    assertThat(body.path("tool_config").path("function_calling_config").path("allowed_function_names").get(0).asText())
        .isEqualTo("get_weather");
    assertThat(body.path("generationConfig").path("responseMimeType").asText()).isEqualTo("application/json");
    assertThat(body.path("generationConfig").path("responseLogprobs").asBoolean()).isTrue();
    assertThat(body.path("generationConfig").path("logprobs").asInt()).isEqualTo(3);
    assertThat(body.path("generationConfig").path("responseModalities").get(0).asText()).isEqualTo("TEXT");
    assertThat(body.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText()).isEqualTo("high");
  }

  @Test
  void transformsCohereV2ChatToolsAndStructuredOutput() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "command-r",
              "messages": [{"role": "developer", "content": "Policy"}],
              "stop": "END",
              "tool_choice": {"type": "function", "function": {"name": "lookup"}},
              "response_format": {"type": "json_schema", "json_schema": {"strict": true}}
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"cohere\",\"api_key\":\"co-key\"}"));

    JsonNode body = readJson(request.body());

    assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
    assertThat(body.path("stop_sequences").get(0).asText()).isEqualTo("END");
    assertThat(body.path("tool_choice").asText()).isEqualTo("REQUIRED");
    assertThat(body.path("strict_tools").asBoolean()).isTrue();
  }

  @Test
  void transformsPalmChatCompletionCompletionAndEmbeddingRequests() throws Exception {
    ProviderRequest chat = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "chat-bison-001",
              "messages": [{"role": "developer", "content": "Policy"}, {"role": "user", "content": "Hello"}],
              "temperature": 0.3,
              "top_p": 0.9,
              "top_k": 20,
              "n": 2,
              "max_completion_tokens": 64,
              "stop": ["END"]
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"palm\",\"api_key\":\"palm-key\"}"));
    ProviderRequest completion = factory.forEndpoint(
        "/v1/completions",
        "{\"model\":\"text-bison-001\",\"prompt\":\"Hello\",\"max_tokens\":32}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"palm\",\"api_key\":\"palm-key\"}"));
    ProviderRequest embedding = factory.forEndpoint(
        "/v1/embeddings",
        "{\"model\":\"embedding-gecko-001\",\"input\":\"Hello\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"palm\",\"api_key\":\"palm-key\"}"));

    JsonNode chatBody = readJson(chat.body());
    JsonNode completionBody = readJson(completion.body());
    JsonNode embeddingBody = readJson(embedding.body());

    assertThat(chat.url()).isEqualTo(
        "https://generativelanguage.googleapis.com/v1beta3/models/chat-bison-001:generateMessage?key=palm-key");
    assertThat(chat.headers()).doesNotContainKey("authorization");
    assertThat(chatBody.path("prompt").path("messages").get(0).path("author").asText()).isEqualTo("system");
    assertThat(chatBody.path("prompt").path("messages").get(1).path("content").asText()).isEqualTo("Hello");
    assertThat(chatBody.path("topP").asDouble()).isEqualTo(0.9);
    assertThat(chatBody.path("topK").asInt()).isEqualTo(20);
    assertThat(chatBody.path("candidateCount").asInt()).isEqualTo(2);
    assertThat(chatBody.path("maxOutputTokens").asInt()).isEqualTo(64);
    assertThat(chatBody.path("stopSequences").get(0).asText()).isEqualTo("END");
    assertThat(chatBody.has("messages")).isFalse();
    assertThat(chatBody.has("model")).isFalse();

    assertThat(completion.url()).isEqualTo(
        "https://generativelanguage.googleapis.com/v1beta3/models/text-bison-001:generateText?key=palm-key");
    assertThat(completionBody.path("prompt").path("text").asText()).isEqualTo("Hello");
    assertThat(completionBody.path("maxOutputTokens").asInt()).isEqualTo(32);
    assertThat(completionBody.has("model")).isFalse();

    assertThat(embedding.url()).isEqualTo(
        "https://generativelanguage.googleapis.com/v1beta3/models/embedding-gecko-001:embedText?key=palm-key");
    assertThat(embeddingBody.path("text").asText()).isEqualTo("Hello");
    assertThat(embeddingBody.has("input")).isFalse();
    assertThat(embeddingBody.has("model")).isFalse();
  }

  @Test
  void transformsRecraftImageGenerationDefaultsAndEndpoint() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/images/generations",
        "{\"prompt\":\"mountain\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"recraft-ai\",\"api_key\":\"recraft-key\"}"));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo("https://external.api.recraft.ai/v1/images/generations");
    assertThat(request.headers()).containsEntry("authorization", "Bearer recraft-key");
    assertThat(body.path("prompt").asText()).isEqualTo("mountain");
    assertThat(body.path("style").asText()).isEqualTo("realistic_image");
    assertThat(body.path("n").asInt()).isEqualTo(1);
    assertThat(body.path("size").asText()).isEqualTo("1024x1024");
    assertThat(body.path("response_format").asText()).isEqualTo("url");
  }

  @Test
  void transformsAi21ChatCompletionCompletionAndEmbeddingRequests() throws Exception {
    ProviderRequest chat = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "jamba-large",
              "messages": [
                {"role": "system", "content": "Policy"},
                {"role": "user", "content": "Hello"}
              ],
              "n": 2,
              "max_completion_tokens": 64,
              "temperature": 0.4,
              "top_p": 0.8,
              "top_k": 3,
              "stop": ["END"],
              "presence_penalty": 0.2,
              "frequency_penalty": 0.1
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"ai21\",\"api_key\":\"ai21-key\"}"));
    ProviderRequest completion = factory.forEndpoint(
        "/v1/completions",
        "{\"model\":\"j2-ultra\",\"prompt\":\"Hello\",\"max_tokens\":32}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"ai21\",\"api_key\":\"ai21-key\"}"));
    ProviderRequest embedding = factory.forEndpoint(
        "/v1/embeddings",
        "{\"model\":\"ignored\",\"input\":\"Hello\",\"type\":\"query\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"ai21\",\"api_key\":\"ai21-key\"}"));

    JsonNode chatBody = readJson(chat.body());
    JsonNode completionBody = readJson(completion.body());
    JsonNode embeddingBody = readJson(embedding.body());

    assertThat(chat.url()).isEqualTo("https://api.ai21.com/studio/v1/jamba-large/chat");
    assertThat(chat.headers()).containsEntry("authorization", "Bearer ai21-key");
    assertThat(chatBody.path("system").asText()).isEqualTo("Policy");
    assertThat(chatBody.path("messages").get(0).path("role").asText()).isEqualTo("user");
    assertThat(chatBody.path("messages").get(0).path("text").asText()).isEqualTo("Hello");
    assertThat(chatBody.path("numResults").asInt()).isEqualTo(2);
    assertThat(chatBody.path("maxTokens").asInt()).isEqualTo(64);
    assertThat(chatBody.path("topP").asDouble()).isEqualTo(0.8);
    assertThat(chatBody.path("topKReturn").asInt()).isEqualTo(3);
    assertThat(chatBody.path("stopSequences").get(0).asText()).isEqualTo("END");
    assertThat(chatBody.path("presencePenalty").path("scale").asDouble()).isEqualTo(0.2);
    assertThat(chatBody.path("frequencyPenalty").path("scale").asDouble()).isEqualTo(0.1);
    assertThat(chatBody.has("model")).isFalse();

    assertThat(completion.url()).isEqualTo("https://api.ai21.com/studio/v1/j2-ultra/complete");
    assertThat(completionBody.path("prompt").asText()).isEqualTo("Hello");
    assertThat(completionBody.path("maxTokens").asInt()).isEqualTo(32);
    assertThat(completionBody.has("model")).isFalse();

    assertThat(embedding.url()).isEqualTo("https://api.ai21.com/studio/v1/embed");
    assertThat(embeddingBody.path("texts").get(0).asText()).isEqualTo("Hello");
    assertThat(embeddingBody.path("type").asText()).isEqualTo("query");
    assertThat(embeddingBody.has("input")).isFalse();
    assertThat(embeddingBody.has("model")).isFalse();
  }

  @Test
  void transformsOllamaRequestsWhenCustomHostIsConfigured() throws Exception {
    ProviderRequest embedding = factory.forEndpoint(
        "/v1/embeddings",
        "{\"model\":\"nomic-embed-text\",\"input\":\"hello\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "ollama",
                  "custom_host": "http://localhost:11434"
                }
                """));
    ProviderRequest chat = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "llama3.1",
              "messages": [
                {"role": "developer", "content": "Policy"},
                {"role": "user", "content": "Hello"}
              ],
              "max_completion_tokens": 32,
              "thinking": {"type": "disabled"}
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "ollama",
                  "custom_host": "http://localhost:11434"
                }
                """));

    JsonNode embeddingBody = readJson(embedding.body());
    JsonNode chatBody = readJson(chat.body());

    assertThat(embedding.url()).isEqualTo("http://localhost:11434/api/embeddings");
    assertThat(embedding.headers()).doesNotContainKey("authorization");
    assertThat(embeddingBody.path("prompt").asText()).isEqualTo("hello");
    assertThat(embeddingBody.has("input")).isFalse();
    assertThat(chat.url()).isEqualTo("http://localhost:11434/v1/chat/completions");
    assertThat(chat.headers()).doesNotContainKey("authorization");
    assertThat(chatBody.path("messages").get(0).path("role").asText()).isEqualTo("system");
    assertThat(chatBody.path("max_tokens").asInt()).isEqualTo(32);
    assertThat(chatBody.path("think").asBoolean()).isFalse();
    assertThat(chatBody.has("max_completion_tokens")).isFalse();
    assertThat(chatBody.has("thinking")).isFalse();
  }

  @Test
  void transformsNomicEmbeddingRequests() throws Exception {
    ProviderRequest singleInput = factory.forEndpoint(
        "/v1/embeddings",
        "{\"model\":\"nomic-embed-text-v1\",\"input\":\"hello\",\"task_type\":\"search_document\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"nomic\",\"api_key\":\"nomic-key\"}"));
    ProviderRequest arrayInput = factory.forEndpoint(
        "/v1/embeddings",
        "{\"model\":\"nomic-embed-text-v1\",\"input\":[\"one\",\"two\"]}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"nomic\",\"api_key\":\"nomic-key\"}"));

    JsonNode singleBody = readJson(singleInput.body());
    JsonNode arrayBody = readJson(arrayInput.body());

    assertThat(singleInput.url()).isEqualTo("https://api-atlas.nomic.ai/v1/embedding/text");
    assertThat(singleInput.headers()).containsEntry("authorization", "Bearer nomic-key");
    assertThat(singleBody.path("texts").get(0).asText()).isEqualTo("hello");
    assertThat(singleBody.path("model").asText()).isEqualTo("nomic-embed-text-v1");
    assertThat(singleBody.path("task_type").asText()).isEqualTo("search_document");
    assertThat(singleBody.has("input")).isFalse();
    assertThat(arrayBody.path("texts").get(0).asText()).isEqualTo("one");
    assertThat(arrayBody.path("texts").get(1).asText()).isEqualTo("two");
  }

  @Test
  void transformsPredibaseChatCompletionRequests() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "llama-3:adapter-repo/2",
              "user": "team-a",
              "messages": [
                {"role": "developer", "content": "Policy"},
                {"role": "user", "content": "Hello"}
              ],
              "max_completion_tokens": 48,
              "temperature": 0.2,
              "stream": true
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"predibase\",\"api_key\":\"predibase-key\"}"));

    JsonNode body = readJson(request.body());

    assertThat(request.url())
        .isEqualTo("https://serving.app.predibase.com/team-a/deployments/v2/llms/llama-3/v1/chat/completions");
    assertThat(request.headers()).containsEntry("authorization", "Bearer predibase-key");
    assertThat(request.headers()).containsEntry("Accept", "application/json");
    assertThat(body.path("model").asText()).isEqualTo("adapter-repo/2");
    assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
    assertThat(body.path("max_tokens").asInt()).isEqualTo(48);
    assertThat(body.has("max_completion_tokens")).isFalse();
  }

  @Test
  void transformsRekaChatCompletionRequests() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "reka-flash",
              "messages": [
                {"role": "user", "content": [
                  {"type": "text", "text": "Describe this"},
                  {"type": "image_url", "image_url": {"url": "https://example.com/cat.png"}}
                ]},
                {"role": "assistant", "content": "A cat"}
              ],
              "max_completion_tokens": 64,
              "top_p": 0.7,
              "stop": "END",
              "seed": 42,
              "top_k": 5
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"reka-ai\",\"api_key\":\"reka-key\"}"));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo("https://api.reka.ai/chat");
    assertThat(request.headers()).containsEntry("x-api-key", "reka-key");
    assertThat(request.headers()).doesNotContainKey("authorization");
    assertThat(body.path("model_name").asText()).isEqualTo("reka-flash");
    assertThat(body.path("conversation_history").get(0).path("type").asText()).isEqualTo("human");
    assertThat(body.path("conversation_history").get(0).path("media_url").asText()).isEqualTo("https://example.com/cat.png");
    assertThat(body.path("conversation_history").get(1).path("type").asText()).isEqualTo("model");
    assertThat(body.path("conversation_history").get(1).path("text").asText()).isEqualTo("Placeholder for alternation");
    assertThat(body.path("conversation_history").get(2).path("text").asText()).isEqualTo("Describe this");
    assertThat(body.path("conversation_history").get(3).path("type").asText()).isEqualTo("model");
    assertThat(body.path("request_output_len").asInt()).isEqualTo(64);
    assertThat(body.path("runtime_top_p").asDouble()).isEqualTo(0.7);
    assertThat(body.path("stop_words").get(0).asText()).isEqualTo("END");
    assertThat(body.path("random_seed").asInt()).isEqualTo(42);
    assertThat(body.path("runtime_top_k").asInt()).isEqualTo(5);
    assertThat(body.has("messages")).isFalse();
    assertThat(body.has("model")).isFalse();
  }

  @Test
  void transformsSegmindImageGenerationRequests() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/images/generations",
        "{\"model\":\"sdxl1.0-txt2img\",\"prompt\":\"A city\",\"n\":2,\"size\":\"768x512\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"segmind\",\"api_key\":\"segmind-key\"}"));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo("https://api.segmind.com/v1/sdxl1.0-txt2img");
    assertThat(request.headers()).containsEntry("x-api-key", "segmind-key");
    assertThat(request.headers()).doesNotContainKey("authorization");
    assertThat(body.path("samples").asInt()).isEqualTo(2);
    assertThat(body.path("img_width").asInt()).isEqualTo(768);
    assertThat(body.path("img_height").asInt()).isEqualTo(512);
    assertThat(body.path("style").asText()).isEqualTo("base");
    assertThat(body.path("num_inference_steps").asInt()).isEqualTo(20);
    assertThat(body.path("scheduler").asText()).isEqualTo("UniPC");
    assertThat(body.path("guidance_scale").asDouble()).isEqualTo(7.5);
    assertThat(body.path("base64").asBoolean()).isTrue();
    assertThat(body.path("size").asInt()).isEqualTo(768);
    assertThat(body.has("model")).isFalse();
    assertThat(body.has("n")).isFalse();
  }

  @Test
  void transformsBytezChatCompletionRequests() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "org/model-name",
              "version": 3,
              "messages": [{"role": "user", "content": "Hello"}],
              "max_tokens": 32,
              "temperature": 0.4,
              "top_p": 0.8,
              "stream": true
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"bytez\",\"api_key\":\"bytez-key\"}"));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo("https://api.bytez.com/models/v3/org/model-name");
    assertThat(request.headers()).containsEntry("authorization", "Key bytez-key");
    assertThat(request.headers()).containsKey("user-agent");
    assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("Hello");
    assertThat(body.path("params").path("max_new_tokens").asInt()).isEqualTo(32);
    assertThat(body.path("params").path("temperature").asDouble()).isEqualTo(0.4);
    assertThat(body.path("params").path("top_p").asDouble()).isEqualTo(0.8);
    assertThat(body.path("stream").asBoolean()).isTrue();
    assertThat(body.has("model")).isFalse();
    assertThat(body.has("version")).isFalse();
    assertThat(body.has("max_tokens")).isFalse();
  }

  @Test
  void mapsLeptonOpenAiCompatibleEndpoints() {
    ProviderRequest chat = factory.forEndpoint(
        "/v1/chat/completions",
        "{\"model\":\"llama\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"lepton\",\"api_key\":\"lepton-key\"}"));
    ProviderRequest completion = factory.forEndpoint(
        "/v1/completions",
        "{\"model\":\"llama\",\"prompt\":\"Hello\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"lepton\",\"api_key\":\"lepton-key\"}"));
    ProviderRequest transcription = factory.forEndpointRawBody(
        "/v1/audio/transcriptions",
        "POST",
        "file-bytes".getBytes(),
        Map.of(
            "content-type", "multipart/form-data",
            "x-modelgate-config", "{\"provider\":\"lepton\",\"api_key\":\"lepton-key\"}"),
        false);

    assertThat(chat.url()).isEqualTo("https://api.lepton.ai/api/v1/chat/completions");
    assertThat(completion.url()).isEqualTo("https://api.lepton.ai/api/v1/completions");
    assertThat(transcription.url()).isEqualTo("https://api.lepton.ai/api/v1/audio/transcriptions");
    assertThat(chat.headers()).containsEntry("authorization", "Bearer lepton-key");
    assertThat(transcription.headers()).containsEntry("content-type", "multipart/form-data");
  }

  @Test
  void mapsMeshyAndTripo3dPassthroughRequests() {
    ProviderRequest meshyTextTo3d = factory.forEndpoint(
        "/text-to-3d",
        "{\"prompt\":\"chair\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"meshy\",\"api_key\":\"meshy-key\"}"));
    ProviderRequest meshyTask = factory.forEndpoint(
        "/tasks/task-1",
        "GET",
        "",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"meshy\",\"api_key\":\"meshy-key\"}"));
    ProviderRequest tripo = factory.forEndpoint(
        "/task",
        "{\"prompt\":\"chair\"}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"tripo3d\",\"api_key\":\"tripo-key\"}"));

    assertThat(meshyTextTo3d.url()).isEqualTo("https://api.meshy.ai/openapi/v2/text-to-3d");
    assertThat(meshyTask.url()).isEqualTo("https://api.meshy.ai/openapi/v1/tasks/task-1");
    assertThat(tripo.url()).isEqualTo("https://api.tripo3d.ai/v2/openapi/task");
    assertThat(meshyTextTo3d.headers()).containsEntry("authorization", "Bearer meshy-key");
    assertThat(meshyTextTo3d.headers()).containsEntry("content-type", "application/json");
    assertThat(meshyTask.headers()).doesNotContainKey("content-type");
    assertThat(tripo.headers()).containsEntry("authorization", "Bearer tripo-key");
  }

  @Test
  void mapsVectorDatabasePassthroughRequests() {
    ProviderRequest qdrant = factory.forEndpoint(
        "/collections/docs/points/search",
        "{\"vector\":[0.1,0.2],\"limit\":3}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "qdrant",
                  "api_key": "qdrant-key",
                  "custom_host": "https://cluster.qdrant.io"
                }
                """));
    ProviderRequest milvus = factory.forEndpoint(
        "/v2/vectordb/entities/search",
        "{\"collectionName\":\"docs\",\"data\":[[0.1,0.2]],\"limit\":3}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "milvus",
                  "api_key": "milvus-key",
                  "custom_host": "https://cluster.milvus.io"
                }
                """));

    assertThat(qdrant.url()).isEqualTo("https://cluster.qdrant.io/collections/docs/points/search");
    assertThat(qdrant.headers()).containsEntry("api-key", "Bearer qdrant-key");
    assertThat(qdrant.headers()).doesNotContainKey("authorization");
    assertThat(milvus.url()).isEqualTo("https://cluster.milvus.io/v2/vectordb/entities/search");
    assertThat(milvus.headers()).containsEntry("authorization", "Bearer milvus-key");
  }

  @Test
  void transformsTritonCompletionRequests() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/completions",
        "{\"model\":\"ensemble\",\"prompt\":\"Hello\",\"max_tokens\":12,\"temperature\":0.2,\"stop\":[\"END\"]}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "triton",
                  "custom_host": "http://localhost:8000"
                }
                """));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo("http://localhost:8000/generate");
    assertThat(request.headers()).doesNotContainKey("authorization");
    assertThat(body.path("text_input").asText()).isEqualTo("Hello");
    assertThat(body.path("max_tokens").asInt()).isEqualTo(12);
    assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
    assertThat(body.path("top_p").asDouble()).isEqualTo(0.7);
    assertThat(body.path("top_k").asInt()).isEqualTo(50);
    assertThat(body.path("stop_words").get(0).asText()).isEqualTo("END");
    assertThat(body.has("prompt")).isFalse();
    assertThat(body.has("model")).isFalse();
  }

  @Test
  void transformsTogetherAiChatRequests() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "meta-llama/Llama-3.2",
              "messages": [{"role": "developer", "content": "Policy"}],
              "max_completion_tokens": 64,
              "frequency_penalty": 0.4
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config", "{\"provider\":\"together-ai\",\"api_key\":\"together-key\"}"));

    JsonNode body = readJson(request.body());

    assertThat(request.url()).isEqualTo("https://api.together.xyz/v1/chat/completions");
    assertThat(request.headers()).containsEntry("authorization", "Bearer together-key");
    assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
    assertThat(body.path("max_tokens").asInt()).isEqualTo(64);
    assertThat(body.path("repetition_penalty").asDouble()).isEqualTo(0.4);
    assertThat(body.has("max_completion_tokens")).isFalse();
    assertThat(body.has("frequency_penalty")).isFalse();
  }

  @Test
  void modalCanUseModelCredentialHeadersWhenApiKeyIsOmitted() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{\"model\":\"modal-model\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "modal",
                  "provider_options": {
                    "modelKey": "modal-key",
                    "modelSecret": "modal-secret"
                  }
                }
                """));

    assertThat(request.url()).isEqualTo("https://api.modal.com/v1/chat/completions");
    assertThat(request.headers()).containsEntry("model-key", "modal-key");
    assertThat(request.headers()).containsEntry("model-secret", "modal-secret");
    assertThat(request.headers()).doesNotContainKey("authorization");
  }

  @Test
  void transformsOracleChatCompletionRequests() throws Exception {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        """
            {
              "model": "cohere.command-r-plus",
              "messages": [
                {"role": "developer", "content": "Policy"},
                {"role": "user", "content": [
                  {"type": "text", "text": "Hello"},
                  {"type": "image_url", "image_url": {"url": "https://example.com/cat.png", "detail": "low"}}
                ]}
              ],
              "max_completion_tokens": 64,
              "top_p": 0.8,
              "temperature": 0.2,
              "stream": true,
              "stop": "END",
              "top_k": 5
            }
            """,
        Map.of(
            "content-type", "application/json",
            "x-modelgate-config",
            """
                {
                  "provider": "oracle",
                  "oracle_region": "us-ashburn-1",
                  "oracle_compartment_id": "ocid1.compartment.oc1..test",
                  "oracle_api_version": "20231130",
                  "oracle_serving_mode": "ON_DEMAND"
                }
                """));

    JsonNode body = readJson(request.body());

    assertThat(request.url())
        .isEqualTo("https://inference.generativeai.us-ashburn-1.oci.oraclecloud.com/20231130/actions/chat");
    assertThat(body.path("compartmentId").asText()).isEqualTo("ocid1.compartment.oc1..test");
    assertThat(body.path("servingMode").path("servingType").asText()).isEqualTo("ON_DEMAND");
    assertThat(body.path("servingMode").path("modelId").asText()).isEqualTo("cohere.command-r-plus");
    assertThat(body.path("chatRequest").path("apiFormat").asText()).isEqualTo("GENERIC");
    assertThat(body.path("chatRequest").path("isStream").asBoolean()).isTrue();
    assertThat(body.path("chatRequest").path("maxTokens").asInt()).isEqualTo(64);
    assertThat(body.path("chatRequest").path("topP").asDouble()).isEqualTo(0.8);
    assertThat(body.path("chatRequest").path("topK").asInt()).isEqualTo(5);
    assertThat(body.path("chatRequest").path("stop").get(0).asText()).isEqualTo("END");
    assertThat(body.path("chatRequest").path("messages").get(0).path("role").asText()).isEqualTo("SYSTEM");
    assertThat(body.path("chatRequest").path("messages").get(1).path("content").get(0).path("type").asText())
        .isEqualTo("TEXT");
    assertThat(body.path("chatRequest").path("messages").get(1).path("content").get(1).path("imageUrl").path("url").asText())
        .isEqualTo("https://example.com/cat.png");
    assertThat(body.has("model")).isFalse();
    assertThat(body.has("messages")).isFalse();
  }

  @Test
  void anthropicCredentialAndVersionCanComeFromRequestHeaders() {
    ProviderRequest request = factory.forEndpoint(
        "/v1/chat/completions",
        "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-provider", "anthropic",
            "x-api-key", "sk-header",
            "anthropic-version", "2023-06-01",
            "anthropic-beta", "messages-2023-12-15"));

    assertThat(request.headers()).containsEntry("x-api-key", "sk-header");
    assertThat(request.headers()).containsEntry("anthropic-version", "2023-06-01");
    assertThat(request.headers()).containsEntry("anthropic-beta", "messages-2023-12-15");
    assertThat(request.headers()).doesNotContainKey("authorization");
  }

  @Test
  void targetProviderOptionsOverrideInheritedProviderOptionsForHeaders() {
    GatewayConfig inheritedConfig = GatewayConfig.builder()
        .provider("openai")
        .apiKey("sk-inherited")
        .providerOptions(new ProviderOptions(Map.of(
            "provider", "openai",
            "apiKey", "sk-inherited",
            "openaiProject", "inherited-project")))
        .build();

    ProviderRequest request = factory.forTarget(
        Target.builder()
            .name("target")
            .provider("openai")
            .apiKey("sk-target")
            .customHost("http://localhost:9998")
            .providerOptions(new ProviderOptions(Map.of(
                "provider", "openai",
                "apiKey", "sk-target",
                "openaiProject", "target-project")))
            .build(),
        "/v1/chat/completions",
        "POST",
        "{}",
        Map.of("content-type", "application/json"),
        false,
        inheritedConfig);

    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-target");
    assertThat(request.headers()).containsEntry("OpenAI-Project", "target-project");
  }

  @Test
  void buildsRequestFromTargetCustomHostAndCredentials() {
    ProviderRequest request = factory.forTarget(
        Target.builder()
            .name("fallback")
            .provider("openai")
            .apiKey("sk-target")
            .customHost("http://localhost:9998")
            .build(),
        "/v1/chat/completions",
        "{}",
        Map.of("content-type", "application/json"));

    assertThat(request.url()).isEqualTo("http://localhost:9998/v1/chat/completions");
    assertThat(request.headers()).containsEntry("authorization", "Bearer sk-target");
  }

  @Test
  void targetRetryAndTimeoutOverrideInheritedConfig() {
    ProviderRequest request = factory.forTarget(
        Target.builder()
            .name("fallback")
            .provider("openai")
            .apiKey("sk-target")
            .customHost("http://localhost:9998")
            .retry(new RetrySettings(1, List.of(500), false))
            .requestTimeoutMillis(50)
            .build(),
        "/v1/chat/completions",
        "POST",
        "{}",
        Map.of("content-type", "application/json"),
        false,
        GatewayConfig.builder()
            .provider("openai")
            .retry(new RetrySettings(0, List.of(429), false))
            .requestTimeoutMillis(1000)
            .build());

    assertThat(request.retryPolicy().attempts()).isEqualTo(1);
    assertThat(request.retryPolicy().onStatusCodes()).containsExactly(500);
    assertThat(request.timeout()).isEqualTo(Duration.ofMillis(50));
  }

  @Test
  void requestTimeoutHeaderOverridesTargetTimeout() {
    ProviderRequest request = factory.forTarget(
        Target.builder()
            .name("fallback")
            .provider("openai")
            .apiKey("sk-target")
            .customHost("http://localhost:9998")
            .requestTimeoutMillis(50)
            .build(),
        "/v1/chat/completions",
        "POST",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-modelgate-request-timeout", "25"),
        false,
        GatewayConfig.builder()
            .provider("openai")
            .requestTimeoutMillis(1000)
            .build());

    assertThat(request.timeout()).isEqualTo(Duration.ofMillis(25));
  }

  @Test
  void targetForwardHeadersOverrideInheritedForwardHeaders() {
    GatewayConfig inheritedConfig = GatewayConfig.builder()
        .provider("openai")
        .forwardHeaders(List.of("x-client-tenant"))
        .build();

    ProviderRequest inheritedRequest = factory.forTarget(
        Target.builder()
            .name("fallback")
            .provider("openai")
            .apiKey("sk-target")
            .customHost("http://localhost:9998")
            .build(),
        "/v1/chat/completions",
        "POST",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-client-tenant", "acme",
            "x-target-only", "target"),
        false,
        inheritedConfig);
    ProviderRequest overrideRequest = factory.forTarget(
        Target.builder()
            .name("fallback")
            .provider("openai")
            .apiKey("sk-target")
            .customHost("http://localhost:9998")
            .forwardHeaders(List.of("x-target-only"))
            .build(),
        "/v1/chat/completions",
        "POST",
        "{}",
        Map.of(
            "content-type", "application/json",
            "x-client-tenant", "acme",
            "x-target-only", "target"),
        false,
        inheritedConfig);

    assertThat(inheritedRequest.headers()).containsEntry("x-client-tenant", "acme");
    assertThat(inheritedRequest.headers()).doesNotContainKey("x-target-only");
    assertThat(overrideRequest.headers()).containsEntry("x-target-only", "target");
    assertThat(overrideRequest.headers()).doesNotContainKey("x-client-tenant");
  }

  private record ProviderDefault(String provider, String baseUrl) {}

  private JsonNode readJson(String body) {
    try {
      return new ObjectMapper().readTree(body);
    } catch (Exception exception) {
      throw new AssertionError("Expected valid JSON body", exception);
    }
  }

  private static HttpServer tokenServer(AtomicReference<String> requestBody, String responseBody) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("content-type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    return server;
  }

  private static String pemPrivateKey(KeyPair keyPair) {
    return "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
            .encodeToString(keyPair.getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----";
  }

  private static void restoreSystemProperty(String key, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previousValue);
    }
  }
}
