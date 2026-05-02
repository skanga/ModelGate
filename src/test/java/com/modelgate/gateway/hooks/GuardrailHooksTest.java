package com.modelgate.gateway.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.modelgate.gateway.plugins.DefaultPluginRegistry;
import com.modelgate.gateway.plugins.PluginResult;
import com.modelgate.gateway.plugins.TransformedData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GuardrailHooksTest {
  private final GuardrailHooks hooks = new GuardrailHooks(DefaultPluginRegistry.create());

  @Test
  void deniesWhenGuardrailCheckFailsAndDenyIsTrue() {
    HookDecision decision = hooks.evaluateInput(
        "{\"messages\":[{\"role\":\"user\",\"content\":\"my secret\"}]}",
        List.of(Map.of(
            "id", "input-deny",
            "default.contains", Map.of("operator", "none", "words", List.of("secret")),
            "deny", true)));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.status()).isEqualTo(446);
    assertThat(decision.body()).contains("hooks_failed");
    assertThat(decision.body()).contains("input-deny");
    assertThat(decision.body()).contains("default.contains");
    assertThat(decision.body()).contains("\"verdict\":false");
    assertThat(decision.transformedBody()).isNull();
  }

  @Test
  void appliesInputMutatorBeforeProviderRequest() {
    HookDecision decision = hooks.evaluateInput(
        "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
        List.of(Map.of(
            "id", "input-transform",
            "default.addPrefix", Map.of("prefix", "Policy: "))));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.transformedBody()).contains("Policy: hello");
    assertThat(decision.beforeRequestHooksResult()).hasSize(1);
    assertThat(decision.beforeRequestHooksResult().getFirst().toString()).contains("input-transform");
    assertThat(decision.beforeRequestHooksResult().getFirst().toString()).contains("default.addPrefix");
  }

  @Test
  void evaluatesOutputTextFromChatCompletionResponse() {
    HookDecision decision = hooks.evaluateOutput(
        "{\"choices\":[{\"message\":{\"content\":\"Apple\"}}]}",
        List.of(Map.of(
            "id", "output-pass",
            "default.contains", Map.of("operator", "any", "words", List.of("Apple")))));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.afterRequestHooksResult()).hasSize(1);
    assertThat(decision.afterRequestHooksResult().getFirst().toString()).contains("output-pass");
    assertThat(decision.afterRequestHooksResult().getFirst().toString()).contains("default.contains");
  }

  @Test
  void evaluatesOutputTextFromAnthropicMessagesResponse() {
    HookDecision decision = hooks.evaluateOutput(
        "{\"content\":[{\"type\":\"text\",\"text\":\"Apple\"}]}",
        List.of(Map.of(
            "default.contains", Map.of("operator", "none", "words", List.of("Apple")),
            "deny", true)),
        "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
        Map.of(),
        "messages");

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.status()).isEqualTo(446);
    assertThat(decision.body()).contains("after_request_hooks");
  }

  @Test
  void outputGuardrailCanDenyWithRegexMatch() {
    HookDecision decision = hooks.evaluateOutput(
        "{\"choices\":[{\"message\":{\"content\":\"The response leaked a secret token.\"}}]}",
        List.of(Map.of(
            "id", "output-deny",
            "default.regexMatch", Map.of("rule", "secret\\s+token", "not", true),
            "deny", true)));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.status()).isEqualTo(446);
    assertThat(decision.body()).contains("hooks_failed");
    assertThat(decision.body()).contains("after_request_hooks");
    assertThat(decision.body()).contains("output-deny");
    assertThat(decision.body()).contains("default.regexMatch");
    assertThat(decision.body()).contains("matchDetails");
  }

  @Test
  void appliesOutputMutatorToProviderResponse() {
    GuardrailHooks mutatingHooks = new GuardrailHooks((pluginId, context, parameters) ->
        new PluginResult(true, null, Map.of(), true, TransformedData.forResponse(Map.of(
            "choices", List.of(Map.of("message", Map.of("content", "redacted output")))))));

    HookDecision decision = mutatingHooks.evaluateOutput(
        "{\"choices\":[{\"message\":{\"content\":\"raw output\"}}]}",
        List.of(Map.of("partner.redactor", Map.of())));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.transformedBody()).contains("redacted output");
  }

  @Test
  void regexReplaceOutputGuardrailTransformsProviderResponse() {
    HookDecision decision = hooks.evaluateOutput(
        "{\"choices\":[{\"message\":{\"content\":\"The response leaked secret token.\"}}]}",
        List.of(Map.of("default.regexReplace", Map.of("rule", "secret token", "redactText", "[REDACTED]"))),
        "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
        Map.of(),
        "chatComplete");

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.transformedBody()).contains("[REDACTED]");
    assertThat(decision.transformedBody()).doesNotContain("secret token");
  }

  @Test
  void inputGuardrailReceivesMetadataAndRequestTypeFromHeaders() {
    HookDecision decision = hooks.evaluateInput(
        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
        List.of(Map.of(
            "default.requiredMetadataKeys", Map.of("metadataKeys", List.of("tenant"), "operator", "all"),
            "default.allowedRequestTypes", Map.of("allowedTypes", List.of("chatComplete")),
            "default.modelwhitelist", Map.of("models", List.of("gpt-4o")),
            "deny", true)),
        Map.of("x-modelgate-metadata", "{\"tenant\":\"acme\"}"),
        "chatComplete");

    assertThat(decision.allowed()).isTrue();
  }

  @Test
  void inputGuardrailCanDenyWhenRequestTypeIsBlocked() {
    HookDecision decision = hooks.evaluateInput(
        "{\"model\":\"text-embedding-3-small\",\"input\":\"hello\"}",
        List.of(Map.of(
            "default.allowedRequestTypes", Map.of("blockedTypes", List.of("embed")),
            "deny", true)),
        Map.of(),
        "embed");

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.status()).isEqualTo(446);
  }

  @Test
  void outputGuardrailSkipsBeforeRequestOnlyRequiredMetadataKeys() {
    HookDecision decision = hooks.evaluateOutput(
        "{\"choices\":[{\"message\":{\"content\":\"safe output\"}}]}",
        List.of(Map.of(
            "default.requiredMetadataKeys", Map.of("metadataKeys", List.of("tenant"), "operator", "all"),
            "deny", true)),
        "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
        Map.of(),
        "chatComplete");

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.transformedBody()).isNull();
  }

  @Test
  void executesChecksArrayWithDefaultPrefixAndSkipsDisabledChecks() {
    HookDecision decision = hooks.evaluateInput(
        "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
        List.of(Map.of(
            "id", "checks-array",
            "checks", List.of(
                Map.of("id", "contains", "parameters", Map.of("operator", "any", "words", List.of("hello"))),
                Map.of(
                    "id", "contains",
                    "parameters", Map.of("operator", "none", "words", List.of("hello")),
                    "is_enabled", false)),
            "deny", true)),
        Map.of(),
        "chatComplete");

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.beforeRequestHooksResult()).hasSize(1);
    assertThat(decision.beforeRequestHooksResult().getFirst().toString()).contains("default.contains");
    assertThat(decision.beforeRequestHooksResult().getFirst().toString()).doesNotContain("is_enabled=false");
  }

  @Test
  void asyncInputGuardrailsDoNotBlockOrReturnHookResults() {
    HookDecision decision = hooks.evaluateInput(
        "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
        List.of(Map.of(
            "id", "async-deny",
            "async", true,
            "default.contains", Map.of("operator", "none", "words", List.of("hello")),
            "deny", true)),
        Map.of(),
        "chatComplete");

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.beforeRequestHooksResult()).isEmpty();
    assertThat(decision.body()).isNull();
  }

  @Test
  void hookResultsIncludeConfiguredSuccessAndFailureFeedback() {
    HookDecision success = hooks.evaluateInput(
        "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
        List.of(Map.of(
            "id", "success-feedback",
            "default.contains", Map.of("operator", "any", "words", List.of("hello")),
            "on_success", Map.of(
                "feedback", Map.of(
                    "value", 1,
                    "weight", 0.75,
                    "metadata", Map.of("label", "accepted"))))),
        Map.of(),
        "chatComplete");
    HookDecision failure = hooks.evaluateInput(
        "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
        List.of(Map.of(
            "id", "failure-feedback",
            "default.contains", Map.of("operator", "none", "words", List.of("hello")),
            "on_fail", Map.of(
                "feedback", Map.of(
                    "value", -1,
                    "weight", 0.25,
                    "metadata", Map.of("label", "blocked"))))),
        Map.of(),
        "chatComplete");

    assertThat(success.beforeRequestHooksResult().getFirst().get("feedback").toString())
        .contains("value=1", "weight=0.75", "label=accepted", "successfulChecks=default.contains");
    assertThat(failure.beforeRequestHooksResult().getFirst().get("feedback").toString())
        .contains("value=-1", "weight=0.25", "label=blocked", "failedChecks=default.contains");
  }
}
