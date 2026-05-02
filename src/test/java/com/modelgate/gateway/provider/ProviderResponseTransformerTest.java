package com.modelgate.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class ProviderResponseTransformerTest {
  @Test
  void transformsAnthropicChatCompletionResponseWhenEndpointHasQueryString() {
    ProviderResponse response = new ProviderResponse(
        200,
        """
            {
              "id": "msg_123",
              "model": "claude-3-5-haiku-20241022",
              "content": [{"type": "text", "text": "hello"}],
              "usage": {"input_tokens": 1, "output_tokens": 2}
            }
            """,
        Map.of("content-type", "application/json"),
        0);

    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "anthropic",
        "/v1/chat/completions?include=usage",
        response);

    assertThat(transformed.body()).contains("\"object\":\"chat.completion\"");
    assertThat(transformed.body()).contains("\"provider\":\"anthropic\"");
  }

  @Test
  void transformsWorkersAiChatResponseToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "workers-ai",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            "{\"result\":{\"response\":\"hello\"},\"success\":true,\"errors\":[],\"messages\":[]}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"object\":\"chat.completion\"");
    assertThat(transformed.body()).contains("\"provider\":\"workers-ai\"");
    assertThat(transformed.body()).contains("\"content\":\"hello\"");
  }

  @Test
  void transformsWorkersAiChatStreamChunksToOpenAiShape() throws Exception {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "workers-ai",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                data: {"response":"hello"}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String body = new String(transformed.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(transformed.streaming()).isTrue();
    assertThat(body).contains("\"object\":\"chat.completion.chunk\"");
    assertThat(body).contains("\"provider\":\"workers-ai\"");
    assertThat(body).contains("\"content\":\"hello\"");
    assertThat(body).contains("data: [DONE]");
    assertThat(body).doesNotContain("\"response\":\"hello\"");
  }

  @Test
  void annotatesOpenAiCompatibleChatStreamChunks() throws Exception {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "deepseek",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                data: {"id":"cmpl-1","object":"chat.completion.chunk","created":1710000000,"model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"hello"},"finish_reason":null}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String body = new String(transformed.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(body).contains("\"provider\":\"deepseek\"");
    assertThat(body).contains("\"content\":\"hello\"");
    assertThat(body).contains("\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}");
    assertThat(body).contains("data: [DONE]");
  }

  @Test
  void enrichesOpenRouterChatStreamReasoningChunks() throws Exception {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "openrouter",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                data: {"id":"or-1","object":"chat.completion.chunk","created":1710000000,"model":"openrouter/auto","choices":[{"index":0,"delta":{"reasoning":"thinking","content":"answer"},"finish_reason":null}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String body = new String(transformed.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(body).contains("\"provider\":\"openrouter\"");
    assertThat(body).contains("\"content_blocks\":[{\"index\":0,\"delta\":{\"thinking\":\"thinking\"}}");
    assertThat(body).contains("{\"index\":0,\"delta\":{\"text\":\"answer\"}}");
  }

  @Test
  void appendsDoneForPerplexityFinalChatStreamChunk() throws Exception {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "perplexity-ai",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                data: {"id":"pplx-1","object":"chat.completion.chunk","created":1710000000,"model":"sonar","citations":["https://example.test"],"choices":[{"index":0,"delta":{"role":"assistant","content":"answer"},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String body = new String(transformed.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(body).contains("\"provider\":\"perplexity-ai\"");
    assertThat(body).contains("\"citations\":[\"https://example.test\"]");
    assertThat(body).contains("\"finish_reason\":\"stop\"");
    assertThat(body).endsWith("data: [DONE]\n\n");
  }

  @Test
  void dropsHuggingFacePingEventsFromChatStream() throws Exception {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "huggingface",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                event: ping

                data: {"object":"chat.completion.chunk","created":1710000000,"model":"hf-model","choices":[{"index":0,"delta":{"content":"hello"},"finish_reason":null}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String body = new String(transformed.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(body).doesNotContain("event: ping");
    assertThat(body).contains("\"provider\":\"huggingface\"");
    assertThat(body).contains("\"id\":\"modelgate-");
    assertThat(body).contains("data: [DONE]");
  }

  @Test
  void transformsCohereChatStreamEventsToOpenAiChunks() throws Exception {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "cohere",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                event: message-start
                data: {"type":"message-start","id":"co-stream-1"}

                event: content-delta
                data: {"type":"content-delta","index":0,"delta":{"message":{"content":{"text":"hello"}}}}

                event: message-end
                data: {"type":"message-end","delta":{"finish_reason":"COMPLETE","usage":{"tokens":{"input_tokens":2,"output_tokens":3}}}}

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String body = new String(transformed.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(body).contains("\"id\":\"co-stream-1\"");
    assertThat(body).contains("\"provider\":\"cohere\"");
    assertThat(body).contains("\"content\":\"hello\"");
    assertThat(body).contains("\"finish_reason\":\"stop\"");
    assertThat(body).contains("\"prompt_tokens\":2");
    assertThat(body).contains("\"completion_tokens\":3");
    assertThat(body).contains("data: [DONE]");
  }

  @Test
  void transformsGoogleGeminiChatStreamChunksToOpenAiShape() throws Exception {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "google",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                data: {"modelVersion":"gemini-1.5-pro","candidates":[{"index":0,"content":{"parts":[{"text":"thinking","thought":true},{"text":"answer"}]},"finishReason":"STOP","groundingMetadata":{"webSearchQueries":["q"]}}],"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":3,"totalTokenCount":5,"thoughtsTokenCount":1,"cachedContentTokenCount":1}}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String body = new String(transformed.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(body).contains("\"object\":\"chat.completion.chunk\"");
    assertThat(body).contains("\"provider\":\"google\"");
    assertThat(body).contains("\"model\":\"gemini-1.5-pro\"");
    assertThat(body).contains("\"content\":\"answer\"");
    assertThat(body).contains("\"content_blocks\":[{\"index\":0,\"delta\":{\"thinking\":\"thinking\"}}");
    assertThat(body).contains("\"finish_reason\":\"stop\"");
    assertThat(body).contains("\"reasoning_tokens\":1");
    assertThat(body).contains("\"cached_tokens\":1");
    assertThat(body).contains("data: [DONE]");
  }

  @Test
  void transformsVertexGeminiChatStreamChunksToOpenAiShape() throws Exception {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "vertex-ai",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                data: {"modelVersion":"gemini-1.5-pro","candidates":[{"content":{"parts":[{"functionCall":{"name":"lookup","args":{"city":"NYC"}}}]},"finishReason":"STOP"}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String body = new String(transformed.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(body).contains("\"provider\":\"vertex-ai\"");
    assertThat(body).contains("\"tool_calls\":[{\"index\":0,\"id\":\"modelgate-");
    assertThat(body).contains("\"name\":\"lookup\"");
    assertThat(body).contains("\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"");
    assertThat(body).contains("\"finish_reason\":\"stop\"");
    assertThat(body).contains("data: [DONE]");
  }

  @Test
  void transformsBedrockConverseStreamChunksToOpenAiShape() throws Exception {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "bedrock",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                {"delta":{"text":"hello"},"contentBlockIndex":0}
                {"stopReason":"end_turn"}
                {"usage":{"inputTokens":2,"outputTokens":3,"totalTokens":5,"cacheReadInputTokens":1}}
                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String body = new String(transformed.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(body).contains("\"provider\":\"bedrock\"");
    assertThat(body).contains("\"content\":\"hello\"");
    assertThat(body).contains("\"content_blocks\":[{\"index\":0,\"delta\":{\"text\":\"hello\"}}]");
    assertThat(body).contains("\"finish_reason\":\"stop\"");
    assertThat(body).contains("\"prompt_tokens\":3");
    assertThat(body).contains("\"completion_tokens\":3");
    assertThat(body).contains("\"cached_tokens\":1");
    assertThat(body).contains("data: [DONE]");
  }

  @Test
  void transformsBedrockAwsEventStreamFramesToOpenAiShape() throws Exception {
    byte[] upstream = concat(
        awsEventStreamFrame(
            "contentBlockDelta",
            "{\"contentBlockDelta\":{\"contentBlockIndex\":0,\"delta\":{\"text\":\"hello\"}}}"),
        awsEventStreamFrame(
            "messageStop",
            "{\"messageStop\":{\"stopReason\":\"end_turn\"}}"),
        awsEventStreamFrame(
            "metadata",
            "{\"metadata\":{\"usage\":{\"inputTokens\":2,\"outputTokens\":3,\"totalTokens\":5,\"cacheReadInputTokens\":1}}}"));
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "bedrock",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream(upstream),
            Map.of("content-type", "application/vnd.amazon.eventstream"),
            0));

    String body = new String(transformed.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(body).contains("\"provider\":\"bedrock\"");
    assertThat(body).contains("\"content\":\"hello\"");
    assertThat(body).contains("\"content_blocks\":[{\"index\":0,\"delta\":{\"text\":\"hello\"}}]");
    assertThat(body).contains("\"finish_reason\":\"stop\"");
    assertThat(body).contains("\"prompt_tokens\":3");
    assertThat(body).contains("\"completion_tokens\":3");
    assertThat(body).contains("\"cached_tokens\":1");
    assertThat(body).contains("data: [DONE]");
  }

  @Test
  void transformsCohereChatResponseToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "cohere",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            """
                {
                  "id": "co-1",
                  "message": {"content": [{"type": "text", "text": "hello"}]},
                  "finish_reason": "COMPLETE",
                  "usage": {"tokens": {"input_tokens": 2, "output_tokens": 3}}
                }
                """,
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"id\":\"co-1\"");
    assertThat(transformed.body()).contains("\"provider\":\"cohere\"");
    assertThat(transformed.body()).contains("\"content\":\"hello\"");
    assertThat(transformed.body()).contains("\"prompt_tokens\":2");
    assertThat(transformed.body()).contains("\"completion_tokens\":3");
  }

  @Test
  void transformsCohereCompletionResponseToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "cohere",
        "/v1/completions",
        new ProviderResponse(
            200,
            """
                {
                  "id": "co-generate-1",
                  "generations": [
                    {"id": "gen-1", "text": "hello"},
                    {"id": "gen-2", "text": "there"}
                  ],
                  "prompt": "Hello"
                }
                """,
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"id\":\"co-generate-1\"");
    assertThat(transformed.body()).contains("\"object\":\"text_completion\"");
    assertThat(transformed.body()).contains("\"provider\":\"cohere\"");
    assertThat(transformed.body()).contains("\"text\":\"hello\"");
    assertThat(transformed.body()).contains("\"text\":\"there\"");
    assertThat(transformed.body()).contains("\"finish_reason\":\"length\"");
  }

  @Test
  void transformsCohereFileResponsesToOpenAiShape() {
    ProviderResponse list = ProviderResponseTransformer.transform(
        "cohere",
        "/v1/files",
        new ProviderResponse(
            200,
            """
                {
                  "datasets": [
                    {
                      "id": "dataset-1",
                      "name": "input.jsonl",
                      "created_at": "2026-04-28T10:15:30Z",
                      "dataset_type": "embed-input",
                      "validation_status": "validated",
                      "validation_error": ""
                    },
                    {
                      "id": "dataset-2",
                      "name": "bad.jsonl",
                      "created_at": "2026-04-28T10:16:30Z",
                      "dataset_type": "embed-input",
                      "validation_status": "failed",
                      "validation_error": "bad row"
                    }
                  ]
                }
                """,
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse retrieve = ProviderResponseTransformer.transform(
        "cohere",
        "/v1/files/dataset-1",
        new ProviderResponse(
            200,
            """
                {
                  "dataset": {
                    "id": "dataset-1",
                    "name": "input.jsonl",
                    "created_at": "2026-04-28T10:15:30Z",
                    "dataset_type": "embed-input",
                    "validation_status": "validated",
                    "validation_error": ""
                  }
                }
                """,
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse upload = ProviderResponseTransformer.transform(
        "cohere",
        "/v1/files",
        new ProviderResponse(200, "{\"id\":\"dataset-new\"}", Map.of("content-type", "application/json"), 0));

    assertThat(list.body()).contains("\"object\":\"list\"");
    assertThat(list.body()).contains("\"id\":\"dataset-1\"");
    assertThat(list.body()).contains("\"status\":\"processed\"");
    assertThat(list.body()).contains("\"id\":\"dataset-2\"");
    assertThat(list.body()).contains("\"status\":\"error\"");
    assertThat(list.body()).contains("\"status_details\":\"bad row\"");
    assertThat(retrieve.body()).contains("\"object\":\"file\"");
    assertThat(retrieve.body()).contains("\"filename\":\"input.jsonl\"");
    assertThat(upload.body()).contains("\"id\":\"dataset-new\"");
    assertThat(upload.body()).contains("\"status\":\"uploaded\"");
  }

  @Test
  void transformsCohereBatchResponsesToOpenAiShape() {
    ProviderResponse create = ProviderResponseTransformer.transform(
        "cohere",
        "/v1/batches",
        new ProviderResponse(200, "{\"job_id\":\"job-new\",\"meta\":{\"warnings\":[]}}", Map.of(), 0));
    ProviderResponse retrieve = ProviderResponseTransformer.transform(
        "cohere",
        "/v1/batches/job-1",
        new ProviderResponse(
            200,
            """
                {
                  "job_id": "job-1",
                  "created_at": "2026-04-28T10:15:30Z",
                  "status": "complete",
                  "input_dataset_id": "dataset-in",
                  "output_dataset_id": "dataset-out",
                  "model": "embed-v4.0",
                  "truncate": "END",
                  "name": "nightly",
                  "meta": {"warnings": []}
                }
                """,
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse list = ProviderResponseTransformer.transform(
        "cohere",
        "/v1/batches",
        new ProviderResponse(
            200,
            """
                {
                  "embed_jobs": [
                    {
                      "job_id": "job-1",
                      "created_at": "2026-04-28T10:15:30Z",
                      "status": "complete",
                      "input_dataset_id": "dataset-in",
                      "output_dataset_id": "dataset-out",
                      "model": "embed-v4.0",
                      "truncate": "END",
                      "name": "nightly",
                      "meta": {"warnings": []}
                    }
                  ]
                }
                """,
            Map.of("content-type", "application/json"),
            0));

    assertThat(create.body()).contains("\"id\":\"job-new\"");
    assertThat(create.body()).contains("\"object\":\"batch\"");
    assertThat(create.body()).contains("\"endpoint\":\"/v1/embed\"");
    assertThat(create.body()).contains("\"status\":\"in_progress\"");
    assertThat(retrieve.body()).contains("\"id\":\"job-1\"");
    assertThat(retrieve.body()).contains("\"input_file_id\":\"dataset-in\"");
    assertThat(retrieve.body()).contains("\"output_file_id\":\"dataset-out\"");
    assertThat(list.body()).contains("\"object\":\"list\"");
    assertThat(list.body()).contains("\"id\":\"job-1\"");
    assertThat(list.body()).contains("\"metadata\":{\"warnings\":[]}");
  }

  @Test
  void transformsBedrockConverseResponseToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "bedrock",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            """
                {
                  "output": {"message": {"content": [{"text": "hello"}]}},
                  "stopReason": "end_turn",
                  "usage": {"inputTokens": 2, "outputTokens": 3, "totalTokens": 5}
                }
                """,
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"provider\":\"bedrock\"");
    assertThat(transformed.body()).contains("\"content\":\"hello\"");
    assertThat(transformed.body()).contains("\"finish_reason\":\"stop\"");
    assertThat(transformed.body()).contains("\"total_tokens\":5");
  }

  @Test
  void transformsBedrockConverseToolUseResponseToOpenAiToolCalls() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "bedrock",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            """
                {
                  "output": {
                    "message": {
                      "content": [
                        {"text": "Checking"},
                        {"toolUse": {"toolUseId": "tool-1", "name": "lookup", "input": {"city": "NYC"}}}
                      ]
                    }
                  },
                  "stopReason": "tool_use",
                  "usage": {"inputTokens": 2, "outputTokens": 3, "totalTokens": 5}
                }
                """,
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"provider\":\"bedrock\"");
    assertThat(transformed.body()).contains("\"content\":\"Checking\"");
    assertThat(transformed.body()).contains("\"finish_reason\":\"tool_calls\"");
    assertThat(transformed.body()).contains("\"tool_calls\":[{\"id\":\"tool-1\",\"type\":\"function\"");
    assertThat(transformed.body()).contains("\"name\":\"lookup\"");
    assertThat(transformed.body()).contains("\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"");
  }

  @Test
  void transformsBedrockMessagesCountTokensResponseToAnthropicShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "bedrock",
        "/v1/messages/count_tokens",
        new ProviderResponse(
            200,
            "{\"inputTokens\":17}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).isEqualTo("{\"input_tokens\":17}");
  }

  @Test
  void transformsInvalidBedrockMessagesCountTokensResponseToProviderErrorShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "bedrock",
        "/v1/messages/count_tokens",
        new ProviderResponse(
            200,
            "{\"unexpected\":true}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"provider\":\"bedrock\"");
    assertThat(transformed.body()).contains("Invalid response received from bedrock: {\\\"unexpected\\\":true}");
    assertThat(transformed.body()).contains("\"type\":null");
    assertThat(transformed.body()).contains("\"param\":null");
    assertThat(transformed.body()).contains("\"code\":null");
  }

  @Test
  void transformsVertexGeminiResponseToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "vertex-ai",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            """
                {
                  "modelVersion": "gemini-1.5-pro",
                  "candidates": [
                    {
                      "content": {"parts": [{"text": "hello"}]},
                      "finishReason": "STOP"
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 2,
                    "candidatesTokenCount": 3,
                    "totalTokenCount": 5
                  }
                }
                """,
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"provider\":\"vertex-ai\"");
    assertThat(transformed.body()).contains("\"model\":\"gemini-1.5-pro\"");
    assertThat(transformed.body()).contains("\"content\":\"hello\"");
    assertThat(transformed.body()).contains("\"finish_reason\":\"stop\"");
  }

  @Test
  void transformsVertexGeminiResponseMetadataToolCallsAndTokenDetails() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "vertex-ai",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            """
                {
                  "modelVersion": "gemini-1.5-pro",
                  "candidates": [
                    {
                      "index": 0,
                      "content": {
                        "parts": [
                          {"text": "thinking", "thought": true},
                          {"text": "answer"},
                          {"inlineData": {"mimeType": "image/png", "data": "abc"}},
                          {"functionCall": {"name": "lookup", "args": {"city": "NYC"}}, "thoughtSignature": "sig-a"}
                        ]
                      },
                      "finishReason": "SAFETY",
                      "safetyRatings": [{"category": "HARM_CATEGORY_DANGEROUS_CONTENT", "probability": "LOW"}],
                      "groundingMetadata": {"webSearchQueries": ["query"]},
                      "logprobsResult": {
                        "chosenCandidates": [{"token": "a", "logProbability": -0.1}],
                        "topCandidates": [{"candidates": [{"token": "a", "logProbability": -0.1}]}]
                      }
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 2,
                    "candidatesTokenCount": 3,
                    "totalTokenCount": 5,
                    "thoughtsTokenCount": 1,
                    "cachedContentTokenCount": 4,
                    "promptTokensDetails": [{"modality": "AUDIO", "tokenCount": 6}],
                    "candidatesTokensDetails": [{"modality": "AUDIO", "tokenCount": 7}]
                  }
                }
                """,
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"provider\":\"vertex-ai\"");
    assertThat(transformed.body()).contains("\"content\":\"answer\"");
    assertThat(transformed.body()).contains("\"content_blocks\":[{\"type\":\"thinking\",\"thinking\":\"thinking\"}");
    assertThat(transformed.body()).contains("\"image_url\":{\"url\":\"data:image/png;base64,abc\"}");
    assertThat(transformed.body()).contains("\"tool_calls\":[{\"id\":");
    assertThat(transformed.body()).contains("\"name\":\"lookup\"");
    assertThat(transformed.body()).contains("\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"");
    assertThat(transformed.body()).contains("\"finish_reason\":\"content_filter\"");
    assertThat(transformed.body()).contains("\"safetyRatings\":[{\"category\":\"HARM_CATEGORY_DANGEROUS_CONTENT\"");
    assertThat(transformed.body()).contains("\"groundingMetadata\":{\"webSearchQueries\":[\"query\"]}");
    assertThat(transformed.body()).contains("\"logprobs\":{\"content\":[{\"token\":\"a\",\"logprob\":-0.1,\"bytes\":[97],\"top_logprobs\"");
    assertThat(transformed.body()).contains("\"completion_tokens_details\":{\"reasoning_tokens\":1,\"audio_tokens\":7}");
    assertThat(transformed.body()).contains("\"prompt_tokens_details\":{\"cached_tokens\":4,\"audio_tokens\":6}");
  }

  @Test
  void transformsVertexBatchAndFineTuneResponsesToOpenAiShapes() {
    String batchRecord = """
        {
          "name": "projects/project-a/locations/us/batchPredictionJobs/batch-1",
          "model": "publishers/google/models/gemini-1.5-pro",
          "state": "JOB_STATE_SUCCEEDED",
          "inputConfig": {"gcsSource": {"uris": ["gs://bucket/input.jsonl"]}},
          "outputConfig": {"gcsDestination": {"outputUriPrefix": "gs://bucket/output/"}},
          "outputInfo": {"gcsOutputDirectory": "gs://bucket/output/job/"},
          "createTime": "2026-01-01T00:00:00Z",
          "startTime": "2026-01-01T00:01:00Z",
          "endTime": "2026-01-01T00:02:00Z",
          "updateTime": "2026-01-01T00:02:00Z",
          "completionStats": {"successfulCount": "2", "failedCount": "1"}
        }
        """;
    ProviderResponse batch = ProviderResponseTransformer.transform(
        "vertex-ai",
        "/v1/batches/batch-1",
        new ProviderResponse(200, batchRecord, Map.of("content-type", "application/json"), 0));
    ProviderResponse batches = ProviderResponseTransformer.transform(
        "vertex-ai",
        "/v1/batches",
        new ProviderResponse(200, "{\"batchPredictionJobs\":[" + batchRecord + "],\"nextPageToken\":\"next\"}",
            Map.of("content-type", "application/json"), 0));
    String fineTuneRecord = """
        {
          "name": "projects/project-a/locations/us/tuningJobs/tune-1",
          "state": "JOB_STATE_SUCCEEDED",
          "createTime": "2026-01-01T00:00:00Z",
          "endTime": "2026-01-01T00:02:00Z",
          "tunedModel": {"model": "projects/project-a/models/tuned"},
          "baseModel": "publishers/google/models/gemini-1.5-pro",
          "supervisedTuningSpec": {
            "trainingDatasetUri": "gs://bucket/train.jsonl",
            "validationDatasetUri": "gs://bucket/validation.jsonl",
            "hyperParameters": {
              "adapterSize": 16,
              "learningRateMultiplier": 0.2,
              "epochCount": 3
            }
          },
          "tuningDataStats": {
            "supervisedTuningDataStats": {"totalBillableTokenCount": 99}
          }
        }
        """;
    ProviderResponse fineTune = ProviderResponseTransformer.transform(
        "vertex-ai",
        "/v1/fine_tuning/jobs/tune-1",
        new ProviderResponse(200, fineTuneRecord, Map.of("content-type", "application/json"), 0));
    ProviderResponse fineTunes = ProviderResponseTransformer.transform(
        "vertex-ai",
        "/v1/fine_tuning/jobs",
        new ProviderResponse(200, "{\"tuningJobs\":[" + fineTuneRecord + "],\"nextPageToken\":\"next\"}",
            Map.of("content-type", "application/json"), 0));

    assertThat(batch.body()).contains("\"id\":\"batch-1\"");
    assertThat(batch.body()).contains("\"object\":\"batch\"");
    assertThat(batch.body()).contains("\"status\":\"completed\"");
    assertThat(batch.body()).contains("\"output_file_id\":\"gs%3A%2F%2Fbucket%2Foutput%2Fjob%2Fpredictions.jsonl\"");
    assertThat(batches.body()).contains("\"object\":\"list\"");
    assertThat(batches.body()).contains("\"has_more\":true");
    assertThat(fineTune.body()).contains("\"id\":\"tune-1\"");
    assertThat(fineTune.body()).contains("\"object\":\"finetune\"");
    assertThat(fineTune.body()).contains("\"status\":\"succeeded\"");
    assertThat(fineTune.body()).contains("\"fine_tuned_model\":\"projects/project-a/models/tuned\"");
    assertThat(fineTune.body()).contains("\"trained_tokens\":99");
    assertThat(fineTunes.body()).contains("\"object\":\"list\"");
    assertThat(fineTunes.body()).contains("\"has_more\":true");
  }

  @Test
  void transformsVertexAnthropicRawPredictResponseToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "vertex-ai",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            """
                {
                  "id": "msg-1",
                  "model": "claude-3-5-sonnet",
                  "content": [
                    {"type": "thinking", "text": "reasoning"},
                    {"type": "text", "text": "answer"},
                    {"type": "tool_use", "id": "tool-1", "name": "lookup", "input": {"city": "NYC"}}
                  ],
                  "stop_reason": "tool_use",
                  "usage": {
                    "input_tokens": 2,
                    "output_tokens": 3,
                    "cache_creation_input_tokens": 4,
                    "cache_read_input_tokens": 5
                  }
                }
                """,
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"id\":\"msg-1\"");
    assertThat(transformed.body()).contains("\"provider\":\"vertex-ai\"");
    assertThat(transformed.body()).contains("\"model\":\"claude-3-5-sonnet\"");
    assertThat(transformed.body()).contains("\"content\":\"answer\"");
    assertThat(transformed.body()).contains("\"content_blocks\":[{\"type\":\"thinking\",\"thinking\":\"reasoning\"}");
    assertThat(transformed.body()).contains("\"tool_calls\":[{\"id\":\"tool-1\",\"type\":\"function\"");
    assertThat(transformed.body()).contains("\"name\":\"lookup\"");
    assertThat(transformed.body()).contains("\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"");
    assertThat(transformed.body()).contains("\"finish_reason\":\"tool_calls\"");
    assertThat(transformed.body()).contains("\"prompt_tokens\":2");
    assertThat(transformed.body()).contains("\"completion_tokens\":3");
    assertThat(transformed.body()).contains("\"total_tokens\":14");
    assertThat(transformed.body()).contains("\"prompt_tokens_details\":{\"cached_tokens\":5}");
    assertThat(transformed.body()).contains("\"cache_creation_input_tokens\":4");
    assertThat(transformed.body()).contains("\"cache_read_input_tokens\":5");
  }

  @Test
  void annotatesVertexLlamaRawPredictResponseToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "vertex-ai",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            """
                {
                  "id": "llama-1",
                  "object": "chat.completion",
                  "created": 123,
                  "model": "meta/llama-3.1",
                  "choices": [
                    {"index": 0, "message": {"role": "assistant", "content": "hello"}, "finish_reason": "stop"}
                  ],
                  "usage": {"prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3}
                }
                """,
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"id\":\"llama-1\"");
    assertThat(transformed.body()).contains("\"provider\":\"vertex-ai\"");
    assertThat(transformed.body()).contains("\"model\":\"meta/llama-3.1\"");
    assertThat(transformed.body()).contains("\"content\":\"hello\"");
    assertThat(transformed.body()).contains("\"total_tokens\":3");
  }

  @Test
  void transformsNativeGoogleGeminiResponsesToOpenAiShape() {
    ProviderResponse chat = ProviderResponseTransformer.transform(
        "google",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            """
                {
                  "modelVersion": "gemini-1.5-pro",
                  "candidates": [
                    {
                      "index": 0,
                      "content": {
                        "parts": [
                          {"text": "thinking", "thought": true},
                          {"text": "answer"},
                          {"functionCall": {"name": "lookup", "args": {"city": "NYC"}}}
                        ]
                      },
                      "finishReason": "STOP"
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 2,
                    "candidatesTokenCount": 3,
                    "totalTokenCount": 5,
                    "thoughtsTokenCount": 1,
                    "cachedContentTokenCount": 1,
                    "promptTokensDetails": [{"modality": "AUDIO", "tokenCount": 4}],
                    "candidatesTokensDetails": [{"modality": "AUDIO", "tokenCount": 6}]
                  }
                }
                """,
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse embedding = ProviderResponseTransformer.transform(
        "google",
        "/v1/embeddings",
        new ProviderResponse(
            200,
            "{\"embedding\":{\"values\":[0.1,0.2]}}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(chat.body()).contains("\"provider\":\"google\"");
    assertThat(chat.body()).contains("\"model\":\"gemini-1.5-pro\"");
    assertThat(chat.body()).contains("\"content\":\"answer\"");
    assertThat(chat.body()).contains("\"content_blocks\":[{\"type\":\"thinking\",\"thinking\":\"thinking\"}");
    assertThat(chat.body()).contains("\"tool_calls\":[{\"id\":");
    assertThat(chat.body()).contains("\"name\":\"lookup\"");
    assertThat(chat.body()).contains("\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"");
    assertThat(chat.body()).contains("\"reasoning_tokens\":1");
    assertThat(chat.body()).contains("\"cached_tokens\":1");
    assertThat(chat.body()).contains("\"audio_tokens\":4");
    assertThat(chat.body()).contains("\"audio_tokens\":6");
    assertThat(embedding.body()).contains("\"object\":\"list\"");
    assertThat(embedding.body()).contains("\"provider\":\"google\"");
    assertThat(embedding.body()).contains("\"embedding\":[0.1,0.2]");
  }

  @Test
  void transformsImageProviderResponsesToOpenAiShape() {
    ProviderResponse stability = ProviderResponseTransformer.transform(
        "stability-ai",
        "/v1/images/generations",
        new ProviderResponse(
            200,
            "{\"image\":\"aW1n\",\"finish_reason\":\"SUCCESS\",\"seed\":7}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse fireworks = ProviderResponseTransformer.transform(
        "fireworks-ai",
        "/v1/images/generations",
        new ProviderResponse(
            200,
            "[{\"base64\":\"aW1n\",\"finishReason\":\"SUCCESS\",\"seed\":7}]",
            Map.of("content-type", "application/json"),
            0));

    assertThat(stability.body()).contains("\"provider\":\"stability-ai\"");
    assertThat(stability.body()).contains("\"b64_json\":\"aW1n\"");
    assertThat(fireworks.body()).contains("\"provider\":\"fireworks-ai\"");
    assertThat(fireworks.body()).contains("\"seed\":7");
  }

  @Test
  void transformsPalmResponsesToOpenAiShapes() {
    ProviderResponse chat = ProviderResponseTransformer.transform(
        "palm",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            "{\"candidates\":[{\"content\":\"hello\"},{\"content\":\"there\"}]}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse completion = ProviderResponseTransformer.transform(
        "palm",
        "/v1/completions",
        new ProviderResponse(
            200,
            "{\"candidates\":[{\"output\":\"hello\"}]}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse embedding = ProviderResponseTransformer.transform(
        "palm",
        "/v1/embeddings",
        new ProviderResponse(
            200,
            "{\"embedding\":{\"value\":[0.1,0.2]}}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(chat.body()).contains("\"object\":\"chat.completion\"");
    assertThat(chat.body()).contains("\"provider\":\"palm\"");
    assertThat(chat.body()).contains("\"content\":\"hello\"");
    assertThat(chat.body()).contains("\"content\":\"there\"");
    assertThat(chat.body()).contains("\"finish_reason\":\"length\"");
    assertThat(completion.body()).contains("\"object\":\"completion\"");
    assertThat(completion.body()).contains("\"provider\":\"palm\"");
    assertThat(completion.body()).contains("\"text\":\"hello\"");
    assertThat(embedding.body()).contains("\"object\":\"list\"");
    assertThat(embedding.body()).contains("\"provider\":\"palm\"");
    assertThat(embedding.body()).contains("\"embedding\":[0.1,0.2]");
    assertThat(embedding.body()).contains("\"prompt_tokens\":-1");
  }

  @Test
  void transformsPalmErrorsToOpenAiErrorShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "palm",
        "/v1/chat/completions",
        new ProviderResponse(
            400,
            "{\"error\":{\"message\":\"bad prompt\",\"status\":\"INVALID_ARGUMENT\",\"code\":400}}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"provider\":\"palm\"");
    assertThat(transformed.body()).contains("\"message\":\"bad prompt\"");
    assertThat(transformed.body()).contains("\"type\":\"INVALID_ARGUMENT\"");
    assertThat(transformed.body()).contains("\"code\":\"400\"");
  }

  @Test
  void annotatesRecraftImageGenerationResponses() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "recraft-ai",
        "/v1/images/generations",
        new ProviderResponse(
            200,
            "{\"data\":[{\"url\":\"https://img.example/recraft.png\"}]}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"provider\":\"recraft-ai\"");
    assertThat(transformed.body()).contains("\"url\":\"https://img.example/recraft.png\"");
  }

  @Test
  void transformsAi21ResponsesToOpenAiShapes() {
    ProviderResponse chat = ProviderResponseTransformer.transform(
        "ai21",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            """
                {
                  "id": "ai21-chat-1",
                  "outputs": [
                    {"text": "hello", "role": "assistant", "finishReason": {"reason": "stop"}}
                  ]
                }
                """,
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse completion = ProviderResponseTransformer.transform(
        "ai21",
        "/v1/completions",
        new ProviderResponse(
            200,
            """
                {
                  "id": "ai21-complete-1",
                  "prompt": {"text": "Hello", "tokens": [{"t": "Hello"}]},
                  "completions": [
                    {"data": {"text": " world", "tokens": [{"t": " world"}]}, "finishReason": {"reason": "length", "length": 1}}
                  ]
                }
                """,
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse embedding = ProviderResponseTransformer.transform(
        "ai21",
        "/v1/embeddings",
        new ProviderResponse(
            200,
            "{\"id\":\"ai21-embed-1\",\"results\":[{\"embedding\":[0.1,0.2]}]}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(chat.body()).contains("\"id\":\"ai21-chat-1\"");
    assertThat(chat.body()).contains("\"object\":\"chat.completion\"");
    assertThat(chat.body()).contains("\"provider\":\"ai21\"");
    assertThat(chat.body()).contains("\"content\":\"hello\"");
    assertThat(chat.body()).contains("\"finish_reason\":\"stop\"");
    assertThat(completion.body()).contains("\"id\":\"ai21-complete-1\"");
    assertThat(completion.body()).contains("\"object\":\"text_completion\"");
    assertThat(completion.body()).contains("\"provider\":\"ai21\"");
    assertThat(completion.body()).contains("\"text\":\" world\"");
    assertThat(completion.body()).contains("\"prompt_tokens\":1");
    assertThat(completion.body()).contains("\"completion_tokens\":1");
    assertThat(embedding.body()).contains("\"object\":\"list\"");
    assertThat(embedding.body()).contains("\"provider\":\"ai21\"");
    assertThat(embedding.body()).contains("\"embedding\":[0.1,0.2]");
    assertThat(embedding.body()).contains("\"prompt_tokens\":-1");
  }

  @Test
  void transformsAi21ErrorsToOpenAiErrorShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "ai21",
        "/v1/chat/completions",
        new ProviderResponse(
            400,
            "{\"detail\":\"bad request\"}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"provider\":\"ai21\"");
    assertThat(transformed.body()).contains("\"message\":\"bad request\"");
  }

  @Test
  void transformsFireworksChatResponsesToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "fireworks-ai",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            """
                {
                  "id": "fw-1",
                  "object": "chat.completion",
                  "created": 1710000000,
                  "model": "accounts/fireworks/models/llama-v3p1-405b-instruct",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "hello",
                        "tool_calls": [{"id": "call_1", "type": "function"}]
                      },
                      "finish_reason": "stop",
                      "logprobs": {"content": []}
                    }
                  ],
                  "usage": {"prompt_tokens": 2, "completion_tokens": 3, "total_tokens": 5}
                }
                """,
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"provider\":\"fireworks-ai\"");
    assertThat(transformed.body()).contains("\"id\":\"fw-1\"");
    assertThat(transformed.body()).contains("\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\"}]");
    assertThat(transformed.body()).contains("\"logprobs\":{\"content\":[]}");
    assertThat(transformed.body()).contains("\"total_tokens\":5");
  }

  @Test
  void enrichesOpenRouterReasoningResponseWhenPresent() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "openrouter",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            """
                {
                  "id": "or-1",
                  "object": "chat.completion",
                  "created": 1710000000,
                  "model": "openrouter/auto",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "answer",
                        "reasoning": "thinking",
                        "reasoning_details": [{"type": "text", "text": "thinking"}]
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {"prompt_tokens": 2, "completion_tokens": 3, "total_tokens": 5}
                }
                """,
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"provider\":\"openrouter\"");
    assertThat(transformed.body()).contains("\"content_blocks\":[{\"type\":\"thinking\",\"thinking\":\"thinking\"");
    assertThat(transformed.body()).contains("\"type\":\"text\",\"text\":\"answer\"");
    assertThat(transformed.body()).contains("\"reasoning_details\":[{\"type\":\"text\",\"text\":\"thinking\"}]");
  }

  @Test
  void transformsBedrockCompletionAndEmbeddingResponsesToOpenAiShape() {
    ProviderResponse completion = ProviderResponseTransformer.transform(
        "bedrock",
        "/v1/completions",
        new ProviderResponse(
            200,
            """
                {
                  "inputTextTokenCount": 2,
                  "results": [
                    {"tokenCount": 3, "outputText": "hello", "completionReason": "FINISH"}
                  ]
                }
                """,
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse embedding = ProviderResponseTransformer.transform(
        "bedrock",
        "/v1/embeddings",
        new ProviderResponse(
            200,
            "{\"embedding\":[0.1,0.2],\"inputTextTokenCount\":4}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(completion.body()).contains("\"object\":\"text_completion\"");
    assertThat(completion.body()).contains("\"provider\":\"bedrock\"");
    assertThat(completion.body()).contains("\"text\":\"hello\"");
    assertThat(completion.body()).contains("\"prompt_tokens\":2");
    assertThat(completion.body()).contains("\"completion_tokens\":3");
    assertThat(embedding.body()).contains("\"object\":\"list\"");
    assertThat(embedding.body()).contains("\"provider\":\"bedrock\"");
    assertThat(embedding.body()).contains("\"embedding\":[0.1,0.2]");
    assertThat(embedding.body()).contains("\"total_tokens\":4");
  }

  @Test
  void transformsWorkersAiEmbeddingAndImageResponsesToOpenAiShape() {
    ProviderResponse embedding = ProviderResponseTransformer.transform(
        "workers-ai",
        "/v1/embeddings",
        new ProviderResponse(
            200,
            "{\"result\":{\"shape\":[1,2],\"data\":[[0.1,0.2]]}}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse image = ProviderResponseTransformer.transform(
        "workers-ai",
        "/v1/images/generations",
        new ProviderResponse(
            200,
            "{\"result\":{\"image\":\"aW1n\"},\"success\":true}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(embedding.body()).contains("\"object\":\"list\"");
    assertThat(embedding.body()).contains("\"provider\":\"workers-ai\"");
    assertThat(embedding.body()).contains("\"embedding\":[0.1,0.2]");
    assertThat(image.body()).contains("\"provider\":\"workers-ai\"");
    assertThat(image.body()).contains("\"b64_json\":\"aW1n\"");
  }

  @Test
  void transformsVertexEmbeddingAndImageResponsesToOpenAiShape() {
    ProviderResponse embedding = ProviderResponseTransformer.transform(
        "vertex-ai",
        "/v1/embeddings",
        new ProviderResponse(
            200,
            """
                {
                  "predictions": [
                    {"embeddings": {"values": [0.1, 0.2], "statistics": {"token_count": 4}}}
                  ]
                }
                """,
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse image = ProviderResponseTransformer.transform(
        "vertex-ai",
        "/v1/images/generations",
        new ProviderResponse(
            200,
            "{\"predictions\":[{\"bytesBase64Encoded\":\"aW1n\",\"mimeType\":\"image/png\"}]}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(embedding.body()).contains("\"object\":\"list\"");
    assertThat(embedding.body()).contains("\"provider\":\"vertex-ai\"");
    assertThat(embedding.body()).contains("\"embedding\":[0.1,0.2]");
    assertThat(embedding.body()).contains("\"total_tokens\":4");
    assertThat(image.body()).contains("\"provider\":\"vertex-ai\"");
    assertThat(image.body()).contains("\"b64_json\":\"aW1n\"");
  }

  @Test
  void transformsVoyageAndJinaEmbeddingResponsesToOpenAiShape() {
    ProviderResponse voyage = ProviderResponseTransformer.transform(
        "voyage",
        "/v1/embeddings",
        new ProviderResponse(
            200,
            "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.1],\"index\":0}],\"model\":\"voyage-3\",\"usage\":{\"total_tokens\":5}}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse jina = ProviderResponseTransformer.transform(
        "jina",
        "/v1/embeddings",
        new ProviderResponse(
            200,
            "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.2],\"index\":0}],\"model\":\"jina-embeddings-v3\",\"usage\":{\"prompt_tokens\":6,\"total_tokens\":6}}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(voyage.body()).contains("\"provider\":\"voyage\"");
    assertThat(voyage.body()).contains("\"prompt_tokens\":5");
    assertThat(jina.body()).contains("\"provider\":\"jina\"");
    assertThat(jina.body()).contains("\"prompt_tokens\":6");
  }

  @Test
  void transformsNomicEmbeddingResponsesToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "nomic",
        "/v1/embeddings",
        new ProviderResponse(
            200,
            "{\"embeddings\":[[0.1,0.2],[0.3,0.4]],\"model\":\"nomic-embed-text-v1\",\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse error = ProviderResponseTransformer.transform(
        "nomic",
        "/v1/embeddings",
        new ProviderResponse(
            422,
            "{\"detail\":[{\"loc\":[\"body\",\"texts\"],\"msg\":\"field required\",\"type\":\"missing\"}]}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"object\":\"list\"");
    assertThat(transformed.body()).contains("\"provider\":\"nomic\"");
    assertThat(transformed.body()).contains("\"model\":\"nomic-embed-text-v1\"");
    assertThat(transformed.body()).contains("\"embedding\":[0.1,0.2]");
    assertThat(transformed.body()).contains("\"prompt_tokens\":7");
    assertThat(error.body()).contains("\"provider\":\"nomic\"");
    assertThat(error.body()).contains("\"message\":\"body.texts: field required\"");
    assertThat(error.body()).contains("\"type\":\"missing\"");
  }

  @Test
  void transformsPredibaseChatResponsesToOpenAiShape() throws Exception {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "predibase",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            """
                {
                  "id": "cmpl-1",
                  "object": "chat.completion",
                  "created": 123,
                  "model": "llama-3:adapter-repo/2",
                  "choices": [
                    {
                      "index": 0,
                      "message": {"role": "assistant", "content": "hello"},
                      "logprobs": null,
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {"prompt_tokens": 3, "completion_tokens": 4, "total_tokens": 7}
                }
                """,
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse stream = ProviderResponseTransformer.transform(
        "predibase",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                data: {"id":"cmpl-2","object":"chat.completion.chunk","created":124,"model":"llama-3","choices":[{"index":0,"delta":{"role":"assistant","content":"hi"},"finish_reason":null}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));
    ProviderResponse error = ProviderResponseTransformer.transform(
        "predibase",
        "/v1/chat/completions",
        new ProviderResponse(
            400,
            "{\"error\":{\"message\":\"bad model\",\"type\":\"invalid_request_error\",\"code\":400}}",
            Map.of("content-type", "application/json"),
            0));

    String streamBody = new String(stream.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(transformed.body()).contains("\"provider\":\"predibase\"");
    assertThat(transformed.body()).contains("\"content\":\"hello\"");
    assertThat(transformed.body()).contains("\"total_tokens\":7");
    assertThat(streamBody).contains("\"provider\":\"predibase\"");
    assertThat(streamBody).contains("\"content\":\"hi\"");
    assertThat(streamBody).contains("data: [DONE]");
    assertThat(error.body()).contains("\"provider\":\"predibase\"");
    assertThat(error.body()).contains("\"message\":\"bad model\"");
  }

  @Test
  void transformsRekaChatResponsesToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "reka-ai",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            "{\"type\":\"model\",\"text\":\"hello\",\"finish_reason\":\"stop\",\"metadata\":{\"input_tokens\":5,\"generated_tokens\":6}}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse error = ProviderResponseTransformer.transform(
        "reka-ai",
        "/v1/chat/completions",
        new ProviderResponse(
            400,
            "{\"detail\":[{\"msg\":\"bad request\"}]}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"object\":\"chat.completion\"");
    assertThat(transformed.body()).contains("\"provider\":\"reka-ai\"");
    assertThat(transformed.body()).contains("\"content\":\"hello\"");
    assertThat(transformed.body()).contains("\"prompt_tokens\":5");
    assertThat(transformed.body()).contains("\"completion_tokens\":6");
    assertThat(error.body()).contains("\"provider\":\"reka-ai\"");
    assertThat(error.body()).contains("\"message\":\"[{\\\"msg\\\":\\\"bad request\\\"}]\"");
  }

  @Test
  void transformsSegmindImageResponsesToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "segmind",
        "/v1/images/generations",
        new ProviderResponse(
            200,
            "{\"image\":[\"aW1nMQ==\",\"aW1nMg==\"],\"status\":\"success\",\"interTime\":1}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse error = ProviderResponseTransformer.transform(
        "segmind",
        "/v1/images/generations",
        new ProviderResponse(
            400,
            "{\"html-message\":\"bad image\"}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"provider\":\"segmind\"");
    assertThat(transformed.body()).contains("\"b64_json\":\"aW1nMQ==\"");
    assertThat(transformed.body()).contains("\"b64_json\":\"aW1nMg==\"");
    assertThat(error.body()).contains("\"provider\":\"segmind\"");
    assertThat(error.body()).contains("\"message\":\"bad image\"");
  }

  @Test
  void transformsBytezChatResponsesToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "bytez",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            "{\"output\":{\"role\":\"assistant\",\"content\":\"hello\"}}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse error = ProviderResponseTransformer.transform(
        "bytez",
        "/v1/chat/completions",
        new ProviderResponse(
            400,
            "{\"error\":\"bad model\"}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"object\":\"chat.completion\"");
    assertThat(transformed.body()).contains("\"provider\":\"bytez\"");
    assertThat(transformed.body()).contains("\"content\":\"hello\"");
    assertThat(transformed.body()).contains("\"prompt_tokens\":-1");
    assertThat(error.body()).contains("\"provider\":\"bytez\"");
    assertThat(error.body()).contains("\"message\":\"bad model\"");
    assertThat(error.body()).contains("\"type\":\"400\"");
  }

  @Test
  void transformsTritonCompletionResponsesToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "triton",
        "/v1/completions",
        new ProviderResponse(
            200,
            "{\"model_name\":\"ensemble\",\"text_output\":\"hello\",\"sequence_end\":true}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse error = ProviderResponseTransformer.transform(
        "triton",
        "/v1/completions",
        new ProviderResponse(
            500,
            "{\"error\":\"backend unavailable\"}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"object\":\"text_completion\"");
    assertThat(transformed.body()).contains("\"provider\":\"triton\"");
    assertThat(transformed.body()).contains("\"model\":\"ensemble\"");
    assertThat(transformed.body()).contains("\"text\":\"hello\"");
    assertThat(transformed.body()).contains("\"finish_reason\":\"stop\"");
    assertThat(error.body()).contains("\"provider\":\"triton\"");
    assertThat(error.body()).contains("\"message\":\"backend unavailable\"");
  }

  @Test
  void transformsOllamaResponsesAndStreamsToOpenAiShape() throws Exception {
    ProviderResponse chat = ProviderResponseTransformer.transform(
        "ollama",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            "{\"id\":\"chat-1\",\"object\":\"chat.completion\",\"created\":123,\"model\":\"llama3.1\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse embedding = ProviderResponseTransformer.transform(
        "ollama",
        "/v1/embeddings",
        new ProviderResponse(
            200,
            "{\"model\":\"nomic-embed-text\",\"embedding\":[0.1,0.2]}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse stream = ProviderResponseTransformer.transform(
        "ollama",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                data: {"id":"chat-2","object":"chat.completion.chunk","created":124,"model":"llama3.1","choices":[{"index":0,"delta":{"role":"assistant","content":"hi"},"finish_reason":null}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));
    ProviderResponse error = ProviderResponseTransformer.transform(
        "ollama",
        "/v1/chat/completions",
        new ProviderResponse(
            400,
            "{\"error\":{\"message\":\"bad model\",\"type\":\"invalid_request_error\"}}",
            Map.of("content-type", "application/json"),
            0));

    String streamBody = new String(stream.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(chat.body()).contains("\"provider\":\"ollama\"");
    assertThat(chat.body()).contains("\"content\":\"hello\"");
    assertThat(embedding.body()).contains("\"object\":\"list\"");
    assertThat(embedding.body()).contains("\"provider\":\"ollama\"");
    assertThat(embedding.body()).contains("\"model\":\"nomic-embed-text\"");
    assertThat(embedding.body()).contains("\"embedding\":[0.1,0.2]");
    assertThat(streamBody).contains("\"provider\":\"ollama\"");
    assertThat(streamBody).contains("\"content\":\"hi\"");
    assertThat(streamBody).contains("data: [DONE]");
    assertThat(error.body()).contains("\"provider\":\"ollama\"");
    assertThat(error.body()).contains("\"message\":\"bad model\"");
  }

  @Test
  void annotatesTogetherAiResponsesAndStreams() throws Exception {
    ProviderResponse chat = ProviderResponseTransformer.transform(
        "together-ai",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            "{\"id\":\"chat-1\",\"object\":\"chat.completion\",\"created\":123,\"model\":\"llama\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"eos\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse embedding = ProviderResponseTransformer.transform(
        "together-ai",
        "/v1/embeddings",
        new ProviderResponse(
            200,
            "{\"object\":\"list\",\"model\":\"mistral-embed\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.1],\"index\":0}],\"usage\":{\"prompt_tokens\":0,\"total_tokens\":0}}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse stream = ProviderResponseTransformer.transform(
        "together-ai",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                data: {"id":"chat-2","object":"chat.completion.chunk","created":124,"model":"llama","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":null}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String streamBody = new String(stream.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(chat.body()).contains("\"provider\":\"together-ai\"");
    assertThat(chat.body()).contains("\"finish_reason\":\"stop\"");
    assertThat(embedding.body()).contains("\"provider\":\"together-ai\"");
    assertThat(streamBody).contains("\"provider\":\"together-ai\"");
    assertThat(streamBody).contains("\"content\":\"hi\"");
    assertThat(streamBody).contains("data: [DONE]");
  }

  @Test
  void annotatesLeptonOpenAiCompatibleResponsesAndStreams() throws Exception {
    ProviderResponse chat = ProviderResponseTransformer.transform(
        "lepton",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            "{\"id\":\"chat-1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse stream = ProviderResponseTransformer.transform(
        "lepton",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                data: {"id":"chat-2","object":"chat.completion.chunk","created":123,"model":"llama","choices":[{"index":0,"delta":{"role":"assistant","content":"hi"},"finish_reason":null}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String streamBody = new String(stream.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(chat.body()).contains("\"provider\":\"lepton\"");
    assertThat(streamBody).contains("\"provider\":\"lepton\"");
    assertThat(streamBody).contains("\"content\":\"hi\"");
    assertThat(streamBody).contains("data: [DONE]");
  }

  @Test
  void annotatesAdditionalOpenAiCompatibleProviderResponsesAndStreams() throws Exception {
    ProviderResponse modal = ProviderResponseTransformer.transform(
        "modal",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            "{\"id\":\"chat-1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse upstageEmbedding = ProviderResponseTransformer.transform(
        "upstage",
        "/v1/embeddings",
        new ProviderResponse(
            200,
            "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.1],\"index\":0}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse dashscopeStream = ProviderResponseTransformer.transform(
        "dashscope",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                data: {"id":"chat-2","object":"chat.completion.chunk","created":123,"model":"qwen","choices":[{"index":0,"delta":{"role":"assistant","content":"hi"},"finish_reason":null}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String streamBody = new String(dashscopeStream.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(modal.body()).contains("\"provider\":\"modal\"");
    assertThat(upstageEmbedding.body()).contains("\"provider\":\"upstage\"");
    assertThat(streamBody).contains("\"provider\":\"dashscope\"");
    assertThat(streamBody).contains("\"content\":\"hi\"");
    assertThat(streamBody).contains("data: [DONE]");
  }

  @Test
  void annotatesLambdaOpenAiCompatibleResponsesAndStreams() throws Exception {
    ProviderResponse chat = ProviderResponseTransformer.transform(
        "lambda",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            "{\"id\":\"chat-1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse stream = ProviderResponseTransformer.transform(
        "lambda",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                data: {"id":"chat-2","object":"chat.completion.chunk","created":123,"model":"llama","choices":[{"index":0,"delta":{"role":"assistant","content":"hi"},"finish_reason":null}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String streamBody = new String(stream.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(chat.body()).contains("\"provider\":\"lambda\"");
    assertThat(streamBody).contains("\"provider\":\"lambda\"");
    assertThat(streamBody).contains("\"content\":\"hi\"");
    assertThat(streamBody).contains("data: [DONE]");
  }

  @Test
  void annotates302AiOpenAiCompatibleResponsesAndStreams() throws Exception {
    ProviderResponse chat = ProviderResponseTransformer.transform(
        "302ai",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            "{\"id\":\"chat-1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse stream = ProviderResponseTransformer.transform(
        "302ai",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                data: {"id":"chat-2","object":"chat.completion.chunk","created":123,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"hi"},"finish_reason":null}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String streamBody = new String(stream.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(chat.body()).contains("\"provider\":\"302ai\"");
    assertThat(streamBody).contains("\"provider\":\"302ai\"");
    assertThat(streamBody).contains("\"content\":\"hi\"");
    assertThat(streamBody).contains("data: [DONE]");
  }

  @Test
  void annotatesAzureAiOpenAiCompatibleResponsesAndStreams() throws Exception {
    ProviderResponse chat = ProviderResponseTransformer.transform(
        "azure-ai",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            "{\"id\":\"chat-1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse embedding = ProviderResponseTransformer.transform(
        "azure-ai",
        "/v1/embeddings",
        new ProviderResponse(
            200,
            "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.1],\"index\":0}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse stream = ProviderResponseTransformer.transform(
        "azure-ai",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                data: {"id":"chat-2","object":"chat.completion.chunk","created":123,"model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"hi"},"finish_reason":null}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String streamBody = new String(stream.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(chat.body()).contains("\"provider\":\"azure-ai\"");
    assertThat(embedding.body()).contains("\"provider\":\"azure-ai\"");
    assertThat(streamBody).contains("\"provider\":\"azure-ai\"");
    assertThat(streamBody).contains("\"content\":\"hi\"");
    assertThat(streamBody).contains("data: [DONE]");
  }

  @Test
  void transformsAzureAiAnthropicFoundryChatResponseToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "azure-ai",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            """
                {
                  "id": "msg-azure-1",
                  "model": "claude-3-5-sonnet",
                  "content": [
                    {"type": "text", "text": "hello"},
                    {"type": "tool_use", "id": "tool-1", "name": "lookup", "input": {"city": "NYC"}}
                  ],
                  "stop_reason": "tool_use",
                  "usage": {"input_tokens": 2, "output_tokens": 3, "cache_read_input_tokens": 1}
                }
                """,
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"id\":\"msg-azure-1\"");
    assertThat(transformed.body()).contains("\"provider\":\"azure-ai\"");
    assertThat(transformed.body()).contains("\"object\":\"chat.completion\"");
    assertThat(transformed.body()).contains("\"content\":\"hello\"");
    assertThat(transformed.body()).contains("\"tool_calls\":[{\"id\":\"tool-1\",\"type\":\"function\"");
    assertThat(transformed.body()).contains("\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"");
    assertThat(transformed.body()).contains("\"finish_reason\":\"tool_calls\"");
    assertThat(transformed.body()).contains("\"prompt_tokens\":2");
    assertThat(transformed.body()).contains("\"completion_tokens\":3");
    assertThat(transformed.body()).contains("\"total_tokens\":6");
    assertThat(transformed.body()).contains("\"prompt_tokens_details\":{\"cached_tokens\":1}");
  }

  @Test
  void annotatesAzureAiNonInferenceSuccessResponses() {
    ProviderResponse batch = ProviderResponseTransformer.transform(
        "azure-ai",
        "/v1/batches/batch-1",
        new ProviderResponse(
            200,
            "{\"id\":\"batch-1\",\"object\":\"batch\",\"status\":\"completed\"}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse files = ProviderResponseTransformer.transform(
        "azure-ai",
        "/v1/files",
        new ProviderResponse(
            200,
            "{\"object\":\"list\",\"has_more\":false}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(batch.body()).contains("\"provider\":\"azure-ai\"");
    assertThat(batch.body()).contains("\"id\":\"batch-1\"");
    assertThat(files.body()).contains("\"provider\":\"azure-ai\"");
    assertThat(files.body()).contains("\"object\":\"list\"");
  }

  @Test
  void transformsOracleChatResponsesToOpenAiShape() {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "oracle",
        "/v1/chat/completions",
        new ProviderResponse(
            200,
            """
                {
                  "modelId": "cohere.command-r-plus",
                  "chatResponse": {
                    "timeCreated": "2026-04-26T12:00:00Z",
                    "choices": [
                      {
                        "index": 0,
                        "finishReason": "STOP",
                        "message": {
                          "role": "ASSISTANT",
                          "content": [{"type": "TEXT", "text": "hello"}]
                        }
                      }
                    ],
                    "usage": {"promptTokens": 3, "completionTokens": 4, "totalTokens": 7}
                  }
                }
                """,
            Map.of("content-type", "application/json", "opc-request-id", "opc-1"),
            0));
    ProviderResponse error = ProviderResponseTransformer.transform(
        "oracle",
        "/v1/chat/completions",
        new ProviderResponse(
            400,
            "{\"code\":\"InvalidParameter\",\"message\":\"bad request\"}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(transformed.body()).contains("\"id\":\"opc-1\"");
    assertThat(transformed.body()).contains("\"provider\":\"oracle\"");
    assertThat(transformed.body()).contains("\"model\":\"cohere.command-r-plus\"");
    assertThat(transformed.body()).contains("\"role\":\"assistant\"");
    assertThat(transformed.body()).contains("\"content\":\"hello\"");
    assertThat(transformed.body()).contains("\"prompt_tokens\":3");
    assertThat(error.body()).contains("\"provider\":\"oracle\"");
    assertThat(error.body()).contains("\"message\":\"bad request\"");
    assertThat(error.body()).contains("\"type\":\"InvalidParameter\"");
  }

  @Test
  void transformsOracleChatStreamChunksToOpenAiShape() throws Exception {
    ProviderResponse transformed = ProviderResponseTransformer.transform(
        "oracle",
        "/v1/chat/completions",
        ProviderResponse.streaming(
            200,
            new ByteArrayInputStream("""
                event: ping

                data: {"index":0,"message":{"role":"ASSISTANT","content":[{"type":"TEXT","text":"hello"}]}}

                data: {"index":0,"finishReason":"STOP","message":{"role":"ASSISTANT","content":[]}}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8)),
            Map.of("content-type", "text/event-stream"),
            0));

    String body = new String(transformed.bodyStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(transformed.streaming()).isTrue();
    assertThat(body).doesNotContain("event: ping");
    assertThat(body).contains("\"object\":\"chat.completion.chunk\"");
    assertThat(body).contains("\"provider\":\"oracle\"");
    assertThat(body).contains("\"role\":\"assistant\"");
    assertThat(body).contains("\"content\":\"hello\"");
    assertThat(body).contains("\"finish_reason\":\"stop\"");
    assertThat(body).contains("data: [DONE]");
  }

  @Test
  void normalizesProviderNativeErrorBodies() {
    ProviderResponse workers = ProviderResponseTransformer.transform(
        "workers-ai",
        "/v1/chat/completions",
        new ProviderResponse(
            400,
            "{\"success\":false,\"errors\":[{\"code\":\"1001\",\"message\":\"bad prompt\"}]}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse vertex = ProviderResponseTransformer.transform(
        "vertex-ai",
        "/v1/chat/completions",
        new ProviderResponse(
            403,
            "{\"error\":{\"message\":\"permission denied\",\"status\":\"PERMISSION_DENIED\",\"code\":403}}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse stability = ProviderResponseTransformer.transform(
        "stability-ai",
        "/v1/images/generations",
        new ProviderResponse(
            400,
            "{\"id\":\"req-1\",\"name\":\"bad_request\",\"errors\":[\"bad image\"]}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse voyage = ProviderResponseTransformer.transform(
        "voyage",
        "/v1/embeddings",
        new ProviderResponse(
            422,
            "{\"detail\":\"input too long\"}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(workers.body()).contains("\"provider\":\"workers-ai\"");
    assertThat(workers.body()).contains("\"message\":\"Error 1001:bad prompt\"");
    assertThat(vertex.body()).contains("\"provider\":\"vertex-ai\"");
    assertThat(vertex.body()).contains("\"message\":\"permission denied\"");
    assertThat(vertex.body()).contains("\"type\":\"PERMISSION_DENIED\"");
    assertThat(stability.body()).contains("\"provider\":\"stability-ai\"");
    assertThat(stability.body()).contains("\"message\":\"bad image\"");
    assertThat(stability.body()).contains("\"type\":\"bad_request\"");
    assertThat(voyage.body()).contains("\"provider\":\"voyage\"");
    assertThat(voyage.body()).contains("\"message\":\"input too long\"");
  }

  @Test
  void normalizesOpenAiCompatibleProviderErrors() {
    ProviderResponse openRouter = ProviderResponseTransformer.transform(
        "openrouter",
        "/v1/chat/completions",
        new ProviderResponse(
            400,
            "{\"message\":\"bad request\",\"type\":\"invalid_request_error\",\"param\":\"model\",\"code\":\"bad_model\"}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse perplexity = ProviderResponseTransformer.transform(
        "perplexity-ai",
        "/v1/chat/completions",
        new ProviderResponse(
            400,
            "{\"error\":{\"message\":\"bad search\",\"type\":\"invalid_request_error\",\"code\":400}}",
            Map.of("content-type", "application/json"),
            0));
    ProviderResponse huggingFace = ProviderResponseTransformer.transform(
        "huggingface",
        "/v1/chat/completions",
        new ProviderResponse(
            503,
            "{\"error\":\"model loading\"}",
            Map.of("content-type", "application/json"),
            0));

    assertThat(openRouter.body()).contains("\"provider\":\"openrouter\"");
    assertThat(openRouter.body()).contains("\"message\":\"bad request\"");
    assertThat(openRouter.body()).contains("\"param\":\"model\"");
    assertThat(perplexity.body()).contains("\"provider\":\"perplexity-ai\"");
    assertThat(perplexity.body()).contains("\"message\":\"bad search\"");
    assertThat(perplexity.body()).contains("\"code\":\"400\"");
    assertThat(huggingFace.body()).contains("\"provider\":\"huggingface\"");
    assertThat(huggingFace.body()).contains("\"message\":\"model loading\"");
    assertThat(huggingFace.body()).contains("\"code\":\"503\"");
  }

  private static byte[] concat(byte[]... chunks) {
    int length = 0;
    for (byte[] chunk : chunks) {
      length += chunk.length;
    }
    byte[] out = new byte[length];
    int offset = 0;
    for (byte[] chunk : chunks) {
      System.arraycopy(chunk, 0, out, offset, chunk.length);
      offset += chunk.length;
    }
    return out;
  }

  private static byte[] awsEventStreamFrame(String eventType, String payload) throws Exception {
    byte[] headers = awsHeaders(eventType);
    byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
    int totalLength = 12 + headers.length + payloadBytes.length + 4;
    ByteArrayOutputStream message = new ByteArrayOutputStream(totalLength);
    ByteBuffer prelude = ByteBuffer.allocate(8);
    prelude.putInt(totalLength);
    prelude.putInt(headers.length);
    byte[] preludeBytes = prelude.array();
    message.write(preludeBytes);
    writeInt(message, (int) crc32(preludeBytes));
    message.write(headers);
    message.write(payloadBytes);
    writeInt(message, (int) crc32(message.toByteArray()));
    return message.toByteArray();
  }

  private static byte[] awsHeaders(String eventType) throws Exception {
    ByteArrayOutputStream headers = new ByteArrayOutputStream();
    awsStringHeader(headers, ":message-type", "event");
    awsStringHeader(headers, ":event-type", eventType);
    awsStringHeader(headers, ":content-type", "application/json");
    return headers.toByteArray();
  }

  private static void awsStringHeader(ByteArrayOutputStream headers, String name, String value) throws Exception {
    byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
    byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
    headers.write(nameBytes.length);
    headers.write(nameBytes);
    headers.write(7);
    headers.write((valueBytes.length >>> 8) & 0xff);
    headers.write(valueBytes.length & 0xff);
    headers.write(valueBytes);
  }

  private static long crc32(byte[] bytes) {
    CRC32 crc32 = new CRC32();
    crc32.update(bytes);
    return crc32.getValue();
  }

  private static void writeInt(ByteArrayOutputStream stream, int value) {
    stream.write((value >>> 24) & 0xff);
    stream.write((value >>> 16) & 0xff);
    stream.write((value >>> 8) & 0xff);
    stream.write(value & 0xff);
  }
}
