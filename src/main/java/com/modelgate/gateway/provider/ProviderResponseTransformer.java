package com.modelgate.gateway.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public final class ProviderResponseTransformer {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final AnthropicMessageTransformer ANTHROPIC_TRANSFORMER =
      new AnthropicMessageTransformer(OBJECT_MAPPER);

  private ProviderResponseTransformer() {}

  public static ProviderResponse transform(String provider, String endpointPath, ProviderResponse response) {
    if (response == null) {
      return response;
    }
    String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    String path = pathWithoutQuery(endpointPath);
    if (response.streaming()) {
      return transformStream(normalizedProvider, path, response);
    }
    if ("anthropic".equals(normalizedProvider) && "/v1/chat/completions".equals(path)) {
      return jsonResponse(response, ANTHROPIC_TRANSFORMER.transformResponse(response.body()));
    }
    if (response.status() < 200 || response.status() >= 300) {
      return transformError(normalizedProvider, response);
    }
    return switch (normalizedProvider) {
      case "workers-ai" -> transformWorkersAi(path, response);
      case "cohere" -> transformCohere(path, response);
      case "bedrock" -> transformBedrock(path, response);
      case "vertex-ai" -> transformVertex(path, response);
      case "google" -> transformGoogle(path, response);
      case "palm" -> transformPalm(path, response);
      case "stability-ai" -> transformStability(path, response);
      case "recraft-ai" -> transformRecraft(path, response);
      case "segmind" -> transformSegmind(path, response);
      case "bytez" -> transformBytez(path, response);
      case "fireworks-ai" -> transformFireworks(path, response);
      case "ai21" -> transformAi21(path, response);
      case "nomic" -> transformNomic(path, response);
      case "reka-ai" -> transformReka(path, response);
      case "oracle" -> transformOracle(path, response);
      case "triton" -> transformTriton(path, response);
      case "ollama" -> transformOllama(path, response);
      case "together-ai" -> transformTogetherAi(path, response);
      case "sambanova" -> transformSambanova(path, response);
      case "azure-ai" -> transformAzureAi(path, response);
      case "voyage", "jina" -> annotateEmbeddingCompatible(normalizedProvider, response);
      case "302ai",
          "cerebras",
          "groq",
          "inception",
          "nvidia",
          "nvidia-nim",
          "openrouter",
          "deepseek",
          "perplexity-ai",
          "mistral-ai",
          "huggingface",
          "predibase",
          "lambda",
          "lepton",
          "modal",
          "upstage",
          "dashscope" ->
          annotateOpenAiCompatible(normalizedProvider, response);
      default -> response;
    };
  }

  private static ProviderResponse transformStream(String provider, String path, ProviderResponse response) {
    if ("workers-ai".equals(provider)
        && "/v1/chat/completions".equals(path)
        && response.bodyStream() != null) {
      return ProviderResponse.streaming(
          response.status(),
          new WorkersAiChatStream(response.bodyStream()),
          sseHeaders(response.headers()),
          response.attempts());
    }
    if ("cohere".equals(provider)
        && "/v1/chat/completions".equals(path)
        && response.bodyStream() != null) {
      return ProviderResponse.streaming(
          response.status(),
          new CohereChatStream(response.bodyStream()),
          sseHeaders(response.headers()),
          response.attempts());
    }
    if (("google".equals(provider) || "vertex-ai".equals(provider))
        && "/v1/chat/completions".equals(path)
        && response.bodyStream() != null) {
      return ProviderResponse.streaming(
          response.status(),
          new GeminiChatStream(response.bodyStream(), provider),
          sseHeaders(response.headers()),
          response.attempts());
    }
    if ("bedrock".equals(provider)
        && "/v1/chat/completions".equals(path)
        && response.bodyStream() != null) {
      return ProviderResponse.streaming(
          response.status(),
          new BedrockChatStream(response.bodyStream()),
          sseHeaders(response.headers()),
          response.attempts());
    }
    if ("oracle".equals(provider)
        && "/v1/chat/completions".equals(path)
        && response.bodyStream() != null) {
      return ProviderResponse.streaming(
          response.status(),
          new OracleChatStream(response.bodyStream()),
          sseHeaders(response.headers()),
          response.attempts());
    }
    if (isOpenAiCompatibleStreamProvider(provider)
        && "/v1/chat/completions".equals(path)
        && response.bodyStream() != null) {
      return ProviderResponse.streaming(
          response.status(),
          new OpenAiCompatibleChatStream(response.bodyStream(), provider),
          sseHeaders(response.headers()),
          response.attempts());
    }
    return response;
  }

  private static Map<String, String> sseHeaders(Map<String, String> headers) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (headers != null) {
      normalized.putAll(headers);
    }
    normalized.put("content-type", "text/event-stream");
    return normalized;
  }

  private static ProviderResponse transformWorkersAi(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null || !root.has("result")) {
      return response;
    }
    if ("/v1/embeddings".equals(path) && root.at("/result/data").isArray()) {
      return jsonResponse(response, write(embeddingResponse("workers-ai", "", root.at("/result/data"), -1), response.body()));
    }
    if ("/v1/images/generations".equals(path) && root.at("/result/image").isTextual()) {
      ObjectNode out = imageResponse("workers-ai");
      out.withArray("data").addObject().put("b64_json", root.at("/result/image").asText(""));
      return jsonResponse(response, write(out, response.body()));
    }
    if (!"/v1/chat/completions".equals(path)) {
      return response;
    }
    ObjectNode out = chatResponse("workers-ai", "", "stop");
    out.withArray("choices").get(0).withObject("/message").put("content", root.path("result").path("response").asText(""));
    return jsonResponse(response, write(out, response.body()));
  }

  private static ProviderResponse transformCohere(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null) {
      if (path.matches("/v1/files/[^/]+")) {
        ObjectNode out = OBJECT_MAPPER.createObjectNode();
        out.put("object", "file");
        out.put("deleted", true);
        out.put("id", lastPathSegment(path));
        return jsonResponse(response, write(out, response.body()));
      }
      if (path.matches("/v1/batches/[^/]+/cancel")) {
        ObjectNode out = OBJECT_MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("object", "batch");
        out.put("id", path.substring("/v1/batches/".length(), path.length() - "/cancel".length()));
        return jsonResponse(response, write(out, response.body()));
      }
      return response;
    }
    if ("/v1/files".equals(path)) {
      if (root.path("datasets").isArray()) {
        ObjectNode out = OBJECT_MAPPER.createObjectNode();
        out.put("object", "list");
        ArrayNode data = out.putArray("data");
        for (JsonNode dataset : root.path("datasets")) {
          data.add(cohereDatasetFile(dataset));
        }
        return jsonResponse(response, write(out, response.body()));
      }
      if (root.has("id")) {
        ObjectNode out = OBJECT_MAPPER.createObjectNode();
        out.put("id", root.path("id").asText(""));
        out.put("object", "file");
        out.put("created_at", System.currentTimeMillis() / 1000);
        out.put("filename", "");
        out.put("purpose", "");
        out.put("status", "uploaded");
        return jsonResponse(response, write(out, response.body()));
      }
    }
    if (path.matches("/v1/files/[^/]+") && root.has("dataset")) {
      return jsonResponse(response, write(cohereDatasetFile(root.path("dataset")), response.body()));
    }
    if ("/v1/batches".equals(path)) {
      if (root.has("job_id")) {
        ObjectNode out = cohereBatch(root);
        out.put("status", "in_progress");
        out.put("endpoint", "/v1/embed");
        return jsonResponse(response, write(out, response.body()));
      }
      if (root.path("embed_jobs").isArray()) {
        ObjectNode out = OBJECT_MAPPER.createObjectNode();
        out.put("object", "list");
        ArrayNode data = out.putArray("data");
        for (JsonNode job : root.path("embed_jobs")) {
          data.add(cohereBatch(job));
        }
        return jsonResponse(response, write(out, response.body()));
      }
    }
    if (path.matches("/v1/batches/[^/]+") && root.has("job_id")) {
      return jsonResponse(response, write(cohereBatch(root), response.body()));
    }
    if ("/v1/chat/completions".equals(path)) {
      ObjectNode out = chatResponse("cohere", root.path("id").asText(String.valueOf(System.currentTimeMillis())),
          finishReason(root.path("finish_reason").asText("")));
      out.withArray("choices").get(0).withObject("/message").put("content", cohereMessageText(root.path("message")));
      ObjectNode usage = usage(
          firstInt(root.at("/usage/tokens/input_tokens"), root.at("/usage/billed_units/input_tokens")),
          firstInt(root.at("/usage/tokens/output_tokens"), root.at("/usage/billed_units/output_tokens")),
          -1);
      out.set("usage", usage);
      return jsonResponse(response, write(out, response.body()));
    }
    if ("/v1/completions".equals(path) && root.path("generations").isArray()) {
      ObjectNode out = OBJECT_MAPPER.createObjectNode();
      out.put("id", root.path("id").asText(String.valueOf(System.currentTimeMillis())));
      out.put("object", "text_completion");
      out.put("created", System.currentTimeMillis() / 1000);
      out.put("model", "Unknown");
      out.put("provider", "cohere");
      ArrayNode choices = out.putArray("choices");
      for (int i = 0; i < root.path("generations").size(); i++) {
        ObjectNode choice = choices.addObject();
        choice.put("text", root.path("generations").get(i).path("text").asText(""));
        choice.put("index", i);
        choice.set("logprobs", OBJECT_MAPPER.nullNode());
        choice.put("finish_reason", "length");
      }
      return jsonResponse(response, write(out, response.body()));
    }
    if ("/v1/embeddings".equals(path)) {
      ObjectNode out = OBJECT_MAPPER.createObjectNode();
      out.put("object", "list");
      out.put("provider", "cohere");
      ArrayNode data = out.putArray("data");
      JsonNode embeddings = root.at("/embeddings/float");
      if (!embeddings.isArray()) {
        embeddings = root.path("embeddings");
      }
      if (embeddings.isArray()) {
        for (int i = 0; i < embeddings.size(); i++) {
          ObjectNode item = data.addObject();
          item.put("object", "embedding");
          item.set("embedding", embeddings.get(i).deepCopy());
          item.put("index", i);
        }
      }
      out.put("model", "");
      out.set("usage", usage(
          firstInt(root.at("/meta/tokens/input_tokens"), root.at("/meta/billed_units/input_tokens")),
          0,
          -1));
      return jsonResponse(response, write(out, response.body()));
    }
    return response;
  }

  private static ProviderResponse transformAzureAi(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null) {
      return response;
    }
    if ("/v1/chat/completions".equals(path) && root.path("content").isArray()) {
      return jsonResponse(response, write(providerAnthropicChat(root, "azure-ai"), response.body()));
    }
    if (root instanceof ObjectNode objectNode) {
      objectNode.put("provider", "azure-ai");
      return jsonResponse(response, write(objectNode, response.body()));
    }
    return response;
  }

  private static ObjectNode cohereDatasetFile(JsonNode dataset) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", dataset.path("id").asText(""));
    out.put("object", "file");
    out.put("bytes", 0);
    out.put("created_at", epochMillis(dataset.path("created_at").asText("")));
    out.put("filename", dataset.path("name").asText(""));
    out.put("purpose", dataset.path("dataset_type").asText(""));
    out.put("status", cohereDatasetStatus(dataset.path("validation_status").asText("")));
    out.put("status_details", dataset.path("validation_error").asText(""));
    return out;
  }

  private static String cohereDatasetStatus(String status) {
    String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "failed", "skipped" -> "error";
      case "validated" -> "processed";
      default -> "uploaded";
    };
  }

  private static ObjectNode cohereBatch(JsonNode job) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", job.path("job_id").asText(""));
    out.put("object", "batch");
    out.put("created_at", epochMillis(job.path("created_at").asText("")));
    out.put("status", job.path("status").asText(""));
    out.put("input_file_id", job.path("input_dataset_id").asText(""));
    out.put("output_file_id", job.path("output_dataset_id").asText(""));
    if (!job.path("meta").isMissingNode()) {
      out.set("metadata", job.path("meta").deepCopy());
    }
    return out;
  }

  private static ProviderResponse transformBedrock(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null) {
      return response;
    }
    if ("/v1/messages/count_tokens".equals(path) && root.has("inputTokens")) {
      ObjectNode out = OBJECT_MAPPER.createObjectNode();
      out.set("input_tokens", root.path("inputTokens").deepCopy());
      return jsonResponse(response, write(out, response.body()));
    }
    if ("/v1/messages/count_tokens".equals(path)) {
      return invalidProviderResponse(response, "bedrock", root);
    }
    if ("/v1/embeddings".equals(path)) {
      if (root.path("embedding").isArray()) {
        ArrayNode data = OBJECT_MAPPER.createArrayNode();
        data.add(root.path("embedding"));
        return jsonResponse(
            response,
            write(embeddingResponse("bedrock", "", data, root.path("inputTextTokenCount").asInt(0)), response.body()));
      }
      JsonNode embeddings = root.at("/embeddings/float");
      if (!embeddings.isArray()) {
        embeddings = root.path("embeddings");
      }
      if (embeddings.isArray()) {
        return jsonResponse(response, write(embeddingResponse("bedrock", "", embeddings, -1), response.body()));
      }
    }
    if ("/v1/images/generations".equals(path)) {
      if (root.path("artifacts").isArray()) {
        ObjectNode out = imageResponse("bedrock");
        ArrayNode data = out.withArray("data");
        for (JsonNode artifact : root.path("artifacts")) {
          data.addObject().put("b64_json", artifact.path("base64").asText(""));
        }
        return jsonResponse(response, write(out, response.body()));
      }
      if (root.path("images").isArray()) {
        ObjectNode out = imageResponse("bedrock");
        ArrayNode data = out.withArray("data");
        for (JsonNode image : root.path("images")) {
          data.addObject().put("b64_json", image.asText(""));
        }
        return jsonResponse(response, write(out, response.body()));
      }
    }
    if ("/v1/completions".equals(path)) {
      return transformBedrockCompletion(response, root);
    }
    if (!"/v1/chat/completions".equals(path) || !root.has("output")) {
      return response;
    }
    ObjectNode out = chatResponse("bedrock", String.valueOf(System.currentTimeMillis()),
        finishReason(root.path("stopReason").asText("")));
    ObjectNode message = out.withArray("choices").get(0).withObject("/message");
    message.put("content", textFromParts(root.at("/output/message/content")));
    ArrayNode toolCalls = bedrockToolCalls(root.at("/output/message/content"));
    if (!toolCalls.isEmpty()) {
      message.set("tool_calls", toolCalls);
    }
    out.set("usage", usage(
        root.at("/usage/inputTokens").asInt(0),
        root.at("/usage/outputTokens").asInt(0),
        root.at("/usage/totalTokens").asInt(0)));
    return jsonResponse(response, write(out, response.body()));
  }

  private static ArrayNode bedrockToolCalls(JsonNode contentParts) {
    ArrayNode toolCalls = OBJECT_MAPPER.createArrayNode();
    if (!contentParts.isArray()) {
      return toolCalls;
    }
    for (JsonNode part : contentParts) {
      JsonNode toolUse = part.path("toolUse");
      if (!toolUse.isObject()) {
        continue;
      }
      ObjectNode toolCall = toolCalls.addObject();
      toolCall.put("id", toolUse.path("toolUseId").asText(""));
      toolCall.put("type", "function");
      ObjectNode function = toolCall.putObject("function");
      function.put("name", toolUse.path("name").asText(""));
      function.put("arguments", jsonString(toolUse.path("input")));
    }
    return toolCalls;
  }

  private static ProviderResponse transformVertex(String path, ProviderResponse response) {
    if (path.matches("/v1/files/[^/]+")) {
      return vertexStorageFileMetadata(path, response);
    }
    JsonNode root = parse(response.body());
    if (root == null) {
      return response;
    }
    if ("/v1/batches".equals(path) && root.path("batchPredictionJobs").isArray()) {
      ObjectNode out = listResponse(root.path("batchPredictionJobs"), root.path("nextPageToken").asText(""), ProviderResponseTransformer::vertexBatch);
      return jsonResponse(response, write(out, response.body()));
    }
    if (path.matches("/v1/batches/[^/]+") && root.has("name")) {
      return jsonResponse(response, write(vertexBatch(root), response.body()));
    }
    if ("/v1/fine_tuning/jobs".equals(path) && root.path("tuningJobs").isArray()) {
      ObjectNode out = listResponse(root.path("tuningJobs"), root.path("nextPageToken").asText(""), ProviderResponseTransformer::vertexFineTune);
      return jsonResponse(response, write(out, response.body()));
    }
    if (path.matches("/v1/fine_tuning/jobs/[^/]+") && root.has("name")) {
      return jsonResponse(response, write(vertexFineTune(root), response.body()));
    }
    if ("/v1/embeddings".equals(path) && root.path("predictions").isArray()) {
      ArrayNode embeddings = OBJECT_MAPPER.createArrayNode();
      int tokenCount = 0;
      for (JsonNode prediction : root.path("predictions")) {
        if (prediction.at("/embeddings/values").isArray()) {
          embeddings.add(prediction.at("/embeddings/values"));
          tokenCount += prediction.at("/embeddings/statistics/token_count").asInt(0);
        } else if (prediction.path("textEmbedding").isArray()) {
          embeddings.add(prediction.path("textEmbedding"));
        }
      }
      return jsonResponse(response, write(embeddingResponse("vertex-ai", root.path("model").asText(""), embeddings, tokenCount), response.body()));
    }
    if ("/v1/images/generations".equals(path) && root.path("predictions").isArray()) {
      ObjectNode out = imageResponse("vertex-ai");
      ArrayNode data = out.withArray("data");
      for (JsonNode prediction : root.path("predictions")) {
        data.addObject().put("b64_json", prediction.path("bytesBase64Encoded").asText(""));
      }
      return jsonResponse(response, write(out, response.body()));
    }
    if ("/v1/chat/completions".equals(path) && root.path("content").isArray()) {
      return jsonResponse(response, write(vertexAnthropicChat(root), response.body()));
    }
    if ("/v1/chat/completions".equals(path) && root.path("choices").isArray()) {
      return annotateOpenAiCompatible("vertex-ai", response);
    }
    if (!"/v1/chat/completions".equals(path) || !root.has("candidates") && !root.has("usageMetadata")) {
      return response;
    }
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", String.valueOf(System.currentTimeMillis()));
    out.put("object", "chat.completion");
    out.put("created", System.currentTimeMillis() / 1000);
    out.put("model", root.path("modelVersion").asText(""));
    out.put("provider", "vertex-ai");
    ArrayNode choices = out.putArray("choices");
    JsonNode candidates = root.path("candidates");
    if (candidates.isArray()) {
      for (int i = 0; i < candidates.size(); i++) {
        JsonNode candidate = candidates.get(i);
        ObjectNode choice = choices.addObject();
        choice.put("index", candidate.path("index").isNumber() ? candidate.path("index").asInt() : i);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        addVertexMessageParts(message, candidate.at("/content/parts"));
        choice.put("finish_reason", finishReason(candidate.path("finishReason").asText("")));
        ObjectNode logprobs = vertexLogprobs(candidate.path("logprobsResult"));
        if (!logprobs.isEmpty()) {
          choice.set("logprobs", logprobs);
        }
        if (candidate.has("safetyRatings")) {
          choice.set("safetyRatings", candidate.path("safetyRatings").deepCopy());
        }
        if (candidate.has("groundingMetadata")) {
          choice.set("groundingMetadata", candidate.path("groundingMetadata").deepCopy());
        }
      }
    }
    out.set("usage", googleUsage(root.path("usageMetadata")));
    return jsonResponse(response, write(out, response.body()));
  }

  private static ProviderResponse transformGoogle(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null) {
      return response;
    }
    if ("/v1/embeddings".equals(path) && root.at("/embedding/values").isArray()) {
      ArrayNode embeddings = OBJECT_MAPPER.createArrayNode();
      embeddings.add(root.at("/embedding/values"));
      ObjectNode out = embeddingResponse("google", "", embeddings, -1);
      return jsonResponse(response, write(out, response.body()));
    }
    if (!"/v1/chat/completions".equals(path) || !root.has("candidates")) {
      return response;
    }
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", "modelgate-" + System.currentTimeMillis());
    out.put("object", "chat.completion");
    out.put("created", System.currentTimeMillis() / 1000);
    out.put("model", root.path("modelVersion").asText(""));
    out.put("provider", "google");
    ArrayNode choices = out.putArray("choices");
    JsonNode candidates = root.path("candidates");
    for (int i = 0; i < candidates.size(); i++) {
      JsonNode candidate = candidates.get(i);
      ObjectNode choice = choices.addObject();
      choice.put("index", candidate.path("index").isNumber() ? candidate.path("index").asInt() : i);
      ObjectNode message = choice.putObject("message");
      message.put("role", "assistant");
      ArrayNode toolCalls = OBJECT_MAPPER.createArrayNode();
      ArrayNode contentBlocks = OBJECT_MAPPER.createArrayNode();
      StringBuilder content = new StringBuilder();
      for (JsonNode part : candidate.at("/content/parts")) {
        if (part.path("functionCall").isObject()) {
          ObjectNode toolCall = toolCalls.addObject();
          toolCall.put("id", "modelgate-" + System.currentTimeMillis() + "-" + toolCalls.size());
          toolCall.put("type", "function");
          ObjectNode function = toolCall.putObject("function");
          function.put("name", part.path("functionCall").path("name").asText(""));
          function.put("arguments", jsonString(part.path("functionCall").path("args")));
        } else if (part.path("text").isTextual()) {
          if (part.path("thought").asBoolean(false)) {
            ObjectNode thinking = contentBlocks.addObject();
            thinking.put("type", "thinking");
            thinking.put("thinking", part.path("text").asText(""));
          } else {
            content.append(part.path("text").asText(""));
            ObjectNode text = contentBlocks.addObject();
            text.put("type", "text");
            text.put("text", part.path("text").asText(""));
          }
        } else if (part.path("inlineData").isObject()) {
          ObjectNode image = contentBlocks.addObject();
          image.put("type", "image_url");
          ObjectNode imageUrl = image.putObject("image_url");
          imageUrl.put("url", "data:" + part.at("/inlineData/mimeType").asText("")
              + ";base64," + part.at("/inlineData/data").asText(""));
        }
      }
      if (!toolCalls.isEmpty()) {
        message.set("tool_calls", toolCalls);
      }
      if (!content.isEmpty()) {
        message.put("content", content.toString());
      } else {
        message.put("content", "");
      }
      if (!contentBlocks.isEmpty()) {
        message.set("content_blocks", contentBlocks);
      }
      choice.put("finish_reason", finishReason(candidate.path("finishReason").asText("")));
      if (candidate.has("groundingMetadata")) {
        choice.set("groundingMetadata", candidate.path("groundingMetadata").deepCopy());
      }
    }
    out.set("usage", googleUsage(root.path("usageMetadata")));
    return jsonResponse(response, write(out, response.body()));
  }

  private static ProviderResponse transformStability(String path, ProviderResponse response) {
    if (!"/v1/images/generations".equals(path)) {
      return response;
    }
    JsonNode root = parse(response.body());
    if (root == null) {
      return response;
    }
    ObjectNode out = imageResponse("stability-ai");
    ArrayNode data = out.withArray("data");
    if (root.has("image")) {
      data.addObject().put("b64_json", root.path("image").asText(""));
    } else if (root.path("artifacts").isArray()) {
      for (JsonNode artifact : root.path("artifacts")) {
        data.addObject().put("b64_json", artifact.path("base64").asText(""));
      }
    } else {
      return response;
    }
    return jsonResponse(response, write(out, response.body()));
  }

  private static ProviderResponse transformPalm(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null) {
      return response;
    }
    if ("/v1/chat/completions".equals(path) && root.path("candidates").isArray()) {
      ObjectNode out = OBJECT_MAPPER.createObjectNode();
      out.put("id", String.valueOf(System.currentTimeMillis()));
      out.put("object", "chat.completion");
      out.put("created", System.currentTimeMillis() / 1000);
      out.put("model", "Unknown");
      out.put("provider", "palm");
      ArrayNode choices = out.putArray("choices");
      for (int i = 0; i < root.path("candidates").size(); i++) {
        ObjectNode choice = choices.addObject();
        choice.put("index", i);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", root.path("candidates").get(i).path("content").asText(""));
        choice.put("finish_reason", "length");
      }
      return jsonResponse(response, write(out, response.body()));
    }
    if ("/v1/completions".equals(path) && root.path("candidates").isArray()) {
      ObjectNode out = OBJECT_MAPPER.createObjectNode();
      out.put("id", String.valueOf(System.currentTimeMillis()));
      out.put("object", "completion");
      out.put("created", System.currentTimeMillis() / 1000);
      out.put("model", "Unknown");
      out.put("provider", "palm");
      ArrayNode choices = out.putArray("choices");
      for (int i = 0; i < root.path("candidates").size(); i++) {
        ObjectNode choice = choices.addObject();
        choice.put("text", root.path("candidates").get(i).path("output").asText(""));
        choice.put("index", i);
        choice.set("logprobs", OBJECT_MAPPER.nullNode());
        choice.put("finish_reason", "length");
      }
      return jsonResponse(response, write(out, response.body()));
    }
    if ("/v1/embeddings".equals(path) && root.at("/embedding/value").isArray()) {
      ArrayNode embeddings = OBJECT_MAPPER.createArrayNode();
      embeddings.add(root.at("/embedding/value"));
      ObjectNode out = embeddingResponse("palm", "", embeddings, -1);
      return jsonResponse(response, write(out, response.body()));
    }
    return response;
  }

  private static ProviderResponse transformFireworks(String path, ProviderResponse response) {
    if ("/v1/chat/completions".equals(path)) {
      return annotateOpenAiCompatible("fireworks-ai", response);
    }
    if (!"/v1/images/generations".equals(path)) {
      return response;
    }
    JsonNode root = parse(response.body());
    if (root == null || !root.isArray()) {
      return response;
    }
    ObjectNode out = imageResponse("fireworks-ai");
    ArrayNode data = out.withArray("data");
    for (JsonNode image : root) {
      ObjectNode item = data.addObject();
      item.put("b64_json", image.path("base64").asText(""));
      if (image.has("seed")) {
        item.set("seed", image.path("seed").deepCopy());
      }
      if (image.has("finishReason")) {
        item.set("finishReason", image.path("finishReason").deepCopy());
      }
    }
    return jsonResponse(response, write(out, response.body()));
  }

  private static ProviderResponse transformRecraft(String path, ProviderResponse response) {
    if (!"/v1/images/generations".equals(path)) {
      return response;
    }
    JsonNode root = parse(response.body());
    if (!(root instanceof ObjectNode objectNode) || !objectNode.has("data")) {
      return response;
    }
    objectNode.put("provider", "recraft-ai");
    return jsonResponse(response, write(objectNode, response.body()));
  }

  private static ProviderResponse transformAi21(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null) {
      return response;
    }
    if ("/v1/chat/completions".equals(path) && root.path("outputs").isArray()) {
      ObjectNode out = OBJECT_MAPPER.createObjectNode();
      out.put("id", root.path("id").asText(String.valueOf(System.currentTimeMillis())));
      out.put("object", "chat.completion");
      out.put("created", System.currentTimeMillis() / 1000);
      out.put("model", "");
      out.put("provider", "ai21");
      ArrayNode choices = out.putArray("choices");
      for (int i = 0; i < root.path("outputs").size(); i++) {
        JsonNode output = root.path("outputs").get(i);
        ObjectNode choice = choices.addObject();
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", output.path("text").asText(""));
        choice.put("index", i);
        choice.set("logprobs", OBJECT_MAPPER.nullNode());
        choice.put("finish_reason", output.at("/finishReason/reason").asText(""));
      }
      return jsonResponse(response, write(out, response.body()));
    }
    if ("/v1/completions".equals(path) && root.path("completions").isArray()) {
      ObjectNode out = OBJECT_MAPPER.createObjectNode();
      out.put("id", root.path("id").asText(String.valueOf(System.currentTimeMillis())));
      out.put("object", "text_completion");
      out.put("created", System.currentTimeMillis() / 1000);
      out.put("model", "");
      out.put("provider", "ai21");
      ArrayNode choices = out.putArray("choices");
      int completionTokens = 0;
      for (int i = 0; i < root.path("completions").size(); i++) {
        JsonNode completion = root.path("completions").get(i);
        completionTokens += completion.at("/data/tokens").isArray() ? completion.at("/data/tokens").size() : 0;
        ObjectNode choice = choices.addObject();
        choice.put("text", completion.at("/data/text").asText(""));
        choice.put("index", i);
        choice.set("logprobs", OBJECT_MAPPER.nullNode());
        choice.put("finish_reason", completion.at("/finishReason/reason").asText(""));
      }
      int promptTokens = root.at("/prompt/tokens").isArray() ? root.at("/prompt/tokens").size() : 0;
      out.set("usage", usage(promptTokens, completionTokens, promptTokens + completionTokens));
      return jsonResponse(response, write(out, response.body()));
    }
    if ("/v1/embeddings".equals(path) && root.path("results").isArray()) {
      ArrayNode embeddings = OBJECT_MAPPER.createArrayNode();
      for (JsonNode result : root.path("results")) {
        embeddings.add(result.path("embedding").deepCopy());
      }
      ObjectNode out = embeddingResponse("ai21", "", embeddings, -1);
      return jsonResponse(response, write(out, response.body()));
    }
    return response;
  }

  private static ProviderResponse transformNomic(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null || !"/v1/embeddings".equals(path) || !root.path("embeddings").isArray()) {
      return response;
    }
    ObjectNode out = embeddingResponse(
        "nomic",
        root.path("model").asText(""),
        root.path("embeddings"),
        root.at("/usage/total_tokens").asInt(-1));
    out.set("usage", usage(
        root.at("/usage/prompt_tokens").asInt(root.at("/usage/total_tokens").asInt(-1)),
        0,
        root.at("/usage/total_tokens").asInt(-1)));
    return jsonResponse(response, write(out, response.body()));
  }

  private static ProviderResponse transformReka(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null || !"/v1/chat/completions".equals(path) || !root.path("text").isTextual()) {
      return response;
    }
    ObjectNode out = chatResponse("reka-ai", "modelgate-" + System.currentTimeMillis(),
        root.path("finish_reason").asText(""));
    out.withArray("choices").get(0).withObject("/message").put("content", root.path("text").asText(""));
    int promptTokens = root.at("/metadata/input_tokens").asInt(0);
    int completionTokens = root.at("/metadata/generated_tokens").asInt(0);
    out.set("usage", usage(promptTokens, completionTokens, promptTokens + completionTokens));
    return jsonResponse(response, write(out, response.body()));
  }

  private static ProviderResponse transformSegmind(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null || !"/v1/images/generations".equals(path) || !root.has("image")) {
      return response;
    }
    ObjectNode out = imageResponse("segmind");
    ArrayNode data = out.withArray("data");
    JsonNode image = root.path("image");
    if (image.isArray()) {
      for (JsonNode item : image) {
        data.addObject().put("b64_json", item.asText(""));
      }
    } else {
      data.addObject().put("b64_json", image.asText(""));
    }
    return jsonResponse(response, write(out, response.body()));
  }

  private static ProviderResponse transformBytez(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null || !"/v1/chat/completions".equals(path) || !root.path("output").isObject()) {
      return response;
    }
    ObjectNode out = chatResponse("bytez", "modelgate-" + System.currentTimeMillis(), "stop");
    ((ObjectNode) out.withArray("choices").get(0)).set("message", root.path("output").deepCopy());
    out.set("usage", usage(-1, -1, -1));
    return jsonResponse(response, write(out, response.body()));
  }

  private static ProviderResponse transformTriton(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null || !"/v1/completions".equals(path) || !root.path("text_output").isTextual()) {
      return response;
    }
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", "modelgate-" + System.currentTimeMillis());
    out.put("object", "text_completion");
    out.put("created", System.currentTimeMillis() / 1000);
    out.put("model", root.path("model_name").asText(""));
    out.put("provider", "triton");
    ObjectNode choice = out.putArray("choices").addObject();
    choice.put("text", root.path("text_output").asText(""));
    choice.put("index", 0);
    choice.set("logprobs", OBJECT_MAPPER.nullNode());
    choice.put("finish_reason", "stop");
    out.set("usage", usage(-1, -1, -1));
    return jsonResponse(response, write(out, response.body()));
  }

  private static ProviderResponse transformOllama(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null) {
      return response;
    }
    if ("/v1/chat/completions".equals(path) && root.path("choices").isArray()) {
      return annotateOpenAiCompatible("ollama", response);
    }
    if ("/v1/embeddings".equals(path) && root.path("embedding").isArray()) {
      ArrayNode embeddings = OBJECT_MAPPER.createArrayNode();
      embeddings.add(root.path("embedding"));
      ObjectNode out = embeddingResponse("ollama", root.path("model").asText(""), embeddings, -1);
      return jsonResponse(response, write(out, response.body()));
    }
    return response;
  }

  private static ProviderResponse transformTogetherAi(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (!(root instanceof ObjectNode objectNode)) {
      return response;
    }
    if ("/v1/chat/completions".equals(path) || "/v1/completions".equals(path)) {
      JsonNode choices = objectNode.path("choices");
      if (choices.isArray()) {
        for (JsonNode choice : choices) {
          if (choice instanceof ObjectNode choiceObject && choice.path("finish_reason").isTextual()) {
            choiceObject.put("finish_reason", finishReason(choice.path("finish_reason").asText("")));
          }
        }
      }
      objectNode.put("provider", "together-ai");
      return jsonResponse(response, write(objectNode, response.body()));
    }
    if ("/v1/embeddings".equals(path)) {
      return annotateEmbeddingCompatible("together-ai", response);
    }
    return response;
  }

  private static ProviderResponse transformOracle(String path, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null || !"/v1/chat/completions".equals(path) || !root.path("chatResponse").isObject()) {
      return response;
    }
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", headerValue(response.headers(), "opc-request-id", "modelgate-" + System.currentTimeMillis()));
    out.put("object", "chat.completion");
    out.put("created", System.currentTimeMillis() / 1000);
    out.put("model", root.path("modelId").asText(""));
    out.put("provider", "oracle");
    ArrayNode choices = out.putArray("choices");
    JsonNode oracleChoices = root.at("/chatResponse/choices");
    if (oracleChoices.isArray()) {
      for (JsonNode oracleChoice : oracleChoices) {
        ObjectNode choice = choices.addObject();
        choice.put("index", oracleChoice.path("index").asInt(choices.size() - 1));
        ObjectNode message = choice.putObject("message");
        message.put("role", openAiRole(oracleChoice.at("/message/role").asText("ASSISTANT")));
        message.put("content", textFromOracleContent(oracleChoice.at("/message/content")));
        choice.put("finish_reason", finishReason(oracleChoice.path("finishReason").asText("")));
      }
    }
    out.set("usage", usage(
        root.at("/chatResponse/usage/promptTokens").asInt(0),
        root.at("/chatResponse/usage/completionTokens").asInt(0),
        root.at("/chatResponse/usage/totalTokens").asInt(0)));
    return jsonResponse(response, write(out, response.body()));
  }

  private static String textFromOracleContent(JsonNode content) {
    if (!content.isArray()) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    for (JsonNode item : content) {
      if ("TEXT".equals(item.path("type").asText(""))) {
        text.append(item.path("text").asText(""));
      }
    }
    return text.toString();
  }

  private static String openAiRole(String role) {
    return switch (role) {
      case "SYSTEM" -> "system";
      case "USER" -> "user";
      case "TOOL" -> "tool";
      default -> "assistant";
    };
  }

  private static ProviderResponse annotateOpenAiCompatible(String provider, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (!(root instanceof ObjectNode objectNode)) {
      return response;
    }
    if (objectNode.has("choices") || objectNode.has("data")) {
      objectNode.put("provider", provider);
      if ("openrouter".equals(provider)) {
        enrichOpenRouterReasoning(objectNode);
      }
      return jsonResponse(response, write(objectNode, response.body()));
    }
    return response;
  }

  private static ProviderResponse transformSambanova(String path, ProviderResponse response) {
    ProviderResponse annotated = annotateOpenAiCompatible("sambanova", response);
    if (!"/v1/chat/completions".equals(path)) {
      return annotated;
    }
    JsonNode root = parse(annotated.body());
    if (!(root instanceof ObjectNode objectNode) || !root.path("choices").isArray()) {
      return annotated;
    }
    for (JsonNode choice : root.path("choices")) {
      if (choice.path("message") instanceof ObjectNode message && !message.path("role").isTextual()) {
        message.put("role", "assistant");
      }
    }
    return jsonResponse(annotated, write(objectNode, annotated.body()));
  }

  private static void enrichOpenRouterReasoning(ObjectNode response) {
    JsonNode choices = response.path("choices");
    if (!choices.isArray()) {
      return;
    }
    for (JsonNode choice : choices) {
      JsonNode message = choice.path("message");
      if (!(message instanceof ObjectNode messageObject)) {
        continue;
      }
      ArrayNode contentBlocks = OBJECT_MAPPER.createArrayNode();
      if (message.path("reasoning").isTextual()) {
        ObjectNode thinking = OBJECT_MAPPER.createObjectNode();
        thinking.put("type", "thinking");
        thinking.set("thinking", message.path("reasoning").deepCopy());
        if (message.has("reasoning_details")) {
          thinking.set("reasoning_details", message.path("reasoning_details").deepCopy());
        }
        contentBlocks.add(thinking);
      }
      if (message.path("content").isTextual()) {
        ObjectNode text = OBJECT_MAPPER.createObjectNode();
        text.put("type", "text");
        text.set("text", message.path("content").deepCopy());
        contentBlocks.add(text);
      }
      if (!contentBlocks.isEmpty()) {
        messageObject.set("content_blocks", contentBlocks);
      }
    }
  }

  private static ProviderResponse annotateEmbeddingCompatible(String provider, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (!(root instanceof ObjectNode objectNode) || !objectNode.has("data")) {
      return response;
    }
    objectNode.put("provider", provider);
    if ("voyage".equals(provider) && objectNode.at("/usage/total_tokens").isNumber()
        && objectNode.at("/usage/prompt_tokens").isMissingNode()) {
      ((ObjectNode) objectNode.path("usage")).put("prompt_tokens", objectNode.at("/usage/total_tokens").asInt());
    }
    return jsonResponse(response, write(objectNode, response.body()));
  }

  private static ProviderResponse transformError(String provider, ProviderResponse response) {
    JsonNode root = parse(response.body());
    if (root == null) {
      return response;
    }
    ObjectNode error = OBJECT_MAPPER.createObjectNode();
    error.put("message", errorMessage(provider, root));
    JsonNode type = errorType(root);
    if ("bytez".equals(provider) && type == null && root.path("error").isTextual()) {
      type = OBJECT_MAPPER.getNodeFactory().textNode(String.valueOf(response.status()));
    }
    if ("oracle".equals(provider) && type == null && root.path("code").isValueNode()) {
      type = root.path("code").deepCopy();
    }
    error.set("type", type == null ? OBJECT_MAPPER.nullNode() : type);
    JsonNode param = errorParam(root);
    error.set("param", param == null ? OBJECT_MAPPER.nullNode() : param);
    JsonNode code = errorCode(root, response.status());
    error.set("code", code == null ? OBJECT_MAPPER.nullNode() : code);
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.set("error", error);
    out.put("provider", provider);
    return jsonResponse(response, write(out, response.body()));
  }

  private static ProviderResponse invalidProviderResponse(ProviderResponse response, String provider, JsonNode root) {
    ObjectNode error = OBJECT_MAPPER.createObjectNode();
    error.put("message", "Invalid response received from " + provider + ": " + write(root, root.toString()));
    error.set("type", OBJECT_MAPPER.nullNode());
    error.set("param", OBJECT_MAPPER.nullNode());
    error.set("code", OBJECT_MAPPER.nullNode());
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.set("error", error);
    out.put("provider", provider);
    return jsonResponse(response, write(out, response.body()));
  }

  private static String errorMessage(String provider, JsonNode root) {
    if ("workers-ai".equals(provider) && root.path("errors").isArray()) {
      StringBuilder message = new StringBuilder();
      for (JsonNode error : root.path("errors")) {
        if (!message.isEmpty()) {
          message.append(", ");
        }
        message.append("Error ")
            .append(error.path("code").asText(""))
            .append(":")
            .append(error.path("message").asText(""));
      }
      return message.toString();
    }
    if (root.at("/error/message").isTextual()) {
      return root.at("/error/message").asText();
    }
    if (root.path("message").isTextual()) {
      return root.path("message").asText();
    }
    if (root.path("error").isTextual()) {
      return root.path("error").asText();
    }
    if (root.path("errors").isArray()) {
      StringBuilder message = new StringBuilder();
      for (JsonNode item : root.path("errors")) {
        if (!message.isEmpty()) {
          message.append(", ");
        }
        message.append(item.asText());
      }
      return message.toString();
    }
    if ("nomic".equals(provider) && root.path("detail").isArray() && !root.path("detail").isEmpty()) {
      JsonNode firstError = root.path("detail").get(0);
      StringBuilder field = new StringBuilder();
      if (firstError.path("loc").isArray()) {
        for (JsonNode item : firstError.path("loc")) {
          if (!field.isEmpty()) {
            field.append(".");
          }
          field.append(item.asText());
        }
      }
      String message = firstError.path("msg").asText(root.toString());
      return field.isEmpty() ? message : field + ": " + message;
    }
    if ("reka-ai".equals(provider) && root.has("detail")) {
      return write(root.path("detail"), root.path("detail").toString());
    }
    if ("segmind".equals(provider) && root.path("html-message").isTextual()) {
      return root.path("html-message").asText();
    }
    if (root.path("detail").isTextual()) {
      return root.path("detail").asText();
    }
    return root.toString();
  }

  private static JsonNode errorType(JsonNode root) {
    if (root.at("/error/type").isValueNode()) {
      return root.at("/error/type").deepCopy();
    }
    if (root.at("/error/status").isValueNode()) {
      return root.at("/error/status").deepCopy();
    }
    if (root.path("type").isValueNode()) {
      return root.path("type").deepCopy();
    }
    if (root.path("name").isValueNode()) {
      return root.path("name").deepCopy();
    }
    if (root.path("detail").isArray() && !root.path("detail").isEmpty()
        && root.path("detail").get(0).path("type").isValueNode()) {
      return root.path("detail").get(0).path("type").deepCopy();
    }
    return null;
  }

  private static JsonNode errorParam(JsonNode root) {
    if (root.at("/error/param").isValueNode()) {
      return root.at("/error/param").deepCopy();
    }
    if (root.path("param").isValueNode()) {
      return root.path("param").deepCopy();
    }
    if (root.path("id").isValueNode()) {
      return root.path("id").deepCopy();
    }
    return null;
  }

  private static JsonNode errorCode(JsonNode root, int status) {
    if (root.at("/error/code").isValueNode()) {
      return textNode(root.at("/error/code"));
    }
    if (root.path("code").isValueNode()) {
      return textNode(root.path("code"));
    }
    if (root.path("error").isTextual()) {
      return OBJECT_MAPPER.getNodeFactory().textNode(String.valueOf(status));
    }
    return null;
  }

  private static JsonNode textNode(JsonNode value) {
    return OBJECT_MAPPER.getNodeFactory().textNode(value.asText());
  }

  private static String headerValue(Map<String, String> headers, String name, String fallback) {
    if (headers == null || headers.isEmpty()) {
      return fallback;
    }
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return fallback;
  }

  private static ProviderResponse transformBedrockCompletion(ProviderResponse response, JsonNode root) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", root.path("id").asText(String.valueOf(System.currentTimeMillis())));
    out.put("object", "text_completion");
    out.put("created", System.currentTimeMillis() / 1000);
    out.put("model", "");
    out.put("provider", "bedrock");
    ArrayNode choices = out.putArray("choices");
    int promptTokens = root.path("inputTextTokenCount").asInt(0);
    int completionTokens = 0;
    if (root.path("results").isArray()) {
      for (int i = 0; i < root.path("results").size(); i++) {
        JsonNode result = root.path("results").get(i);
        completionTokens += result.path("tokenCount").asInt(0);
        ObjectNode choice = choices.addObject();
        choice.put("text", result.path("outputText").asText(""));
        choice.put("index", i);
        choice.set("logprobs", OBJECT_MAPPER.nullNode());
        choice.put("finish_reason", finishReason(result.path("completionReason").asText("")));
      }
    } else if (root.path("generation").isTextual()) {
      promptTokens = root.path("prompt_token_count").asInt(0);
      completionTokens = root.path("generation_token_count").asInt(0);
      ObjectNode choice = choices.addObject();
      choice.put("text", root.path("generation").asText(""));
      choice.put("index", 0);
      choice.set("logprobs", OBJECT_MAPPER.nullNode());
      choice.put("finish_reason", finishReason(root.path("stop_reason").asText("")));
    } else if (root.path("completion").isTextual()) {
      ObjectNode choice = choices.addObject();
      choice.put("text", root.path("completion").asText(""));
      choice.put("index", 0);
      choice.set("logprobs", OBJECT_MAPPER.nullNode());
      choice.put("finish_reason", finishReason(root.path("stop_reason").asText("")));
    } else if (root.path("generations").isArray()) {
      for (int i = 0; i < root.path("generations").size(); i++) {
        JsonNode generation = root.path("generations").get(i);
        ObjectNode choice = choices.addObject();
        choice.put("text", generation.path("text").asText(""));
        choice.put("index", i);
        choice.set("logprobs", OBJECT_MAPPER.nullNode());
        choice.put("finish_reason", finishReason(generation.path("finish_reason").asText("")));
      }
    } else if (root.path("outputs").isArray() && !root.path("outputs").isEmpty()) {
      ObjectNode choice = choices.addObject();
      choice.put("text", root.path("outputs").get(0).path("text").asText(""));
      choice.put("index", 0);
      choice.set("logprobs", OBJECT_MAPPER.nullNode());
      choice.put("finish_reason", finishReason(root.path("outputs").get(0).path("stop_reason").asText("")));
    } else {
      return response;
    }
    out.set("usage", usage(promptTokens, completionTokens, promptTokens + completionTokens));
    return jsonResponse(response, write(out, response.body()));
  }

  private static ObjectNode embeddingResponse(String provider, String model, JsonNode embeddings, int tokenCount) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("object", "list");
    out.put("provider", provider);
    out.put("model", model);
    ArrayNode data = out.putArray("data");
    if (embeddings.isArray()) {
      for (int i = 0; i < embeddings.size(); i++) {
        ObjectNode item = data.addObject();
        item.put("object", "embedding");
        item.set("embedding", embeddings.get(i).deepCopy());
        item.put("index", i);
      }
    }
    out.set("usage", usage(tokenCount, -1, tokenCount));
    return out;
  }

  private static ObjectNode chatResponse(String provider, String id, String finishReason) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", id);
    out.put("object", "chat.completion");
    out.put("created", System.currentTimeMillis() / 1000);
    out.put("model", "");
    out.put("provider", provider);
    ObjectNode choice = out.putArray("choices").addObject();
    choice.put("index", 0);
    ObjectNode message = choice.putObject("message");
    message.put("role", "assistant");
    message.put("content", "");
    choice.put("finish_reason", finishReason);
    return out;
  }

  private static ObjectNode imageResponse(String provider) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("created", System.currentTimeMillis() / 1000);
    out.put("provider", provider);
    out.putArray("data");
    return out;
  }

  private static ObjectNode usage(int promptTokens, int completionTokens, int totalTokens) {
    ObjectNode usage = OBJECT_MAPPER.createObjectNode();
    usage.put("prompt_tokens", promptTokens);
    if (completionTokens >= 0) {
      usage.put("completion_tokens", completionTokens);
    }
    usage.put("total_tokens", totalTokens >= 0 ? totalTokens : promptTokens + Math.max(completionTokens, 0));
    return usage;
  }

  private static ObjectNode googleUsage(JsonNode usageMetadata) {
    ObjectNode usage = usage(
        usageMetadata.path("promptTokenCount").asInt(0),
        usageMetadata.path("candidatesTokenCount").asInt(0),
        usageMetadata.path("totalTokenCount").asInt(0));
    ObjectNode completionDetails = OBJECT_MAPPER.createObjectNode();
    completionDetails.put("reasoning_tokens", usageMetadata.path("thoughtsTokenCount").asInt(0));
    completionDetails.put("audio_tokens", audioTokenCount(usageMetadata.path("candidatesTokensDetails")));
    usage.set("completion_tokens_details", completionDetails);
    ObjectNode promptDetails = OBJECT_MAPPER.createObjectNode();
    promptDetails.put("cached_tokens", usageMetadata.path("cachedContentTokenCount").asInt(0));
    promptDetails.put("audio_tokens", audioTokenCount(usageMetadata.path("promptTokensDetails")));
    usage.set("prompt_tokens_details", promptDetails);
    return usage;
  }

  private static int audioTokenCount(JsonNode tokenDetails) {
    if (!tokenDetails.isArray()) {
      return 0;
    }
    int total = 0;
    for (JsonNode detail : tokenDetails) {
      if ("AUDIO".equalsIgnoreCase(detail.path("modality").asText())) {
        total += detail.path("tokenCount").asInt(0);
      }
    }
    return total;
  }

  private static ObjectNode listResponse(JsonNode records, String nextPageToken, Function<JsonNode, ObjectNode> mapper) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    ArrayNode data = out.putArray("data");
    if (records.isArray()) {
      for (JsonNode record : records) {
        data.add(mapper.apply(record));
      }
    }
    out.put("object", "list");
    if (!data.isEmpty()) {
      out.put("first_id", data.get(0).path("id").asText(""));
      out.put("last_id", data.get(data.size() - 1).path("id").asText(""));
    }
    out.put("has_more", nextPageToken != null && !nextPageToken.isBlank());
    return out;
  }

  private static ObjectNode vertexBatch(JsonNode record) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    String model = record.path("model").asText("");
    String outputPrefix = record.at("/outputInfo/gcsOutputDirectory").asText("");
    if (outputPrefix.isBlank()) {
      outputPrefix = record.at("/outputConfig/gcsDestination/outputUriPrefix").asText("");
    }
    String outputFileId = outputPrefix;
    if (!outputFileId.endsWith(".jsonl")) {
      if (!outputFileId.endsWith("/")) {
        outputFileId += "/";
      }
      outputFileId += model.contains("embedding") ? "000000000000.jsonl" : "predictions.jsonl";
    }
    out.put("id", lastPathSegment(record.path("name").asText("")));
    out.put("object", "batch");
    out.put("endpoint", model.contains("embedding") ? "/v1/embeddings" : "/v1/chat/completions");
    out.put("input_file_id", encodeUrl(record.at("/inputConfig/gcsSource/uris/0").asText("")));
    out.set("completion_window", OBJECT_MAPPER.nullNode());
    out.put("status", vertexBatchStatus(record.path("state").asText("")));
    out.put("output_file_id", encodeUrl(outputFileId));
    out.put("error_file_id", encodeUrl(record.at("/outputConfig/gcsDestination/outputUriPrefix").asText("")));
    out.put("created_at", epochMillis(record.path("createTime").asText("")));
    putTimeForVertexBatchStatus(out, record.path("state").asText(""), record.path("endTime").asText(""));
    out.put("in_progress_at", epochMillis(record.path("startTime").asText("")));
    putTimeForVertexBatchStatus(out, record.path("state").asText(""), record.path("updateTime").asText(""));
    ObjectNode requestCounts = out.putObject("request_counts");
    int completed = intFromText(record.at("/completionStats/successfulCount"));
    int failed = intFromText(record.at("/completionStats/failedCount"));
    requestCounts.put("total", completed + failed);
    requestCounts.put("completed", completed);
    requestCounts.put("failed", failed);
    if (record.has("error")) {
      ObjectNode errors = out.putObject("errors");
      errors.put("object", "list");
      errors.putArray("data").add(record.path("error").deepCopy());
    }
    return out;
  }

  private static ObjectNode vertexFineTune(JsonNode record) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", lastPathSegment(record.path("name").asText("")));
    out.put("object", "finetune");
    out.put("status", vertexFineTuneStatus(record.path("state").asText("")));
    out.put("created_at", epochMillis(record.path("createTime").asText("")));
    if (record.has("error")) {
      out.set("error", record.path("error").deepCopy());
    }
    out.put("fine_tuned_model", record.at("/tunedModel/model").asText(""));
    if (record.has("endTime")) {
      out.put("finished_at", epochMillis(record.path("endTime").asText("")));
    }
    ObjectNode hyperparameters = out.putObject("hyperparameters");
    hyperparameters.set("batch_size", record.at("/supervisedTuningSpec/hyperParameters/adapterSize").deepCopy());
    hyperparameters.set(
        "learning_rate_multiplier",
        record.at("/supervisedTuningSpec/hyperParameters/learningRateMultiplier").deepCopy());
    hyperparameters.set("n_epochs", record.at("/supervisedTuningSpec/hyperParameters/epochCount").deepCopy());
    out.put("model", firstText(record.path("baseModel").asText(null), record.at("/source_model/baseModel").asText("")));
    out.set("trained_tokens", record.at("/tuningDataStats/supervisedTuningDataStats/totalBillableTokenCount").deepCopy());
    out.put("training_file", encodeUrl(record.at("/supervisedTuningSpec/trainingDatasetUri").asText("")));
    if (record.at("/supervisedTuningSpec/validationDatasetUri").isTextual()) {
      out.put("validation_file", encodeUrl(record.at("/supervisedTuningSpec/validationDatasetUri").asText("")));
    }
    return out;
  }

  private static ProviderResponse vertexStorageFileMetadata(String path, ProviderResponse response) {
    if (response.status() != 200) {
      ObjectNode error = OBJECT_MAPPER.createObjectNode();
      error.put("message", "File not found");
      error.put("status", "failure");
      error.put("provider", "google-vertex-ai");
      return jsonResponse(response.withStatus(500), write(error, "{\"message\":\"File not found\"}"));
    }
    String id = lastPathSegment(path);
    String decodedId = URLDecoder.decode(id, StandardCharsets.UTF_8);
    String objectPath = decodedId.startsWith("gs://") ? decodedId.substring("gs://".length()) : decodedId;
    String filename = lastPathSegment(objectPath);
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("bytes", parseLongHeader(response.headers(), "content-length"));
    out.put("updatedAt", headerDateMillis(response.headers().get("last-modified")));
    out.put("createdAt", headerDateMillis(response.headers().get("date")));
    out.put("id", id);
    out.put("filename", filename);
    out.set("purpose", OBJECT_MAPPER.nullNode());
    out.put("status", "processed");
    out.put("object", "file");
    return jsonResponse(response, write(out, response.body()));
  }

  private static long parseLongHeader(Map<String, String> headers, String name) {
    try {
      return Long.parseLong(headers.getOrDefault(name, "0"));
    } catch (NumberFormatException exception) {
      return 0L;
    }
  }

  private static long headerDateMillis(String value) {
    if (value == null || value.isBlank()) {
      return Instant.now().toEpochMilli();
    }
    try {
      return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
    } catch (Exception exception) {
      return Instant.now().toEpochMilli();
    }
  }

  private static ObjectNode vertexAnthropicChat(JsonNode root) {
    return providerAnthropicChat(root, "vertex-ai");
  }

  private static ObjectNode providerAnthropicChat(JsonNode root, String provider) {
    int inputTokens = root.path("usage").path("input_tokens").asInt(0);
    int outputTokens = root.path("usage").path("output_tokens").asInt(0);
    int cacheCreationTokens = root.path("usage").path("cache_creation_input_tokens").asInt(0);
    int cacheReadTokens = root.path("usage").path("cache_read_input_tokens").asInt(0);
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", root.path("id").asText(""));
    out.put("object", "chat.completion");
    out.put("created", System.currentTimeMillis() / 1000);
    out.put("model", root.path("model").asText(""));
    out.put("provider", provider);
    ObjectNode choice = out.putArray("choices").addObject();
    choice.put("index", 0);
    ObjectNode message = choice.putObject("message");
    message.put("role", "assistant");
    ArrayNode toolCalls = OBJECT_MAPPER.createArrayNode();
    ArrayNode contentBlocks = OBJECT_MAPPER.createArrayNode();
    StringBuilder content = new StringBuilder();
    for (JsonNode part : root.path("content")) {
      String type = part.path("type").asText("");
      if ("text".equals(type)) {
        content.append(part.path("text").asText(""));
        ObjectNode text = contentBlocks.addObject();
        text.put("type", "text");
        text.put("text", part.path("text").asText(""));
      } else if ("thinking".equals(type)) {
        ObjectNode thinking = contentBlocks.addObject();
        thinking.put("type", "thinking");
        thinking.put("thinking", part.path("text").asText(""));
      } else if ("tool_use".equals(type)) {
        ObjectNode toolCall = toolCalls.addObject();
        toolCall.put("id", part.path("id").asText(""));
        toolCall.put("type", "function");
        ObjectNode function = toolCall.putObject("function");
        function.put("name", part.path("name").asText(""));
        function.put("arguments", jsonString(part.path("input")));
      }
    }
    message.put("content", content.toString());
    if (!contentBlocks.isEmpty()) {
      message.set("content_blocks", contentBlocks);
    }
    if (!toolCalls.isEmpty()) {
      message.set("tool_calls", toolCalls);
    }
    choice.set("logprobs", OBJECT_MAPPER.nullNode());
    choice.put("finish_reason", finishReason(root.path("stop_reason").asText("")));
    ObjectNode usage = out.putObject("usage");
    usage.put("prompt_tokens", inputTokens);
    usage.put("completion_tokens", outputTokens);
    usage.put("total_tokens", inputTokens + outputTokens + cacheCreationTokens + cacheReadTokens);
    usage.putObject("prompt_tokens_details").put("cached_tokens", cacheReadTokens);
    if (cacheCreationTokens > 0 || cacheReadTokens > 0) {
      usage.put("cache_creation_input_tokens", cacheCreationTokens);
      usage.put("cache_read_input_tokens", cacheReadTokens);
    }
    return out;
  }

  private static void putTimeForVertexBatchStatus(ObjectNode out, String state, String timestamp) {
    String normalized = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
    switch (normalized) {
      case "JOB_STATE_FAILED", "JOB_STATE_EXPIRED" -> out.put("failed_at", epochMillis(timestamp));
      case "JOB_STATE_SUCCEEDED" -> out.put("completed_at", epochMillis(timestamp));
      case "JOB_STATE_CANCELLED" -> out.put("cancelled_at", epochMillis(timestamp));
      default -> {
      }
    }
  }

  private static String vertexBatchStatus(String state) {
    String normalized = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "JOB_STATE_CANCELLING" -> "cancelling";
      case "JOB_STATE_CANCELLED" -> "cancelled";
      case "JOB_STATE_EXPIRED" -> "expired";
      case "JOB_STATE_FAILED" -> "failed";
      case "JOB_STATE_PARTIALLY_SUCCEEDED", "JOB_STATE_SUCCEEDED" -> "completed";
      case "JOB_STATE_RUNNING", "JOB_STATE_UPDATING" -> "in_progress";
      default -> "validating";
    };
  }

  private static String vertexFineTuneStatus(String state) {
    String normalized = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "JOB_STATE_CANCELLED", "JOB_STATE_CANCELLING", "JOB_STATE_EXPIRED" -> "cancelled";
      case "JOB_STATE_FAILED" -> "failed";
      case "JOB_STATE_PARTIALLY_SUCCEEDED", "JOB_STATE_SUCCEEDED" -> "succeeded";
      case "JOB_STATE_RUNNING", "JOB_STATE_UPDATING" -> "running";
      default -> "queued";
    };
  }

  private static void addVertexMessageParts(ObjectNode message, JsonNode parts) {
    ArrayNode toolCalls = OBJECT_MAPPER.createArrayNode();
    ArrayNode contentBlocks = OBJECT_MAPPER.createArrayNode();
    StringBuilder content = new StringBuilder();
    if (parts.isArray()) {
      for (JsonNode part : parts) {
        if (part.path("functionCall").isObject()) {
          ObjectNode toolCall = toolCalls.addObject();
          toolCall.put("id", "modelgate-" + System.currentTimeMillis() + "-" + toolCalls.size());
          toolCall.put("type", "function");
          ObjectNode function = toolCall.putObject("function");
          function.put("name", part.at("/functionCall/name").asText(""));
          function.put("arguments", jsonString(part.at("/functionCall/args")));
          if (part.path("thoughtSignature").isTextual()) {
            function.put("thought_signature", part.path("thoughtSignature").asText(""));
          }
        } else if (part.path("text").isTextual()) {
          if (part.path("thought").asBoolean(false)) {
            ObjectNode thinking = contentBlocks.addObject();
            thinking.put("type", "thinking");
            thinking.put("thinking", part.path("text").asText(""));
          } else {
            content.append(part.path("text").asText(""));
            ObjectNode text = contentBlocks.addObject();
            text.put("type", "text");
            text.put("text", part.path("text").asText(""));
          }
        } else if (part.path("inlineData").isObject()) {
          ObjectNode image = contentBlocks.addObject();
          image.put("type", "image_url");
          image.putObject("image_url").put("url", "data:" + part.at("/inlineData/mimeType").asText("")
              + ";base64," + part.at("/inlineData/data").asText(""));
        }
      }
    }
    message.put("content", content.toString());
    if (!toolCalls.isEmpty()) {
      message.set("tool_calls", toolCalls);
    }
    if (!contentBlocks.isEmpty()) {
      message.set("content_blocks", contentBlocks);
    }
  }

  private static ObjectNode vertexLogprobs(JsonNode logprobsResult) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    if (!logprobsResult.isObject()) {
      return out;
    }
    ArrayNode content = OBJECT_MAPPER.createArrayNode();
    JsonNode chosen = logprobsResult.path("chosenCandidates");
    if (chosen.isArray()) {
      for (JsonNode candidate : chosen) {
        ObjectNode item = content.addObject();
        String token = candidate.path("token").asText("");
        item.put("token", token);
        item.set("logprob", candidate.path("logProbability").deepCopy());
        ArrayNode bytes = item.putArray("bytes");
        token.chars().forEach(bytes::add);
      }
    }
    JsonNode topCandidates = logprobsResult.path("topCandidates");
    if (topCandidates.isArray()) {
      for (int i = 0; i < topCandidates.size() && i < content.size(); i++) {
        ArrayNode topLogprobs = ((ObjectNode) content.get(i)).putArray("top_logprobs");
        JsonNode candidates = topCandidates.get(i).path("candidates");
        if (candidates.isArray()) {
          for (JsonNode candidate : candidates) {
            ObjectNode item = topLogprobs.addObject();
            String token = candidate.path("token").asText("");
            item.put("token", token);
            item.set("logprob", candidate.path("logProbability").deepCopy());
            ArrayNode bytes = item.putArray("bytes");
            token.chars().forEach(bytes::add);
          }
        }
      }
    }
    if (!content.isEmpty()) {
      out.set("content", content);
    }
    return out;
  }

  private static int intFromText(JsonNode value) {
    if (value.isNumber()) {
      return value.asInt();
    }
    try {
      return Integer.parseInt(value.asText("0"));
    } catch (NumberFormatException exception) {
      return 0;
    }
  }

  private static String encodeUrl(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
        .replace("+", "%20");
  }

  private static String jsonString(JsonNode value) {
    try {
      return OBJECT_MAPPER.writeValueAsString(value);
    } catch (Exception exception) {
      return "{}";
    }
  }

  private static String cohereMessageText(JsonNode message) {
    JsonNode content = message.path("content");
    if (!content.isArray()) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    for (JsonNode item : content) {
      if ("text".equals(item.path("type").asText())) {
        text.append(item.path("text").asText(""));
      }
    }
    return text.toString();
  }

  private static String textFromParts(JsonNode parts) {
    if (!parts.isArray()) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    for (JsonNode part : parts) {
      if (part.has("text")) {
        if (!text.isEmpty()) {
          text.append('\n');
        }
        text.append(part.path("text").asText(""));
      }
    }
    return text.toString();
  }

  private static String textFromVertexParts(JsonNode parts) {
    if (!parts.isArray()) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    for (JsonNode part : parts) {
      if (part.has("text") && !part.path("thought").asBoolean(false)) {
        text.append(part.path("text").asText(""));
      }
    }
    return text.toString();
  }

  private static int firstInt(JsonNode first, JsonNode second) {
    return first.isMissingNode() || first.isNull() ? second.asInt(0) : first.asInt(0);
  }

  private static long epochMillis(String timestamp) {
    if (timestamp == null || timestamp.isBlank()) {
      return 0;
    }
    try {
      return Instant.parse(timestamp).toEpochMilli();
    } catch (Exception exception) {
      return 0;
    }
  }

  private static String lastPathSegment(String path) {
    if (path == null || path.isBlank()) {
      return "";
    }
    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  private static String finishReason(String providerReason) {
    String normalized = providerReason == null ? "" : providerReason.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "complete", "stop", "end_turn", "eos" -> "stop";
      case "max_tokens", "length" -> "length";
      case "tool_use" -> "tool_calls";
      case "safety", "prohibited_content", "blocked" -> "content_filter";
      default -> normalized;
    };
  }

  private static String firstText(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }

  private static ProviderResponse jsonResponse(ProviderResponse response, String body) {
    Map<String, String> headers = new LinkedHashMap<>(response.headers());
    headers.put("content-type", "application/json");
    return new ProviderResponse(response.status(), body, headers, response.attempts());
  }

  private static String workersAiChatStreamLine(String line) {
    String trimmed = line == null ? "" : line.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    if ("data: [DONE]".equals(trimmed)) {
      return "data: [DONE]\n\n";
    }
    if (!trimmed.startsWith("data:")) {
      return "";
    }
    JsonNode root = parse(trimmed.substring("data:".length()).trim());
    if (root == null) {
      return line + "\n\n";
    }
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", "modelgate-" + System.currentTimeMillis());
    out.put("object", "chat.completion.chunk");
    out.put("created", System.currentTimeMillis() / 1000);
    out.put("model", "");
    out.put("provider", "workers-ai");
    ObjectNode choice = out.putArray("choices").addObject();
    ObjectNode delta = choice.putObject("delta");
    delta.put("content", root.path("response").asText(""));
    choice.put("index", 0);
    choice.set("logprobs", OBJECT_MAPPER.nullNode());
    choice.set("finish_reason", OBJECT_MAPPER.nullNode());
    return "data: " + write(out, "{}") + "\n\n";
  }

  private static String oracleChatStreamLine(String line) {
    String trimmed = line == null ? "" : line.trim();
    if (trimmed.isEmpty() || trimmed.startsWith("event: ping")) {
      return "";
    }
    String payload = dataPayload(trimmed);
    if (payload.isEmpty()) {
      return "";
    }
    if ("[DONE]".equals(payload)) {
      return "data: [DONE]\n\n";
    }
    JsonNode root = parse(payload);
    if (root == null) {
      return line + "\n\n";
    }
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", "modelgate-" + System.currentTimeMillis());
    out.put("object", "chat.completion.chunk");
    out.put("created", System.currentTimeMillis() / 1000);
    out.put("model", "");
    out.put("provider", "oracle");
    ObjectNode choice = out.putArray("choices").addObject();
    choice.put("index", root.path("index").asInt(0));
    JsonNode finishReason = root.path("finishReason");
    if (!finishReason.isMissingNode() && !finishReason.isNull() && !finishReason.asText("").isBlank()) {
      choice.set("delta", OBJECT_MAPPER.createObjectNode());
      choice.put("finish_reason", finishReason(finishReason.asText("")));
      return "data: " + write(out, "{}") + "\n\n" + "data: [DONE]\n\n";
    }
    ObjectNode delta = choice.putObject("delta");
    delta.put("role", openAiRole(root.at("/message/role").asText("ASSISTANT")));
    delta.put("content", textFromOracleContent(root.at("/message/content")));
    choice.set("finish_reason", OBJECT_MAPPER.nullNode());
    return "data: " + write(out, "{}") + "\n\n";
  }

  private static boolean isOpenAiCompatibleStreamProvider(String provider) {
    return switch (provider) {
      case "302ai",
          "azure-ai",
          "cerebras",
          "deepseek",
          "fireworks-ai",
          "groq",
          "huggingface",
          "inception",
          "lambda",
          "ollama",
          "dashscope",
          "mistral-ai",
          "modal",
          "nvidia",
          "nvidia-nim",
          "openrouter",
          "perplexity-ai",
          "predibase",
          "together-ai",
          "lepton",
          "upstage",
          "sambanova" -> true;
      default -> false;
    };
  }

  private static String openAiCompatibleChatStreamLine(String provider, String line) {
    String trimmed = line == null ? "" : line.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    if ("huggingface".equals(provider) && trimmed.startsWith("event: ping")) {
      return "";
    }
    if ("data: [DONE]".equals(trimmed)) {
      return "data: [DONE]\n\n";
    }
    if (!trimmed.startsWith("data:")) {
      return "";
    }
    String data = trimmed.substring("data:".length()).trim();
    if ("[DONE]".equals(data)) {
      return "data: [DONE]\n\n";
    }
    if ("openrouter".equals(provider) && data.contains("OPENROUTER PROCESSING")) {
      data = """
          {"id":"modelgate-processing","object":"chat.completion.chunk","created":0,"model":"","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}
          """;
    }
    JsonNode root = parse(data);
    if (!(root instanceof ObjectNode objectNode)) {
      return line + "\n\n";
    }
    if ("huggingface".equals(provider)) {
      objectNode.put("id", "modelgate-" + System.currentTimeMillis());
    }
    objectNode.put("provider", provider);
    if ("openrouter".equals(provider)) {
      enrichOpenRouterStreamReasoning(objectNode);
    }
    String out = "data: " + write(objectNode, data) + "\n\n";
    if ("perplexity-ai".equals(provider) && hasFinishReason(objectNode)) {
      out += "data: [DONE]\n\n";
    }
    return out;
  }

  private static void enrichOpenRouterStreamReasoning(ObjectNode response) {
    JsonNode choices = response.path("choices");
    if (!choices.isArray() || choices.isEmpty()) {
      return;
    }
    JsonNode choice = choices.get(0);
    JsonNode delta = choice.path("delta");
    if (!(delta instanceof ObjectNode deltaObject)) {
      return;
    }
    ArrayNode contentBlocks = OBJECT_MAPPER.createArrayNode();
    if (delta.path("reasoning").isTextual()) {
      ObjectNode thinking = contentBlocks.addObject();
      thinking.put("index", choice.path("index").asInt(0));
      thinking.putObject("delta").put("thinking", delta.path("reasoning").asText());
    }
    if (delta.path("content").isTextual()) {
      ObjectNode text = contentBlocks.addObject();
      text.put("index", choice.path("index").asInt(0));
      text.putObject("delta").put("text", delta.path("content").asText());
    }
    if (!contentBlocks.isEmpty()) {
      deltaObject.set("content_blocks", contentBlocks);
    }
  }

  private static boolean hasFinishReason(JsonNode response) {
    JsonNode choices = response.path("choices");
    if (!choices.isArray() || choices.isEmpty()) {
      return false;
    }
    JsonNode finishReason = choices.get(0).path("finish_reason");
    return !finishReason.isMissingNode() && !finishReason.isNull() && !finishReason.asText("").isBlank();
  }

  private static String dataPayload(String line) {
    String trimmed = line == null ? "" : line.trim();
    if (trimmed.startsWith("data:")) {
      return trimmed.substring("data:".length()).trim();
    }
    return trimmed;
  }

  private static String cohereUsageChunk(String generationId, int lastIndex, JsonNode root) {
    int promptTokens = firstInt(root.at("/delta/usage/tokens/input_tokens"),
        root.at("/delta/usage/billed_units/input_tokens"));
    int completionTokens = firstInt(root.at("/delta/usage/tokens/output_tokens"),
        root.at("/delta/usage/billed_units/output_tokens"));
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", generationId);
    out.put("object", "chat.completion.chunk");
    out.put("created", System.currentTimeMillis() / 1000);
    out.put("model", "");
    out.put("provider", "cohere");
    ObjectNode choice = out.putArray("choices").addObject();
    choice.put("index", lastIndex);
    choice.set("delta", OBJECT_MAPPER.createObjectNode());
    choice.set("logprobs", OBJECT_MAPPER.nullNode());
    choice.put("finish_reason", finishReason(root.at("/delta/finish_reason").asText("")));
    out.set("usage", usage(promptTokens, completionTokens, promptTokens + completionTokens));
    return "data: " + write(out, "{}") + "\n\n" + "data: [DONE]\n\n";
  }

  private static String cohereContentChunk(String generationId, int lastIndex, JsonNode root) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", generationId);
    out.put("object", "chat.completion.chunk");
    out.put("created", System.currentTimeMillis() / 1000);
    out.put("model", "");
    out.put("provider", "cohere");
    out.set("system_fingerprint", OBJECT_MAPPER.nullNode());
    ObjectNode choice = out.putArray("choices").addObject();
    choice.put("index", lastIndex);
    ObjectNode delta = choice.putObject("delta");
    delta.put("role", "assistant");
    delta.put("content", root.at("/delta/message/content/text").asText(""));
    if (!root.at("/delta/message/tool_calls").isMissingNode()) {
      delta.set("tool_calls", root.at("/delta/message/tool_calls").deepCopy());
    }
    choice.set("logprobs", OBJECT_MAPPER.nullNode());
    choice.set("finish_reason", OBJECT_MAPPER.nullNode());
    return "data: " + write(out, "{}") + "\n\n";
  }

  private static String geminiChatStreamLine(String provider, boolean containsThinking, String line) {
    String payload = dataPayload(line);
    if (payload.isEmpty()) {
      return "";
    }
    if ("[DONE]".equals(payload)) {
      return "data: [DONE]\n\n";
    }
    payload = trimJsonArrayEnvelope(payload);
    JsonNode root = parse(payload);
    if (root == null) {
      return line + "\n\n";
    }
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", "modelgate-" + System.currentTimeMillis());
    out.put("object", "chat.completion.chunk");
    out.put("created", System.currentTimeMillis() / 1000);
    out.put("model", root.path("modelVersion").asText(""));
    out.put("provider", provider);
    ArrayNode choices = out.putArray("choices");
    JsonNode candidates = root.path("candidates");
    if (candidates.isArray()) {
      for (int i = 0; i < candidates.size(); i++) {
        JsonNode candidate = candidates.get(i);
        ObjectNode choice = choices.addObject();
        ObjectNode delta = choice.putObject("delta");
        delta.put("role", "assistant");
        addGeminiDelta(delta, candidate.at("/content/parts"), containsThinking);
        choice.put("index", candidate.path("index").isNumber() ? candidate.path("index").asInt() : i);
        JsonNode finishReason = candidate.path("finishReason");
        if (finishReason.isMissingNode() || finishReason.isNull() || finishReason.asText("").isBlank()) {
          choice.set("finish_reason", OBJECT_MAPPER.nullNode());
        } else {
          choice.put("finish_reason", finishReason(finishReason.asText("")));
        }
        if (candidate.has("groundingMetadata")) {
          choice.set("groundingMetadata", candidate.path("groundingMetadata").deepCopy());
        }
        if (candidate.has("safetyRatings")) {
          choice.set("safetyRatings", candidate.path("safetyRatings").deepCopy());
        }
      }
    }
    if (root.at("/usageMetadata/candidatesTokenCount").isNumber()) {
      out.set("usage", googleUsage(root.path("usageMetadata")));
    }
    return "data: " + write(out, "{}") + "\n\n";
  }

  private static void addGeminiDelta(ObjectNode delta, JsonNode parts, boolean containsThinking) {
    if (!parts.isArray() || parts.isEmpty()) {
      delta.put("content", "");
      return;
    }
    ArrayNode contentBlocks = OBJECT_MAPPER.createArrayNode();
    ArrayNode toolCalls = OBJECT_MAPPER.createArrayNode();
    StringBuilder content = new StringBuilder();
    boolean sawThinking = containsThinking;
    for (int i = 0; i < parts.size(); i++) {
      JsonNode part = parts.get(i);
      if (part.path("functionCall").isObject()) {
        ObjectNode toolCall = toolCalls.addObject();
        toolCall.put("index", i);
        toolCall.put("id", "modelgate-" + System.currentTimeMillis() + "-" + i);
        toolCall.put("type", "function");
        ObjectNode function = toolCall.putObject("function");
        function.put("name", part.at("/functionCall/name").asText(""));
        function.put("arguments", jsonString(part.at("/functionCall/args")));
      } else if (part.path("inlineData").isObject()) {
        ObjectNode block = contentBlocks.addObject();
        block.put("index", sawThinking ? 1 : 0);
        ObjectNode blockDelta = block.putObject("delta");
        blockDelta.put("type", "image_url");
        ObjectNode imageUrl = blockDelta.putObject("image_url");
        imageUrl.put("url", "data:" + part.at("/inlineData/mimeType").asText("")
            + ";base64," + part.at("/inlineData/data").asText(""));
      } else if (part.path("text").isTextual()) {
        ObjectNode block = contentBlocks.addObject();
        if (part.path("thought").asBoolean(false)) {
          block.put("index", 0);
          block.putObject("delta").put("thinking", part.path("text").asText(""));
          sawThinking = true;
        } else {
          content.append(part.path("text").asText(""));
          block.put("index", sawThinking ? 1 : 0);
          block.putObject("delta").put("text", part.path("text").asText(""));
        }
      }
    }
    if (!toolCalls.isEmpty()) {
      delta.set("tool_calls", toolCalls);
      return;
    }
    delta.put("content", content.toString());
    if (!contentBlocks.isEmpty()) {
      delta.set("content_blocks", contentBlocks);
    }
  }

  private static String trimJsonArrayEnvelope(String payload) {
    String out = payload.trim();
    if (out.startsWith("[")) {
      out = out.substring(1).trim();
    }
    if (out.endsWith(",")) {
      out = out.substring(0, out.length() - 1).trim();
    }
    if (out.endsWith("]")) {
      out = out.substring(0, out.length() - 1).trim();
    }
    return out;
  }

  private static String bedrockChatStreamLine(String line, String stopReason, int currentToolCallIndex) {
    String payload = dataPayload(normalizeBedrockStreamPayload(line));
    if (payload.isEmpty()) {
      return "";
    }
    JsonNode root = parse(payload);
    if (root == null) {
      return line + "\n";
    }
    if (root.has("stopReason")) {
      return "";
    }
    if (root.path("usage").isObject()) {
      int cacheReadInputTokens = root.at("/usage/cacheReadInputTokens").asInt(0);
      int cacheWriteInputTokens = root.at("/usage/cacheWriteInputTokens").asInt(0);
      ObjectNode out = OBJECT_MAPPER.createObjectNode();
      out.put("id", "modelgate-" + System.currentTimeMillis());
      out.put("object", "chat.completion.chunk");
      out.put("created", System.currentTimeMillis() / 1000);
      out.put("model", "");
      out.put("provider", "bedrock");
      ObjectNode choice = out.putArray("choices").addObject();
      choice.put("index", 0);
      choice.set("delta", OBJECT_MAPPER.createObjectNode());
      choice.put("finish_reason", finishReason(stopReason));
      ObjectNode usage = usage(
          root.at("/usage/inputTokens").asInt(0) + cacheReadInputTokens + cacheWriteInputTokens,
          root.at("/usage/outputTokens").asInt(0),
          root.at("/usage/totalTokens").asInt(0));
      ObjectNode promptDetails = OBJECT_MAPPER.createObjectNode();
      promptDetails.put("cached_tokens", cacheReadInputTokens);
      usage.set("prompt_tokens_details", promptDetails);
      if (cacheReadInputTokens > 0 || cacheWriteInputTokens > 0) {
        usage.put("cache_read_input_tokens", cacheReadInputTokens);
        usage.put("cache_creation_input_tokens", cacheWriteInputTokens);
      }
      out.set("usage", usage);
      return "data: " + write(out, "{}") + "\n\n" + "data: [DONE]\n\n";
    }
    ArrayNode toolCalls = OBJECT_MAPPER.createArrayNode();
    JsonNode toolUse = root.at("/start/toolUse").isObject() ? root.at("/start/toolUse") : root.at("/delta/toolUse");
    if (toolUse.isObject()) {
      ObjectNode toolCall = toolCalls.addObject();
      toolCall.put("index", Math.max(0, currentToolCallIndex));
      toolCall.put("id", toolUse.path("toolUseId").asText(""));
      toolCall.put("type", "function");
      ObjectNode function = toolCall.putObject("function");
      function.put("name", toolUse.path("name").asText(""));
      function.put("arguments", toolUse.path("input").isTextual()
          ? toolUse.path("input").asText("")
          : jsonString(toolUse.path("input")));
    }
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("id", "modelgate-" + System.currentTimeMillis());
    out.put("object", "chat.completion.chunk");
    out.put("created", System.currentTimeMillis() / 1000);
    out.put("model", "");
    out.put("provider", "bedrock");
    ObjectNode choice = out.putArray("choices").addObject();
    choice.put("index", 0);
    ObjectNode delta = choice.putObject("delta");
    delta.put("role", "assistant");
    String content = root.at("/delta/text").asText("");
    delta.put("content", content);
    if (!toolCalls.isEmpty()) {
      delta.set("tool_calls", toolCalls);
    } else {
      ArrayNode contentBlocks = bedrockContentBlocks(root);
      if (!contentBlocks.isEmpty()) {
        delta.set("content_blocks", contentBlocks);
      }
    }
    choice.set("finish_reason", OBJECT_MAPPER.nullNode());
    return "data: " + write(out, "{}") + "\n\n";
  }

  private static String normalizeBedrockStreamPayload(String line) {
    String payload = dataPayload(line);
    JsonNode root = parse(payload);
    if (root == null) {
      return line;
    }
    if (root.path("contentBlockDelta").isObject()) {
      JsonNode event = root.path("contentBlockDelta");
      ObjectNode normalized = OBJECT_MAPPER.createObjectNode();
      normalized.put("contentBlockIndex", event.path("contentBlockIndex").asInt(0));
      normalized.set("delta", event.path("delta"));
      return write(normalized, line);
    }
    if (root.path("contentBlockStart").isObject()) {
      JsonNode event = root.path("contentBlockStart");
      ObjectNode normalized = OBJECT_MAPPER.createObjectNode();
      normalized.put("contentBlockIndex", event.path("contentBlockIndex").asInt(0));
      normalized.set("start", event.path("start"));
      return write(normalized, line);
    }
    if (root.path("messageStop").isObject()) {
      ObjectNode normalized = OBJECT_MAPPER.createObjectNode();
      normalized.put("stopReason", root.at("/messageStop/stopReason").asText(""));
      return write(normalized, line);
    }
    if (root.path("metadata").isObject()) {
      ObjectNode normalized = OBJECT_MAPPER.createObjectNode();
      normalized.set("usage", root.at("/metadata/usage"));
      return write(normalized, line);
    }
    return line;
  }

  private static ArrayNode bedrockContentBlocks(JsonNode root) {
    ArrayNode blocks = OBJECT_MAPPER.createArrayNode();
    ObjectNode block = OBJECT_MAPPER.createObjectNode();
    block.put("index", root.path("contentBlockIndex").asInt(0));
    ObjectNode delta = block.putObject("delta");
    if (root.at("/delta/reasoningContent/text").isTextual()) {
      delta.put("thinking", root.at("/delta/reasoningContent/text").asText());
    }
    if (root.at("/delta/reasoningContent/signature").isTextual()) {
      delta.put("signature", root.at("/delta/reasoningContent/signature").asText());
    }
    if (root.at("/delta/text").isTextual()) {
      delta.put("text", root.at("/delta/text").asText());
    }
    if (root.at("/delta/reasoningContent/redactedContent").isTextual()) {
      delta.put("data", root.at("/delta/reasoningContent/redactedContent").asText());
    }
    if (!delta.isEmpty()) {
      blocks.add(block);
    }
    return blocks;
  }

  private static JsonNode parse(String body) {
    try {
      return OBJECT_MAPPER.readTree(body);
    } catch (Exception exception) {
      return null;
    }
  }

  private static String write(JsonNode node, String fallback) {
    try {
      return OBJECT_MAPPER.writeValueAsString(node);
    } catch (Exception exception) {
      return fallback;
    }
  }

  private static String pathWithoutQuery(String endpointPath) {
    if (endpointPath == null) {
      return "";
    }
    int queryStart = endpointPath.indexOf('?');
    return queryStart < 0 ? endpointPath : endpointPath.substring(0, queryStart);
  }

  private static final class WorkersAiChatStream extends InputStream {
    private final BufferedReader reader;
    private byte[] current = new byte[0];
    private int index;

    private WorkersAiChatStream(InputStream upstream) {
      this.reader = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8));
    }

    @Override
    public int read() throws IOException {
      if (index >= current.length && !fill()) {
        return -1;
      }
      return current[index++] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (buffer == null) {
        throw new NullPointerException("buffer");
      }
      if (offset < 0 || length < 0 || length > buffer.length - offset) {
        throw new IndexOutOfBoundsException();
      }
      if (length == 0) {
        return 0;
      }
      int first = read();
      if (first == -1) {
        return -1;
      }
      buffer[offset] = (byte) first;
      int count = 1;
      while (count < length && index < current.length) {
        buffer[offset + count++] = current[index++];
      }
      return count;
    }

    @Override
    public void close() throws IOException {
      reader.close();
    }

    private boolean fill() throws IOException {
      String line;
      while ((line = reader.readLine()) != null) {
        String transformed = workersAiChatStreamLine(line);
        if (!transformed.isEmpty()) {
          current = transformed.getBytes(StandardCharsets.UTF_8);
          index = 0;
          return true;
        }
      }
      return false;
    }
  }

  private static final class CohereChatStream extends InputStream {
    private final BufferedReader reader;
    private byte[] current = new byte[0];
    private int index;
    private String generationId = "";
    private int lastIndex;

    private CohereChatStream(InputStream upstream) {
      this.reader = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8));
    }

    @Override
    public int read() throws IOException {
      if (index >= current.length && !fill()) {
        return -1;
      }
      return current[index++] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (buffer == null) {
        throw new NullPointerException("buffer");
      }
      if (offset < 0 || length < 0 || length > buffer.length - offset) {
        throw new IndexOutOfBoundsException();
      }
      if (length == 0) {
        return 0;
      }
      int first = read();
      if (first == -1) {
        return -1;
      }
      buffer[offset] = (byte) first;
      int count = 1;
      while (count < length && index < current.length) {
        buffer[offset + count++] = current[index++];
      }
      return count;
    }

    @Override
    public void close() throws IOException {
      reader.close();
    }

    private boolean fill() throws IOException {
      String line;
      while ((line = reader.readLine()) != null) {
        String transformed = transform(line);
        if (!transformed.isEmpty()) {
          current = transformed.getBytes(StandardCharsets.UTF_8);
          index = 0;
          return true;
        }
      }
      return false;
    }

    private String transform(String line) {
      String payload = dataPayload(line);
      if (payload.isEmpty() || line.trim().startsWith("event:")) {
        return "";
      }
      JsonNode root = parse(payload);
      if (root == null) {
        return line + "\n\n";
      }
      if ("message-start".equals(root.path("type").asText())) {
        generationId = root.path("id").asText(generationId);
      }
      if (root.path("index").isNumber()) {
        lastIndex = root.path("index").asInt(0);
      }
      if ("message-end".equals(root.path("type").asText())) {
        return cohereUsageChunk(generationId, lastIndex, root);
      }
      return cohereContentChunk(generationId, lastIndex, root);
    }
  }

  private static final class GeminiChatStream extends InputStream {
    private final BufferedReader reader;
    private final String provider;
    private byte[] current = new byte[0];
    private int index;
    private boolean containsThinking;

    private GeminiChatStream(InputStream upstream, String provider) {
      this.reader = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8));
      this.provider = provider;
    }

    @Override
    public int read() throws IOException {
      if (index >= current.length && !fill()) {
        return -1;
      }
      return current[index++] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (buffer == null) {
        throw new NullPointerException("buffer");
      }
      if (offset < 0 || length < 0 || length > buffer.length - offset) {
        throw new IndexOutOfBoundsException();
      }
      if (length == 0) {
        return 0;
      }
      int first = read();
      if (first == -1) {
        return -1;
      }
      buffer[offset] = (byte) first;
      int count = 1;
      while (count < length && index < current.length) {
        buffer[offset + count++] = current[index++];
      }
      return count;
    }

    @Override
    public void close() throws IOException {
      reader.close();
    }

    private boolean fill() throws IOException {
      String line;
      while ((line = reader.readLine()) != null) {
        String transformed = geminiChatStreamLine(provider, containsThinking, line);
        if (dataPayload(line).contains("\"thought\":true")) {
          containsThinking = true;
        }
        if (!transformed.isEmpty()) {
          current = transformed.getBytes(StandardCharsets.UTF_8);
          index = 0;
          return true;
        }
      }
      return false;
    }
  }

  private static final class BedrockChatStream extends InputStream {
    private static final int EVENT_STREAM_PRELUDE_LENGTH = 12;
    private static final int EVENT_STREAM_MESSAGE_CRC_LENGTH = 4;

    private final PushbackInputStream upstream;
    private final BufferedReader reader;
    private final boolean awsEventStream;
    private byte[] current = new byte[0];
    private int index;
    private String stopReason = "";
    private int currentToolCallIndex = -1;

    private BedrockChatStream(InputStream input) {
      this.upstream = new PushbackInputStream(input, 1);
      this.awsEventStream = isLikelyAwsEventStream(this.upstream);
      this.reader = awsEventStream
          ? null
          : new BufferedReader(new InputStreamReader(this.upstream, StandardCharsets.UTF_8));
    }

    @Override
    public int read() throws IOException {
      if (index >= current.length && !fill()) {
        return -1;
      }
      return current[index++] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (buffer == null) {
        throw new NullPointerException("buffer");
      }
      if (offset < 0 || length < 0 || length > buffer.length - offset) {
        throw new IndexOutOfBoundsException();
      }
      if (length == 0) {
        return 0;
      }
      int first = read();
      if (first == -1) {
        return -1;
      }
      buffer[offset] = (byte) first;
      int count = 1;
      while (count < length && index < current.length) {
        buffer[offset + count++] = current[index++];
      }
      return count;
    }

    @Override
    public void close() throws IOException {
      if (reader != null) {
        reader.close();
        return;
      }
      upstream.close();
    }

    private boolean fill() throws IOException {
      return awsEventStream ? fillAwsEventStream() : fillTextStream();
    }

    private boolean fillTextStream() throws IOException {
      String line;
      while ((line = reader.readLine()) != null) {
        if (fillFromBedrockPayload(line)) {
          return true;
        }
      }
      return false;
    }

    private boolean fillAwsEventStream() throws IOException {
      String payload;
      while ((payload = readAwsEventStreamPayload()) != null) {
        if (fillFromBedrockPayload(payload)) {
          return true;
        }
      }
      return false;
    }

    private boolean fillFromBedrockPayload(String line) {
      String normalized = normalizeBedrockStreamPayload(line);
      JsonNode root = parse(dataPayload(normalized));
      if (root != null && root.has("stopReason")) {
        stopReason = root.path("stopReason").asText(stopReason);
      }
      if (root != null && root.at("/start/toolUse").isObject()) {
        currentToolCallIndex++;
      }
      String transformed = bedrockChatStreamLine(normalized, stopReason, currentToolCallIndex);
      if (transformed.isEmpty()) {
        return false;
      }
      current = transformed.getBytes(StandardCharsets.UTF_8);
      index = 0;
      return true;
    }

    private String readAwsEventStreamPayload() throws IOException {
      byte[] prelude = readExactlyOrNull(EVENT_STREAM_PRELUDE_LENGTH);
      if (prelude == null) {
        return null;
      }
      int totalLength = intFromBytes(prelude, 0);
      int headersLength = intFromBytes(prelude, 4);
      int payloadLength = totalLength
          - EVENT_STREAM_PRELUDE_LENGTH
          - headersLength
          - EVENT_STREAM_MESSAGE_CRC_LENGTH;
      if (totalLength < EVENT_STREAM_PRELUDE_LENGTH + EVENT_STREAM_MESSAGE_CRC_LENGTH
          || headersLength < 0
          || payloadLength < 0) {
        throw new IOException("Invalid Bedrock event stream frame length");
      }
      skipExactly(headersLength);
      byte[] payload = readExactly(payloadLength);
      skipExactly(EVENT_STREAM_MESSAGE_CRC_LENGTH);
      return new String(payload, StandardCharsets.UTF_8);
    }

    private byte[] readExactlyOrNull(int length) throws IOException {
      ByteArrayOutputStream out = new ByteArrayOutputStream(length);
      while (out.size() < length) {
        int next = upstream.read();
        if (next == -1) {
          if (out.size() == 0) {
            return null;
          }
          throw new EOFException("Unexpected end of Bedrock event stream frame");
        }
        out.write(next);
      }
      return out.toByteArray();
    }

    private byte[] readExactly(int length) throws IOException {
      byte[] bytes = readExactlyOrNull(length);
      if (bytes == null) {
        throw new EOFException("Unexpected end of Bedrock event stream frame");
      }
      return bytes;
    }

    private void skipExactly(int length) throws IOException {
      int remaining = length;
      while (remaining > 0) {
        long skipped = upstream.skip(remaining);
        if (skipped <= 0) {
          if (upstream.read() == -1) {
            throw new EOFException("Unexpected end of Bedrock event stream frame");
          }
          skipped = 1;
        }
        remaining -= (int) skipped;
      }
    }

    private static boolean isLikelyAwsEventStream(PushbackInputStream stream) {
      try {
        int first = stream.read();
        if (first == -1) {
          return false;
        }
        stream.unread(first);
        return first == 0;
      } catch (IOException exception) {
        return false;
      }
    }

    private static int intFromBytes(byte[] bytes, int offset) {
      return ((bytes[offset] & 0xff) << 24)
          | ((bytes[offset + 1] & 0xff) << 16)
          | ((bytes[offset + 2] & 0xff) << 8)
          | (bytes[offset + 3] & 0xff);
    }
  }

  private static final class OracleChatStream extends InputStream {
    private final BufferedReader reader;
    private byte[] current = new byte[0];
    private int index;

    private OracleChatStream(InputStream upstream) {
      this.reader = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8));
    }

    @Override
    public int read() throws IOException {
      if (index >= current.length && !fill()) {
        return -1;
      }
      return current[index++] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (buffer == null) {
        throw new NullPointerException("buffer");
      }
      if (offset < 0 || length < 0 || length > buffer.length - offset) {
        throw new IndexOutOfBoundsException();
      }
      if (length == 0) {
        return 0;
      }
      int first = read();
      if (first == -1) {
        return -1;
      }
      buffer[offset] = (byte) first;
      int count = 1;
      while (count < length && index < current.length) {
        buffer[offset + count++] = current[index++];
      }
      return count;
    }

    @Override
    public void close() throws IOException {
      reader.close();
    }

    private boolean fill() throws IOException {
      String line;
      while ((line = reader.readLine()) != null) {
        String transformed = oracleChatStreamLine(line);
        if (!transformed.isEmpty()) {
          current = transformed.getBytes(StandardCharsets.UTF_8);
          index = 0;
          return true;
        }
      }
      return false;
    }
  }

  private static final class OpenAiCompatibleChatStream extends InputStream {
    private final BufferedReader reader;
    private final String provider;
    private byte[] current = new byte[0];
    private int index;

    private OpenAiCompatibleChatStream(InputStream upstream, String provider) {
      this.reader = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8));
      this.provider = provider;
    }

    @Override
    public int read() throws IOException {
      if (index >= current.length && !fill()) {
        return -1;
      }
      return current[index++] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (buffer == null) {
        throw new NullPointerException("buffer");
      }
      if (offset < 0 || length < 0 || length > buffer.length - offset) {
        throw new IndexOutOfBoundsException();
      }
      if (length == 0) {
        return 0;
      }
      int first = read();
      if (first == -1) {
        return -1;
      }
      buffer[offset] = (byte) first;
      int count = 1;
      while (count < length && index < current.length) {
        buffer[offset + count++] = current[index++];
      }
      return count;
    }

    @Override
    public void close() throws IOException {
      reader.close();
    }

    private boolean fill() throws IOException {
      String line;
      while ((line = reader.readLine()) != null) {
        String transformed = openAiCompatibleChatStreamLine(provider, line);
        if (!transformed.isEmpty()) {
          current = transformed.getBytes(StandardCharsets.UTF_8);
          index = 0;
          return true;
        }
      }
      return false;
    }
  }
}
