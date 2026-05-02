package com.modelgate.gateway.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PartnerPluginContractTest {
  private final PluginRegistry registry = DefaultPluginRegistry.create();

  @Test
  void qualifirePostsApiKeyAndBlocksFailedStatus() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"status":"failed","evaluationResults":{"policy":"blocked"}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "qualifire.contentModeration",
          requestResponseContext(),
          parameters(server.url("/qualifire")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/qualifire");
      assertThat(captured.header("X-Qualifire-API-Key")).isEqualTo("test-key");
      assertThat(captured.body()).contains("\"content_moderation_check\":true");
      assertThat(result.data()).containsEntry("policy", "blocked");
    }
  }

  @ParameterizedTest
  @MethodSource("qualifireCheckPlugins")
  void qualifireCheckPluginsPostTheirSpecificCheckFlag(String pluginId, String checkKey) throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"status":"success","evaluationResults":{"ok":true}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          pluginId,
          requestResponseContext(),
          parameters(server.url("/qualifire"), Map.of("mode", "strict")));

      assertThat(result.verdict()).isTrue();
      assertThat(captured.path()).isEqualTo("/qualifire");
      assertThat(captured.body()).contains("\"" + checkKey + "\":true");
      assertThat(captured.body()).contains("\"input\":\"clean prompt\"");
      assertThat(captured.body()).contains("\"output\":\"clean response\"");
    }
  }

  @Test
  void qualifirePolicyPostsAssertionsAndMode() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"status":"failed","evaluationResults":{"policy":"blocked"}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "qualifire.policy",
          requestContext(),
          parameters(server.url("/qualifire"), Map.of(
              "policies", List.of(Map.of("type", "contains", "value", "secret")),
              "mode", "strict")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/qualifire");
      assertThat(captured.body()).contains("\"assertions\"");
      assertThat(captured.body()).contains("\"assertions_mode\":\"strict\"");
    }
  }

  @Test
  void qualifirePolicyHonorsOutputOnlyTarget() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"status":"success","evaluationResults":{"policy":"ok"}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "qualifire.policy",
          requestResponseContext(),
          parameters(server.url("/qualifire"), Map.of(
              "policies", List.of(Map.of("type", "contains", "value", "safe")),
              "policy_target", "output")));

      assertThat(result.verdict()).isTrue();
      assertThat(captured.body()).doesNotContain("\"input\"");
      assertThat(captured.body()).contains("\"output\":\"clean response\"");
    }
  }

  @Test
  void qualifirePromptInjectionsOnlyRunsBeforeRequest() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"status":"success","evaluationResults":{"ok":true}}
        """);
    try (RunningServer ignored = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "qualifire.promptInjections",
          requestResponseContext(),
          parameters(ignored.url("/qualifire")));

      assertThat(result.verdict()).isFalse();
      assertThat(result.error()).contains("only supports before_request_hooks");
      assertThat(captured.path()).isNull();
    }
  }

  @Test
  void qualifirePromptInjectionsPostsBeforeRequestInput() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"status":"success","evaluationResults":{"ok":true}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "qualifire.promptInjections",
          requestContext(),
          parameters(server.url("/qualifire")));

      assertThat(result.verdict()).isTrue();
      assertThat(captured.body()).contains("\"prompt_injections\":true");
      assertThat(captured.body()).contains("\"input\":\"clean prompt\"");
      assertThat(captured.body()).doesNotContain("\"output\"");
    }
  }

  @Test
  void qualifireToolUseQualityPostsMessagesToolsAndTsqModeAfterRequest() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"status":"success","evaluationResults":{"tool_quality":"ok"}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "qualifire.toolUseQuality",
          toolUseQualityContext(),
          parameters(server.url("/qualifire"), Map.of("mode", "strict")));

      assertThat(result.verdict()).isTrue();
      assertThat(captured.body()).contains("\"tool_selection_quality_check\":true");
      assertThat(captured.body()).contains("\"tsq_mode\":\"strict\"");
      assertThat(captured.body()).contains("\"available_tools\"");
      assertThat(captured.body()).contains("\"name\":\"lookup\"");
      assertThat(captured.body()).contains("\"role\":\"assistant\"");
      assertThat(captured.body()).doesNotContain("\"output\"");
    }
  }

  @Test
  void portkeyModerationPostsInputAndBlocksRestrictedCategory() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"response":{"results":[{"categories":{"hate":true,"sexual":false}}]}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "portkey.moderateContent",
          requestContext(),
          parameters(server.url(""), Map.of("categories", List.of("hate"))));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/moderations");
      assertThat(captured.body()).contains("\"input\":\"clean prompt\"");
      assertThat(result.data().get("flaggedCategories")).asList().containsExactly("hate");
    }
  }

  @Test
  void portkeyLanguagePostsInputAndAllowsConfiguredLanguages() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"response":{"label":"fr"}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "portkey.language",
          requestContext(),
          parameters(server.url(""), Map.of("language", List.of("en"))));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/language");
      assertThat(captured.body()).contains("\"input\":\"clean prompt\"");
      assertThat(result.data()).containsEntry("detectedLanguage", "fr");
    }
  }

  @Test
  void portkeyPiiRedactsProcessedTextWhenConfigured() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"response":[{"entities":[{"labels":{"EMAIL":0.99}}],"processed_text":"clean [EMAIL]"}]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "portkey.pii",
          requestContext(),
          parameters(server.url(""), Map.of("categories", List.of("EMAIL"), "redact", true)));

      assertThat(result.verdict()).isTrue();
      assertThat(result.transformed()).isTrue();
      assertThat(captured.path()).isEqualTo("/pii");
      assertThat(captured.body()).contains("\"input\":[\"clean prompt\"]");
      assertThat(result.transformedData().requestJson().get("prompt")).isEqualTo("clean [EMAIL]");
    }
  }

  @Test
  void portkeyGibberishBlocksNonCleanLabel() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"response":{"label":"gibberish"}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "portkey.gibberish",
          requestContext(),
          parameters(server.url("")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/gibberish");
      assertThat(captured.body()).contains("\"input\":\"clean prompt\"");
    }
  }

  @Test
  void aporiaPostsProjectValidationAndBlocksNonPassthroughAction() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"action":"modify","policy_execution_result":{"action":{"type":"modify"}}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "aporia.validateProject",
          requestContext(),
          parameters(server.url(""), Map.of("projectID", "project-1")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/project-1/validate");
      assertThat(captured.header("X-APORIA-API-KEY")).isEqualTo("test-key");
      assertThat(captured.body()).contains("\"validation_target\":\"prompt\"");
    }
  }

  @Test
  void sydeguardPostsPromptAndBlocksScoresOverThreshold() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"category_scores":[{"category":"PROMPT_INJECT","score":0.91},{"category":"TOXIC","score":0.1}]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "sydelabs.sydeguard",
          requestContext(),
          parameters(server.url("/sydeguard"), Map.of("prompt_injection_threshold", 0.5)));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/sydeguard");
      assertThat(captured.header("x-api-key")).isEqualTo("test-key");
      assertThat(captured.body()).contains("\"prompt\":\"clean prompt\"");
    }
  }

  @Test
  void pillarPostsScannerObjectAndBlocksDetectedScanner() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"pii":true,"prompt_injection":false,"secrets":null}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "pillar.scanPrompt",
          requestContext(),
          parameters(server.url(""), Map.of("scanners", List.of("pii", "secrets"))));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/scan/prompt");
      assertThat(captured.header("Authorization")).isEqualTo("Bearer test-key");
      assertThat(captured.body()).contains("\"scanners\":{\"pii\":true,\"secrets\":true}");
    }
  }

  @Test
  void pillarScanResponseUsesResponsePathAndResponseText() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"toxic_language":true}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "pillar.scanResponse",
          requestResponseContext(),
          parameters(server.url(""), Map.of("scanners", List.of("toxic_language"))));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/scan/response");
      assertThat(captured.body()).contains("\"message\":\"clean response\"");
      assertThat(captured.body()).contains("\"toxic_language\":true");
    }
  }

  @Test
  void patronusJudgePostsEvaluatorAndUsesEvaluationPass() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"results":[{"error_message":null,"evaluation_result":{"pass":false,"additional_info":{"score":0.2}}}]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "patronus.isHelpful",
          requestResponseContext(),
          parameters(server.url("/evaluate")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/evaluate");
      assertThat(captured.header("x-api-key")).isEqualTo("test-key");
      assertThat(captured.body()).contains("\"evaluator\":\"judge\"");
      assertThat(captured.body()).contains("\"criteria\":\"patronus:is-helpful\"");
      assertThat(result.data()).containsEntry("score", 0.2);
    }
  }

  @ParameterizedTest
  @MethodSource("patronusJudgePlugins")
  void patronusJudgePluginsPostExpectedEvaluatorAndCriteria(String pluginId, String evaluator, String criteria) throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"results":[{"error_message":null,"evaluation_result":{"pass":true,"additional_info":{"score":1.0}}}]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          pluginId,
          requestResponseContext(),
          parameters(server.url("/evaluate")));

      assertThat(result.verdict()).isTrue();
      assertThat(captured.path()).isEqualTo("/evaluate");
      assertThat(captured.body()).contains("\"evaluator\":\"" + evaluator + "\"");
      if (criteria != null) {
        assertThat(captured.body()).contains("\"criteria\":\"" + criteria + "\"");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("patronusPiiLikePlugins")
  void patronusPiiLikePluginsPostCurrentTextToEvaluator(String pluginId, String evaluator) throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"results":[{"error_message":"detected","evaluation_result":{"pass":false,"additional_info":{"positions":[[0,5]]}}}]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          pluginId,
          requestContext(),
          parameters(server.url("/evaluate")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/evaluate");
      assertThat(captured.body()).contains("\"evaluator\":\"" + evaluator + "\"");
      assertThat(captured.body()).contains("\"evaluated_model_output\":\"clean prompt\"");
    }
  }

  @Test
  void patronusCustomSplitsEvaluatorAndCriteriaFromProfile() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"results":[{"error_message":null,"evaluation_result":{"pass":true,"additional_info":{}}}]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "patronus.custom",
          requestResponseContext(),
          parameters(server.url("/evaluate"), Map.of("profile", "judge:custom-policy")));

      assertThat(result.verdict()).isTrue();
      assertThat(captured.path()).isEqualTo("/evaluate");
      assertThat(captured.body()).contains("\"evaluator\":\"judge\"");
      assertThat(captured.body()).contains("\"criteria\":\"custom-policy\"");
    }
  }

  @Test
  void mistralModerationBlocksConfiguredFlaggedCategory() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"results":[{"categories":{"sexual":true,"pii":false},"category_score":{"sexual":0.9}}]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "mistral.moderateContent",
          requestContext(),
          parameters(server.url(""), Map.of("categories", List.of("sexual"))));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/v1/moderations");
      assertThat(captured.header("Authorization")).isEqualTo("Bearer test-key");
      assertThat(captured.body()).contains("\"input\":[\"clean prompt\"]");
    }
  }

  @Test
  void pangeaTextGuardBlocksDetectedDetector() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"status":"Success","result":{"detectors":{"prompt_injection":{"detected":true}}}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "pangea.textGuard",
          requestContext(),
          parameters(server.url("/v1/text/guard")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/v1/text/guard");
      assertThat(captured.header("Authorization")).isEqualTo("Bearer test-key");
      assertThat(captured.body()).contains("\"text\":\"clean prompt\"");
    }
  }

  @Test
  void pangeaPiiRedactsWhenConfigured() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"summary":"redacted","result":{"count":1,"redacted_data":["clean [EMAIL]"]}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "pangea.pii",
          requestContext(),
          parameters(server.url("/v1/redact_structured"), Map.of("redact", true)));

      assertThat(result.verdict()).isTrue();
      assertThat(result.transformed()).isTrue();
      assertThat(result.transformedData().requestJson().get("prompt")).isEqualTo("clean [EMAIL]");
      assertThat(captured.body()).contains("\"data\":[\"clean prompt\"]");
    }
  }

  @Test
  void pangeaPiiRedactsResponseJsonWhenScanningResponse() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"summary":"redacted","result":{"count":1,"redacted_data":["clean [EMAIL]"]}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "pangea.pii",
          responseHookContext(),
          parameters(server.url("/v1/redact_structured"), Map.of("redact", true)));

      assertThat(result.verdict()).isTrue();
      assertThat(result.transformed()).isTrue();
      assertThat(result.transformedData().requestJson()).isEmpty();
      assertThat(result.transformedData().responseJson().toString()).contains("clean [EMAIL]");
    }
  }

  @ParameterizedTest
  @MethodSource("promptfooPlugins")
  void promptfooPluginsPostInputToTheirEndpoint(String pluginId, String responseJson, String path) throws Exception {
    CapturedExchange captured = new CapturedExchange(responseJson);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          pluginId,
          requestContext(),
          parameters(server.url("")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo(path);
      assertThat(captured.body()).contains("\"input\":\"clean prompt\"");
    }
  }

  @Test
  void bedrockGuardBlocksGuardrailIntervention() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"action":"GUARDRAIL_INTERVENED","outputs":[{"text":"blocked"}]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "bedrock.guard",
          requestContext(),
          parameters(server.url("")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/bedrock/guard");
      assertThat(captured.body()).contains("\"source\":\"INPUT\"");
      assertThat(captured.body()).contains("\"content\"");
    }
  }

  @Test
  void lassoClassifyPostsMessagesAndBlocksViolations() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"deputies":{"prompt_injection":true},"deputies_predictions":{"prompt_injection":0.95},"violations_detected":true}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "lasso.classify",
          requestContext(),
          parameters(server.url(""), Map.of("conversationId", "c-1", "userId", "u-1")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/gateway/v2/classify");
      assertThat(captured.header("lasso-api-key")).isEqualTo("test-key");
      assertThat(captured.header("lasso-conversation-id")).isEqualTo("c-1");
      assertThat(captured.body()).contains("\"messages\"");
    }
  }

  @Test
  void exaOnlineTransformsChatRequestWithSearchContext() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"query":"clean prompt","count":1,"results":[{"title":"Doc","url":"https://example.com","text":"Evidence"}]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "exa.online",
          requestContext(),
          parameters(server.url("/search"), Map.of("numResults", 1)));

      assertThat(result.verdict()).isTrue();
      assertThat(result.transformed()).isTrue();
      assertThat(captured.path()).isEqualTo("/search");
      assertThat(captured.header("x-api-key")).isEqualTo("test-key");
      assertThat(captured.body()).contains("\"numResults\":1");
      assertThat(result.transformedData().requestJson().toString()).contains("web_search_context");
    }
  }

  @Test
  void exaOnlineAppendsSearchContextToExistingSystemMessage() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"results":[{"title":"Doc","url":"https://example.com","text":"Evidence"}]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "exa.online",
          systemMessageContext(),
          parameters(server.url("/search"), Map.of("numResults", 1)));

      assertThat(result.verdict()).isTrue();
      assertThat(result.transformed()).isTrue();
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> messages = (List<Map<String, Object>>) result.transformedData().requestJson().get("messages");
      assertThat(messages).hasSize(2);
      assertThat(messages.getFirst().get("role")).isEqualTo("system");
      assertThat(messages.getFirst().get("content")).asString().startsWith("Existing policy");
      assertThat(messages.getFirst().get("content")).asString().contains("web_search_context");
    }
  }

  @Test
  void exaOnlineSkipsAfterRequestHooks() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"results":[{"title":"Doc","url":"https://example.com","text":"Evidence"}]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "exa.online",
          requestResponseContext(),
          parameters(server.url("/search"), Map.of("numResults", 1)));

      assertThat(result.verdict()).isTrue();
      assertThat(result.transformed()).isFalse();
      assertThat(captured.path()).isNull();
    }
  }

  @Test
  void azureShieldPromptBlocksDetectedAttack() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"userPromptAnalysis":{"attackDetected":true},"documentsAnalysis":[]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "azure.shieldPrompt",
          requestContext(),
          parameters(server.url("")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).contains("/contentsafety/text:shieldPrompt");
      assertThat(captured.header("Ocp-Apim-Subscription-Key")).isEqualTo("test-key");
      assertThat(captured.body()).contains("\"userPrompt\":\"clean prompt\"");
    }
  }

  @Test
  void azureProtectedMaterialBlocksDetectedMaterial() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"protectedMaterialAnalysis":{"detected":true}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "azure.protectedMaterial",
          requestContext(),
          parameters(server.url("")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).contains("/contentsafety/text:detectProtectedMaterial");
      assertThat(captured.header("Ocp-Apim-Subscription-Key")).isEqualTo("test-key");
      assertThat(captured.body()).contains("\"text\":\"clean prompt\"");
    }
  }

  @Test
  void azureContentSafetyBlocksSeverityAtThreshold() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"categoriesAnalysis":[{"category":"Hate","severity":3}],"blocklistsMatch":[]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "azure.contentSafety",
          requestContext(),
          parameters(server.url(""), Map.of("severity", 2)));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).contains("/contentsafety/text:analyze");
      assertThat(captured.header("Ocp-Apim-Subscription-Key")).isEqualTo("test-key");
      assertThat(captured.body()).contains("\"text\":\"clean prompt\"");
    }
  }

  @Test
  void azurePiiRedactsDocumentTextWhenConfigured() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"results":{"documents":[{"id":"0","redactedText":"clean [EMAIL]","entities":[{"text":"a@example.com"}]}]}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "azure.pii",
          requestContext(),
          parameters(server.url(""), Map.of("redact", true)));

      assertThat(result.verdict()).isTrue();
      assertThat(result.transformed()).isTrue();
      assertThat(captured.path()).contains("/language/:analyze-text");
      assertThat(captured.body()).contains("\"kind\":\"PiiEntityRecognition\"");
      assertThat(result.transformedData().requestJson().get("prompt")).isEqualTo("clean [EMAIL]");
    }
  }

  @Test
  void promptSecurityProtectResponsePostsResponseText() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"result":{"response":{"passed":false,"reason":"unsafe output"}}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "promptsecurity.protectResponse",
          requestResponseContext(),
          parameters(server.url("")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/api/protect");
      assertThat(captured.header("APP-ID")).isEqualTo("test-key");
      assertThat(captured.body()).contains("\"response\":\"clean response\"");
    }
  }

  @Test
  void promptSecurityUsesTargetSpecificResult() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"result":{"prompt":{"passed":false,"reason":"unsafe"}}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "promptsecurity.protectPrompt",
          requestContext(),
          parameters(server.url("")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/api/protect");
      assertThat(captured.header("APP-ID")).isEqualTo("test-key");
      assertThat(captured.body()).contains("\"prompt\":\"clean prompt\"");
    }
  }

  @Test
  void panwPrismaAirsBlocksBlockActionAndUsesTraceId() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"action":"block","profile":"strict"}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "panw-prisma-airs.intercept",
          requestContext(),
          parameters(server.url("/scan")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/scan");
      assertThat(captured.header("x-pan-token")).isEqualTo("test-key");
      assertThat(captured.body()).contains("\"tr_id\":\"trace-1\"");
    }
  }

  @Test
  void walledAiBlocksUnsafeSafetyResult() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"data":{"safety":[{"isSafe":false,"policy":"generic"}]}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "walledai.walledprotect",
          requestContext(),
          parameters(server.url("/v1/walled-protect")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/v1/walled-protect");
      assertThat(captured.header("x-api-key")).isEqualTo("test-key");
      assertThat(captured.body()).contains("\"generic_safety_check\":true");
    }
  }

  @Test
  void javelinBlocksRejectedAssessment() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"assessments":[{"prompt_injection":{"request_reject":true,"results":{"reject_prompt":"blocked"}}}]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "javelin.guardrails",
          requestContext(),
          parameters(server.url("")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/v1/guardrails/apply");
      assertThat(captured.header("x-javelin-apikey")).isEqualTo("test-key");
      assertThat(captured.header("x-javelin-application")).isEqualTo("test-app");
    }
  }

  @Test
  void f5GuardrailsRedactsWhenConfigured() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"id":"scan-1","redactedInput":"clean [REDACTED]","result":{"outcome":"redacted","scannerResults":[]}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "f5-guardrails.scan",
          requestContext(),
          parameters(server.url(""), Map.of("redact", true, "projectId", "project-1")));

      assertThat(result.verdict()).isTrue();
      assertThat(result.transformed()).isTrue();
      assertThat(captured.path()).isEqualTo("/backend/v1/scans");
      assertThat(captured.body()).contains("\"project\":\"project-1\"");
      assertThat(result.transformedData().requestJson().get("prompt")).isEqualTo("clean [REDACTED]");
    }
  }

  @Test
  void acuvityPostsScanEnvelopeAndBlocksMatchedExtraction() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"extractions":[{"data":"clean prompt","matched":true}]}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "acuvity.scan",
          requestContext(),
          parameters(server.url("")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/_acuvity/scan");
      assertThat(captured.header("Authorization")).isEqualTo("Bearer test-key");
      assertThat(captured.body()).contains("\"anonymization\":\"FixedSize\"");
    }
  }

  @Test
  void crowdstrikeAidrBlocksPolicyViolation() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"status":"Success","result":{"blocked":true,"policy":"strict"}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "crowdstrike-aidr.guardChatCompletions",
          requestContext(),
          crowdstrikeParameters(server.url("")));

      assertThat(result.verdict()).isFalse();
      assertThat(captured.path()).isEqualTo("/v1/guard_chat_completions");
      assertThat(captured.header("Authorization")).isEqualTo("Bearer test-key");
      assertThat(captured.header("User-Agent")).isEqualTo("portkey-ai-plugin/v1.0.0-beta");
      assertThat(captured.body()).contains("\"event_type\":\"input\"");
      assertThat(captured.body()).contains("\"app_id\":\"Portkey AI Gateway\"");
      assertThat(captured.body()).contains("\"guard_input\"");
      assertThat(result.data()).containsEntry("explanation", "Blocked by AIDR Policy 'strict'");
    }
  }

  @Test
  void crowdstrikeAidrTransformsResponseJsonWhenAllowedWithTransformations() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"status":"Success","result":{"blocked":false,"transformed":true,"policy":"redact","guard_output":{"choices":[{"message":{"content":"redacted response"}}]}}}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult result = registry.execute(
          "crowdstrike-aidr.guardChatCompletions",
          responseHookContext(),
          crowdstrikeParameters(server.url("")));

      assertThat(result.verdict()).isTrue();
      assertThat(result.transformed()).isTrue();
      assertThat(captured.body()).contains("\"event_type\":\"output\"");
      assertThat(result.transformedData().responseJson().toString()).contains("redacted response");
    }
  }

  @Test
  void manifestPluginAliasesRouteToExistingHandlers() throws Exception {
    CapturedExchange captured = new CapturedExchange("""
        {"extractions":[{"data":"clean prompt","matched":false}],"action":"allow"}
        """);
    try (RunningServer server = RunningServer.start(captured)) {
      PluginResult acuvity = registry.execute(
          "acuvity.Acuvity",
          requestContext(),
          parameters(server.url("")));

      assertThat(acuvity.verdict()).isTrue();
      assertThat(captured.path()).isEqualTo("/_acuvity/scan");
    }

    CapturedExchange panwCaptured = new CapturedExchange("""
        {"action":"allow","profile":"strict"}
        """);
    try (RunningServer server = RunningServer.start(panwCaptured)) {
      PluginResult panw = registry.execute(
          "panwPrismaAirs.intercept",
          requestContext(),
          parameters(server.url("/scan")));

      assertThat(panw.verdict()).isTrue();
      assertThat(panwCaptured.path()).isEqualTo("/scan");
    }
  }

  private static Stream<Arguments> qualifireCheckPlugins() {
    return Stream.of(
        Arguments.of("qualifire.grounding", "grounding_check"),
        Arguments.of("qualifire.hallucinations", "hallucinations_check"),
        Arguments.of("qualifire.pii", "pii_check"));
  }

  private static Stream<Arguments> patronusJudgePlugins() {
    return Stream.of(
        Arguments.of("patronus.isConcise", "judge", "patronus:is-concise"),
        Arguments.of("patronus.isPolite", "judge", "patronus:is-polite"),
        Arguments.of("patronus.noApologies", "judge", "patronus:no-apologies"),
        Arguments.of("patronus.noGenderBias", "judge", "patronus:no-gender-bias"),
        Arguments.of("patronus.noRacialBias", "judge", "patronus:no-racial-bias"),
        Arguments.of("patronus.retrievalAnswerRelevance", "answer-relevance", null),
        Arguments.of("patronus.retrievalHallucination", "hallucination", "patronus:hallucination"),
        Arguments.of("patronus.toxicity", "toxicity", null));
  }

  private static Stream<Arguments> patronusPiiLikePlugins() {
    return Stream.of(
        Arguments.of("patronus.phi", "phi"),
        Arguments.of("patronus.pii", "pii"));
  }

  private static Stream<Arguments> promptfooPlugins() {
    return Stream.of(
        Arguments.of(
            "promptfoo.harm",
            """
            {"results":[{"flagged":true,"categories":{"hate":true}}]}
            """,
            "/harm"),
        Arguments.of(
            "promptfoo.guard",
            """
            {"results":[{"flagged":false,"categories":{"jailbreak":true}}]}
            """,
            "/guard"),
        Arguments.of(
            "promptfoo.pii",
            """
            {"results":[{"flagged":true,"categories":{"pii":true}}]}
            """,
            "/pii"));
  }

  private static HookContext requestContext() {
    return HookContext.forRequest(
        "",
        Map.of(
            "prompt", "clean prompt",
            "messages", List.of(Map.of("role", "user", "content", "clean prompt"))),
        Map.of(),
        Map.of("x-modelgate-trace-id", "trace-1"),
        "chatComplete");
  }

  private static HookContext requestResponseContext() {
    return HookContext.forRequest(
        "clean response",
        Map.of(
            "prompt", "clean prompt",
            "messages", List.of(Map.of("role", "user", "content", "clean prompt"))),
        Map.of(),
        Map.of("x-modelgate-trace-id", "trace-1"),
        "chatComplete");
  }

  private static HookContext responseHookContext() {
    return HookContext.forResponse(
        "clean response",
        Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", "clean response")))));
  }

  private static HookContext systemMessageContext() {
    return HookContext.forRequest(
        "",
        Map.of("messages", List.of(
            Map.of("role", "system", "content", "Existing policy"),
            Map.of("role", "user", "content", "clean prompt"))),
        Map.of(),
        Map.of(),
        "chatComplete");
  }

  private static HookContext toolUseQualityContext() {
    return HookContext.forRequest(
        "tool answer",
        Map.of(
            "messages", List.of(Map.of("role", "user", "content", "use a tool")),
            "tools", List.of(Map.of(
                "type", "function",
                "function", Map.of(
                    "name", "lookup",
                    "description", "Lookup facts",
                    "parameters", Map.of("type", "object"))))),
        Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", "tool answer")))),
        Map.of(),
        Map.of(),
        "chatComplete");
  }

  private static Map<String, Object> parameters(String baseUrl) {
    return parameters(baseUrl, Map.of());
  }

  private static Map<String, Object> parameters(String baseUrl, Map<String, ?> overrides) {
    Map<String, Object> credentials = new LinkedHashMap<>();
    credentials.put("apiKey", "test-key");
    credentials.put("AIRS_API_KEY", "test-key");
    credentials.put("application", "test-app");
    credentials.put("contentSafety", Map.of("resourceName", "modelgate-test", "apiKey", "test-key", "customHost", baseUrl));
    credentials.put("pii", Map.of("resourceName", "modelgate-test", "apiKey", "test-key", "customHost", baseUrl));

    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("baseUrl", baseUrl);
    parameters.put("credentials", credentials);
    parameters.put("categories", List.of("hate", "sexual", "pii"));
    parameters.put("language", List.of("en"));
    parameters.put("scanners", List.of("pii"));
    parameters.put("projectID", "project-1");
    parameters.put("projectId", "project-1");
    parameters.put("profile", "judge:custom");
    parameters.putAll(overrides);
    return parameters;
  }

  private static Map<String, Object> crowdstrikeParameters(String baseUrl) {
    Map<String, Object> credentials = new LinkedHashMap<>();
    credentials.put("apiKey", "test-key");
    credentials.put("baseUrl", baseUrl);
    return Map.of("credentials", credentials);
  }

  private record RunningServer(HttpServer server, int port) implements AutoCloseable {
    static RunningServer start(CapturedExchange captured) throws Exception {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> {
        captured.path.set(exchange.getRequestURI().getPath());
        captured.body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        captured.headers.set(new LinkedHashMap<>());
        exchange.getRequestHeaders().forEach((key, values) -> captured.headers.get().put(key.toLowerCase(), values.isEmpty() ? "" : values.getFirst()));
        byte[] response = captured.responseJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
      });
      server.start();
      return new RunningServer(server, server.getAddress().getPort());
    }

    String url(String path) {
      return "http://127.0.0.1:" + port + path;
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static final class CapturedExchange {
    private final String responseJson;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> headers = new AtomicReference<>(Map.of());

    private CapturedExchange(String responseJson) {
      this.responseJson = responseJson;
    }

    String path() {
      return path.get();
    }

    String body() {
      return body.get();
    }

    String header(String name) {
      return headers.get().get(name.toLowerCase());
    }
  }
}
