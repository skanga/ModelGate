package com.modelgate.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AnthropicMessageTransformerTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AnthropicMessageTransformer transformer = new AnthropicMessageTransformer(objectMapper);

  @Test
  void transformsOpenAiChatRequestToAnthropicMessagesRequest() throws Exception {
    String request = """
        {
          "model": "claude-3-5-sonnet-latest",
          "messages": [
            {"role": "system", "content": "Be terse."},
            {"role": "user", "content": "Hello"},
            {"role": "assistant", "content": "Hi"},
            {"role": "user", "content": [{"type": "text", "text": "Again"}]}
          ],
          "max_tokens": 64,
          "temperature": 0.2,
          "top_p": 0.9,
          "stop": ["END"]
        }
        """;

    JsonNode transformed = objectMapper.readTree(transformer.transformRequest(request));

    assertThat(transformed.path("model").asText()).isEqualTo("claude-3-5-sonnet-latest");
    assertThat(transformed.path("max_tokens").asInt()).isEqualTo(64);
    assertThat(transformed.path("temperature").asDouble()).isEqualTo(0.2);
    assertThat(transformed.path("top_p").asDouble()).isEqualTo(0.9);
    assertThat(transformed.path("stop_sequences").get(0).asText()).isEqualTo("END");
    assertThat(transformed.path("system").get(0).path("type").asText()).isEqualTo("text");
    assertThat(transformed.path("system").get(0).path("text").asText()).isEqualTo("Be terse.");
    assertThat(transformed.path("messages")).hasSize(3);
    assertThat(transformed.path("messages").get(0).path("role").asText()).isEqualTo("user");
    assertThat(transformed.path("messages").get(0).path("content").asText()).isEqualTo("Hello");
    assertThat(transformed.path("messages").get(1).path("content").get(0).path("type").asText()).isEqualTo("text");
    assertThat(transformed.path("messages").get(1).path("content").get(0).path("text").asText()).isEqualTo("Hi");
    assertThat(transformed.path("messages").get(2).path("content").get(0).path("text").asText()).isEqualTo("Again");
  }

  @Test
  void transformsDeveloperMessagesIntoAnthropicSystemMessages() throws Exception {
    String request = """
        {
          "model": "claude-3-5-sonnet-latest",
          "messages": [
            {"role": "developer", "content": "Follow policy."},
            {"role": "user", "content": "Hello"}
          ]
        }
        """;

    JsonNode transformed = objectMapper.readTree(transformer.transformRequest(request));

    assertThat(transformed.path("system").get(0).path("text").asText()).isEqualTo("Follow policy.");
    assertThat(transformed.path("messages")).hasSize(1);
    assertThat(transformed.has("max_tokens")).isFalse();
  }

  @Test
  void preservesSystemTextBlockCacheControl() throws Exception {
    String request = """
        {
          "messages": [
            {
              "role": "system",
              "content": [
                {"type": "text", "text": "Policy", "cache_control": {"type": "ephemeral"}}
              ]
            },
            {"role": "user", "content": "Hello"}
          ]
        }
        """;

    JsonNode system = objectMapper.readTree(transformer.transformRequest(request))
        .path("system").get(0);

    assertThat(system.path("text").asText()).isEqualTo("Policy");
    assertThat(system.path("cache_control").path("type").asText()).isEqualTo("ephemeral");
  }

  @Test
  void preservesSystemArrayMessageLevelCacheControl() throws Exception {
    String request = """
        {
          "messages": [
            {
              "role": "system",
              "cache_control": {"type": "ephemeral"},
              "content": [
                {"type": "text", "text": "Policy"}
              ]
            },
            {"role": "user", "content": "Hello"}
          ]
        }
        """;

    JsonNode system = objectMapper.readTree(transformer.transformRequest(request))
        .path("system").get(0);

    assertThat(system.path("text").asText()).isEqualTo("Policy");
    assertThat(system.path("cache_control").path("type").asText()).isEqualTo("ephemeral");
  }

  @Test
  void preservesSystemStringMessageCacheControl() throws Exception {
    String request = """
        {
          "messages": [
            {
              "role": "system",
              "content": "Policy",
              "cache_control": {"type": "ephemeral"}
            },
            {"role": "user", "content": "Hello"}
          ]
        }
        """;

    JsonNode system = objectMapper.readTree(transformer.transformRequest(request))
        .path("system").get(0);

    assertThat(system.path("text").asText()).isEqualTo("Policy");
    assertThat(system.path("cache_control").path("type").asText()).isEqualTo("ephemeral");
  }

  @Test
  void transformsOpenAiUserImageAndFilePartsToAnthropicBlocks() throws Exception {
    String request = """
        {
          "messages": [
            {
              "role": "user",
              "content": [
                {"type": "text", "text": "Inspect", "cache_control": {"type": "ephemeral"}},
                {"type": "image_url", "image_url": {"url": "https://example.com/cat.png"}},
                {"type": "image_url", "image_url": {"url": "data:application/pdf;base64,JVBERi0="}},
                {"type": "file", "file": {"file_url": "https://example.com/doc.pdf"}},
                {"type": "file", "file": {"file_data": "plain text", "mime_type": "text/plain"}}
              ]
            }
          ]
        }
        """;

    JsonNode content = objectMapper.readTree(transformer.transformRequest(request))
        .path("messages").get(0).path("content");

    assertThat(content).hasSize(5);
    assertThat(content.get(0).path("type").asText()).isEqualTo("text");
    assertThat(content.get(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
    assertThat(content.get(1).path("type").asText()).isEqualTo("image");
    assertThat(content.get(1).path("source").path("type").asText()).isEqualTo("url");
    assertThat(content.get(1).path("source").path("url").asText()).isEqualTo("https://example.com/cat.png");
    assertThat(content.get(2).path("type").asText()).isEqualTo("document");
    assertThat(content.get(2).path("source").path("type").asText()).isEqualTo("base64");
    assertThat(content.get(2).path("source").path("media_type").asText()).isEqualTo("application/pdf");
    assertThat(content.get(2).path("source").path("data").asText()).isEqualTo("JVBERi0=");
    assertThat(content.get(3).path("type").asText()).isEqualTo("document");
    assertThat(content.get(3).path("source").path("url").asText()).isEqualTo("https://example.com/doc.pdf");
    assertThat(content.get(4).path("type").asText()).isEqualTo("document");
    assertThat(content.get(4).path("source").path("type").asText()).isEqualTo("text");
    assertThat(content.get(4).path("source").path("data").asText()).isEqualTo("plain text");
  }

  @Test
  void transformsAssistantToolCallsToAnthropicToolUseBlocks() throws Exception {
    String request = """
        {
          "messages": [
            {
              "role": "assistant",
              "content": "I will call a tool.",
              "tool_calls": [
                {
                  "id": "call_123",
                  "type": "function",
                  "function": {
                    "name": "lookup",
                    "arguments": "{\\"city\\":\\"Paris\\"}"
                  }
                }
              ]
            }
          ]
        }
        """;

    JsonNode content = objectMapper.readTree(transformer.transformRequest(request))
        .path("messages").get(0).path("content");

    assertThat(content.get(0).path("type").asText()).isEqualTo("text");
    assertThat(content.get(0).path("text").asText()).isEqualTo("I will call a tool.");
    assertThat(content.get(1).path("type").asText()).isEqualTo("tool_use");
    assertThat(content.get(1).path("id").asText()).isEqualTo("call_123");
    assertThat(content.get(1).path("name").asText()).isEqualTo("lookup");
    assertThat(content.get(1).path("input").path("city").asText()).isEqualTo("Paris");
  }

  @Test
  void assistantContentBlocksWinOverContentAndExistingToolUseBlocksAreRebuilt() throws Exception {
    String request = """
        {
          "messages": [
            {
              "role": "assistant",
              "content": "ignored",
              "content_blocks": [
                {"type": "text", "text": "Use this block."},
                {"type": "tool_use", "id": "old", "name": "stale", "input": {"x": 1}}
              ],
              "tool_calls": [
                {
                  "id": "call_new",
                  "type": "function",
                  "function": {
                    "name": "lookup",
                    "arguments": ""
                  }
                }
              ]
            }
          ]
        }
        """;

    JsonNode content = objectMapper.readTree(transformer.transformRequest(request))
        .path("messages").get(0).path("content");

    assertThat(content).hasSize(2);
    assertThat(content.get(0).path("type").asText()).isEqualTo("text");
    assertThat(content.get(0).path("text").asText()).isEqualTo("Use this block.");
    assertThat(content.get(1).path("type").asText()).isEqualTo("tool_use");
    assertThat(content.get(1).path("id").asText()).isEqualTo("call_new");
    assertThat(content.get(1).path("input").isObject()).isTrue();
    assertThat(content.get(1).path("input")).isEmpty();
  }

  @Test
  void preservesToolCallCacheControlOnAnthropicToolUseBlocks() throws Exception {
    String request = """
        {
          "messages": [
            {
              "role": "assistant",
              "tool_calls": [
                {
                  "id": "call_123",
                  "type": "function",
                  "cache_control": {"type": "ephemeral"},
                  "function": {
                    "name": "lookup",
                    "arguments": "{\\"city\\":\\"Paris\\"}"
                  }
                }
              ]
            }
          ]
        }
        """;

    JsonNode toolUse = objectMapper.readTree(transformer.transformRequest(request))
        .path("messages").get(0).path("content").get(0);

    assertThat(toolUse.path("cache_control").path("type").asText()).isEqualTo("ephemeral");
  }

  @Test
  void transformsToolMessagesToAnthropicToolResultBlocks() throws Exception {
    String request = """
        {
          "messages": [
            {
              "role": "tool",
              "tool_call_id": "call_123",
              "content": "{\\"temperature\\":18}"
            }
          ]
        }
        """;

    JsonNode message = objectMapper.readTree(transformer.transformRequest(request))
        .path("messages").get(0);

    assertThat(message.path("role").asText()).isEqualTo("user");
    assertThat(message.path("content").get(0).path("type").asText()).isEqualTo("tool_result");
    assertThat(message.path("content").get(0).path("tool_use_id").asText()).isEqualTo("call_123");
    assertThat(message.path("content").get(0).path("content").asText()).isEqualTo("{\"temperature\":18}");
  }

  @Test
  void transformsOpenAiToolsAndToolChoiceToAnthropicToolsAndToolChoice() throws Exception {
    String request = """
        {
          "messages": [{"role": "user", "content": "weather"}],
          "tools": [
            {
              "type": "function",
              "function": {
                "name": "lookup",
                "description": "Look up weather",
                "parameters": {
                  "type": "object",
                  "properties": {"city": {"type": "string"}},
                  "required": ["city"]
                }
              }
            }
          ],
          "tool_choice": {
            "type": "function",
            "function": {"name": "lookup"}
          }
        }
        """;

    JsonNode transformed = objectMapper.readTree(transformer.transformRequest(request));

    assertThat(transformed.path("tools").get(0).path("name").asText()).isEqualTo("lookup");
    assertThat(transformed.path("tools").get(0).path("description").asText()).isEqualTo("Look up weather");
    assertThat(transformed.path("tools").get(0).path("input_schema").path("required").get(0).asText()).isEqualTo("city");
    assertThat(transformed.path("tool_choice").path("type").asText()).isEqualTo("tool");
    assertThat(transformed.path("tool_choice").path("name").asText()).isEqualTo("lookup");
  }

  @Test
  void preservesToolDefinitionCacheControlAndAdvancedAnthropicToolFields() throws Exception {
    String request = """
        {
          "messages": [{"role": "user", "content": "weather"}],
          "tools": [
            {
              "type": "function",
              "cache_control": {"type": "ephemeral"},
              "function": {
                "name": "lookup",
                "description": "Look up weather",
                "parameters": {"type": "object", "properties": {}},
                "defer_loading": true,
                "allowed_callers": ["code_execution_20250825"],
                "input_examples": [{"city": "Paris"}]
              }
            }
          ]
        }
        """;

    JsonNode tool = objectMapper.readTree(transformer.transformRequest(request))
        .path("tools").get(0);

    assertThat(tool.path("cache_control").path("type").asText()).isEqualTo("ephemeral");
    assertThat(tool.path("defer_loading").asBoolean()).isTrue();
    assertThat(tool.path("allowed_callers").get(0).asText()).isEqualTo("code_execution_20250825");
    assertThat(tool.path("input_examples").get(0).path("city").asText()).isEqualTo("Paris");
  }

  @Test
  void transformsStringToolChoicesToAnthropicToolChoices() throws Exception {
    assertThat(toolChoiceType("required")).isEqualTo("any");
    assertThat(toolChoiceType("auto")).isEqualTo("auto");
    assertThat(toolChoiceType("none")).isEqualTo("none");
  }

  @Test
  void transformsAnthropicMessageResponseToOpenAiChatCompletionResponse() throws Exception {
    String response = """
        {
          "id": "msg_123",
          "type": "message",
          "role": "assistant",
          "model": "claude-3-5-sonnet-latest",
          "content": [
            {"type": "text", "text": "Hello "},
            {"type": "text", "text": "there"}
          ],
          "stop_reason": "end_turn",
          "usage": {"input_tokens": 3, "output_tokens": 4}
        }
        """;

    JsonNode transformed = objectMapper.readTree(transformer.transformResponse(response));

    assertThat(transformed.path("id").asText()).isEqualTo("msg_123");
    assertThat(transformed.path("object").asText()).isEqualTo("chat.completion");
    assertThat(transformed.path("provider").asText()).isEqualTo("anthropic");
    assertThat(transformed.path("choices").get(0).path("message").path("role").asText()).isEqualTo("assistant");
    assertThat(transformed.path("choices").get(0).path("message").path("content").asText()).isEqualTo("Hello there");
    assertThat(transformed.path("choices").get(0).path("finish_reason").asText()).isEqualTo("stop");
    assertThat(transformed.path("usage").path("prompt_tokens").asInt()).isEqualTo(3);
    assertThat(transformed.path("usage").path("completion_tokens").asInt()).isEqualTo(4);
    assertThat(transformed.path("usage").path("total_tokens").asInt()).isEqualTo(7);
  }

  @Test
  void transformsAnthropicToolUseResponseToOpenAiToolCalls() throws Exception {
    String response = """
        {
          "id": "msg_123",
          "type": "message",
          "role": "assistant",
          "model": "claude-3-5-sonnet-latest",
          "content": [
            {
              "type": "tool_use",
              "id": "toolu_123",
              "name": "lookup",
              "input": {"city": "Paris"}
            }
          ],
          "stop_reason": "tool_use",
          "usage": {"input_tokens": 3, "output_tokens": 4}
        }
        """;

    JsonNode transformed = objectMapper.readTree(transformer.transformResponse(response));
    JsonNode message = transformed.path("choices").get(0).path("message");
    JsonNode toolCall = message.path("tool_calls").get(0);

    assertThat(message.path("content").asText()).isEmpty();
    assertThat(toolCall.path("id").asText()).isEqualTo("toolu_123");
    assertThat(toolCall.path("type").asText()).isEqualTo("function");
    assertThat(toolCall.path("function").path("name").asText()).isEqualTo("lookup");
    assertThat(toolCall.path("function").path("arguments").asText()).isEqualTo("{\"city\":\"Paris\"}");
    assertThat(transformed.path("choices").get(0).path("finish_reason").asText()).isEqualTo("tool_calls");
  }

  @Test
  void transformsAnthropicErrorResponseToOpenAiErrorResponse() throws Exception {
    String response = """
        {
          "type": "error",
          "error": {
            "type": "invalid_request_error",
            "message": "bad request"
          }
        }
        """;

    JsonNode transformed = objectMapper.readTree(transformer.transformResponse(response));

    assertThat(transformed.path("provider").asText()).isEqualTo("anthropic");
    assertThat(transformed.path("error").path("message").asText()).isEqualTo("anthropic error: bad request");
    assertThat(transformed.path("error").path("type").asText()).isEqualTo("invalid_request_error");
    assertThat(transformed.path("error").path("param").isNull()).isTrue();
    assertThat(transformed.path("error").path("code").isNull()).isTrue();
  }

  @Test
  void mapsAnthropicFinishReasonsToOpenAiFinishReasons() throws Exception {
    assertThat(finishReasonFor("end_turn")).isEqualTo("stop");
    assertThat(finishReasonFor("stop_sequence")).isEqualTo("stop");
    assertThat(finishReasonFor("pause_turn")).isEqualTo("stop");
    assertThat(finishReasonFor("max_tokens")).isEqualTo("length");
    assertThat(finishReasonFor("tool_use")).isEqualTo("tool_calls");
    assertThat(finishReasonFor("unknown")).isEqualTo("stop");
  }

  private String finishReasonFor(String stopReason) throws Exception {
    String response = """
        {
          "id": "msg_123",
          "model": "claude-3-5-sonnet-latest",
          "content": [],
          "stop_reason": "%s",
          "usage": {"input_tokens": 0, "output_tokens": 0}
        }
        """.formatted(stopReason);
    return objectMapper.readTree(transformer.transformResponse(response))
        .path("choices").get(0).path("finish_reason").asText();
  }

  private String toolChoiceType(String toolChoice) throws Exception {
    String request = """
        {
          "messages": [{"role": "user", "content": "weather"}],
          "tool_choice": "%s"
        }
        """.formatted(toolChoice);
    return objectMapper.readTree(transformer.transformRequest(request))
        .path("tool_choice").path("type").asText();
  }
}
