package com.modelgate.gateway.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

public final class AnthropicMessageTransformer {
  private final ObjectMapper objectMapper;

  public AnthropicMessageTransformer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String transformRequest(String body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      ObjectNode transformed = objectMapper.createObjectNode();
      copyIfPresent(root, transformed, "model", "model");
      copyIfPresent(root, transformed, "max_tokens", "max_tokens");
      copyIfPresent(root, transformed, "max_completion_tokens", "max_tokens");
      copyIfPresent(root, transformed, "temperature", "temperature");
      copyIfPresent(root, transformed, "top_p", "top_p");
      copyIfPresent(root, transformed, "stream", "stream");
      copyIfPresent(root, transformed, "stop", "stop_sequences");
      appendTools(root.path("tools"), transformed);
      appendToolChoice(root.get("tool_choice"), transformed);
      appendMessages(root.path("messages"), transformed);
      return objectMapper.writeValueAsString(transformed);
    } catch (JsonProcessingException exception) {
      return body;
    }
  }

  public String transformResponse(String body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      if (root.has("error")) {
        return transformError(root);
      }
      if (!root.has("content")) {
        return body;
      }

      int inputTokens = root.path("usage").path("input_tokens").asInt(0);
      int outputTokens = root.path("usage").path("output_tokens").asInt(0);

      ObjectNode response = objectMapper.createObjectNode();
      response.put("id", root.path("id").asText(""));
      response.put("object", "chat.completion");
      response.put("created", Instant.now().getEpochSecond());
      response.put("model", root.path("model").asText(""));
      response.put("provider", "anthropic");

      ArrayNode choices = response.putArray("choices");
      ObjectNode choice = choices.addObject();
      ObjectNode message = choice.putObject("message");
      message.put("role", "assistant");
      message.put("content", textContent(root.path("content")));
      ArrayNode toolCalls = toolCalls(root.path("content"));
      if (!toolCalls.isEmpty()) {
        message.set("tool_calls", toolCalls);
      }
      choice.put("index", 0);
      choice.set("logprobs", objectMapper.nullNode());
      choice.put("finish_reason", finishReason(root.path("stop_reason").asText(null)));

      ObjectNode usage = response.putObject("usage");
      usage.put("prompt_tokens", inputTokens);
      usage.put("completion_tokens", outputTokens);
      usage.put("total_tokens", inputTokens + outputTokens);
      ObjectNode promptDetails = usage.putObject("prompt_tokens_details");
      promptDetails.put("cached_tokens", root.path("usage").path("cache_read_input_tokens").asInt(0));

      return objectMapper.writeValueAsString(response);
    } catch (JsonProcessingException exception) {
      return body;
    }
  }

  private void appendMessages(JsonNode messagesNode, ObjectNode transformed) {
    if (!messagesNode.isArray()) {
      return;
    }
    ArrayNode messages = transformed.putArray("messages");
    ArrayNode system = transformed.putArray("system");
    for (JsonNode message : messagesNode) {
      String role = message.path("role").asText();
      if (role.equals("system") || role.equals("developer")) {
        appendSystem(system, message);
        continue;
      }
      ObjectNode transformedMessage = messages.addObject();
      if ("tool".equals(role)) {
        transformedMessage.put("role", "user");
        transformedMessage.set("content", toolResultContent(message));
        continue;
      }
      transformedMessage.put("role", role);
      transformedMessage.set("content", copyMessageContent(message));
    }
    if (system.isEmpty()) {
      transformed.remove("system");
    }
  }

  private void appendSystem(ArrayNode system, JsonNode message) {
    JsonNode content = message.path("content");
    if (content.isTextual()) {
      ObjectNode systemMessage = system.addObject();
      systemMessage.put("type", "text");
      systemMessage.put("text", content.asText());
      copyCacheControl(message, systemMessage);
      return;
    }
    if (!content.isArray()) {
      return;
    }
    for (JsonNode item : content) {
      if ("text".equals(item.path("type").asText())) {
        ObjectNode systemMessage = system.addObject();
        systemMessage.put("type", "text");
        systemMessage.put("text", item.path("text").asText(""));
        copyCacheControl(item, systemMessage);
        if (!systemMessage.has("cache_control")) {
          copyCacheControl(message, systemMessage);
        }
      }
    }
  }

  private void appendTools(JsonNode toolsNode, ObjectNode transformed) {
    if (!toolsNode.isArray()) {
      return;
    }
    ArrayNode tools = transformed.putArray("tools");
    for (JsonNode tool : toolsNode) {
      if ("function".equals(tool.path("type").asText())) {
        JsonNode function = tool.path("function");
        ObjectNode transformedTool = tools.addObject();
        transformedTool.put("name", function.path("name").asText(""));
        if (function.has("description") && !function.path("description").isNull()) {
          transformedTool.put("description", function.path("description").asText(""));
        }
        JsonNode parameters = function.path("parameters");
        transformedTool.set("input_schema", parameters.isMissingNode()
            ? objectMapper.createObjectNode()
            : parameters.deepCopy());
        copyCacheControl(tool, transformedTool);
        copyIfPresent(function, transformedTool, "defer_loading", "defer_loading");
        copyIfPresent(function, transformedTool, "allowed_callers", "allowed_callers");
        copyIfPresent(function, transformedTool, "input_examples", "input_examples");
        continue;
      }

      String type = tool.path("type").asText("");
      JsonNode toolOptions = tool.path(type);
      if (!type.isBlank() && !toolOptions.isMissingNode()) {
        ObjectNode transformedTool = tools.addObject();
        if (toolOptions.isObject()) {
          toolOptions.fields().forEachRemaining(entry -> transformedTool.set(entry.getKey(), entry.getValue().deepCopy()));
        }
        transformedTool.put("name", type);
        if (toolOptions.has("name") && !toolOptions.path("name").isNull()) {
          transformedTool.put("type", toolOptions.path("name").asText());
        }
        copyCacheControl(tool, transformedTool);
      }
    }
    if (tools.isEmpty()) {
      transformed.remove("tools");
    }
  }

  private void appendToolChoice(JsonNode toolChoice, ObjectNode transformed) {
    if (toolChoice == null || toolChoice.isNull() || toolChoice.isMissingNode()) {
      return;
    }
    ObjectNode choice = objectMapper.createObjectNode();
    if (toolChoice.isTextual()) {
      switch (toolChoice.asText()) {
        case "required" -> choice.put("type", "any");
        case "auto" -> choice.put("type", "auto");
        case "none" -> choice.put("type", "none");
        default -> {
          return;
        }
      }
      transformed.set("tool_choice", choice);
      return;
    }
    String name = toolChoice.path("function").path("name").asText("");
    if (!name.isBlank()) {
      choice.put("type", "tool");
      choice.put("name", name);
      transformed.set("tool_choice", choice);
    }
  }

  private JsonNode copyMessageContent(JsonNode message) {
    String role = message.path("role").asText();
    if ("assistant".equals(role)) {
      return assistantContent(message);
    }
    JsonNode content = message.path("content");
    if (content.isMissingNode() || content.isNull()) {
      return objectMapper.getNodeFactory().textNode("");
    }
    if (content.isArray()) {
      return anthropicContentParts(content);
    }
    return content.deepCopy();
  }

  private ArrayNode anthropicContentParts(JsonNode content) {
    ArrayNode blocks = objectMapper.createArrayNode();
    for (JsonNode item : content) {
      switch (item.path("type").asText("")) {
        case "text" -> appendTextBlock(blocks, item);
        case "image_url" -> appendImageUrlBlock(blocks, item);
        case "file" -> appendFileBlock(blocks, item);
        default -> blocks.add(item.deepCopy());
      }
    }
    return blocks;
  }

  private void appendTextBlock(ArrayNode blocks, JsonNode item) {
    ObjectNode block = blocks.addObject();
    block.put("type", "text");
    block.put("text", item.path("text").asText(""));
    copyCacheControl(item, block);
  }

  private void appendImageUrlBlock(ArrayNode blocks, JsonNode item) {
    String url = item.path("image_url").path("url").asText("");
    if (url.isBlank()) {
      return;
    }
    if (!url.startsWith("data:")) {
      ObjectNode block = blocks.addObject();
      block.put("type", "image");
      ObjectNode source = block.putObject("source");
      source.put("type", "url");
      source.put("url", url);
      return;
    }
    DataUrl dataUrl = parseDataUrl(url);
    if (dataUrl == null) {
      return;
    }
    ObjectNode block = blocks.addObject();
    block.put("type", "application/pdf".equals(dataUrl.mediaType()) ? "document" : "image");
    ObjectNode source = block.putObject("source");
    source.put("type", "base64");
    source.put("media_type", dataUrl.mediaType());
    source.put("data", dataUrl.data());
    copyCacheControl(item, block);
  }

  private void appendFileBlock(ArrayNode blocks, JsonNode item) {
    JsonNode file = item.path("file");
    String fileUrl = file.path("file_url").asText("");
    if (!fileUrl.isBlank()) {
      ObjectNode block = blocks.addObject();
      block.put("type", "document");
      ObjectNode source = block.putObject("source");
      source.put("type", "url");
      source.put("url", fileUrl);
      return;
    }

    String fileData = file.path("file_data").asText("");
    if (fileData.isBlank()) {
      return;
    }
    String mimeType = file.path("mime_type").asText("application/pdf");
    ObjectNode block = blocks.addObject();
    block.put("type", "document");
    ObjectNode source = block.putObject("source");
    source.put("type", "text/plain".equals(mimeType) ? "text" : "base64");
    source.put("data", fileData);
    source.put("media_type", mimeType);
    copyCacheControl(item, block);
  }

  private ArrayNode assistantContent(JsonNode message) {
    ArrayNode blocks = objectMapper.createArrayNode();
    JsonNode content = message.has("content_blocks") && message.path("content_blocks").isArray()
        ? message.path("content_blocks")
        : message.path("content");
    if (content.isTextual() && !content.asText().isEmpty()) {
      ObjectNode block = blocks.addObject();
      block.put("type", "text");
      block.put("text", content.asText());
    } else if (content.isArray()) {
      for (JsonNode item : content) {
        if (!"tool_use".equals(item.path("type").asText())) {
          blocks.add(item.deepCopy());
        }
      }
    }

    JsonNode toolCalls = message.path("tool_calls");
    if (toolCalls.isArray()) {
      for (JsonNode toolCall : toolCalls) {
        ObjectNode toolUse = blocks.addObject();
        toolUse.put("type", "tool_use");
        toolUse.put("id", toolCall.path("id").asText(""));
        toolUse.put("name", toolCall.path("function").path("name").asText(""));
        toolUse.set("input", toolInput(toolCall.path("function").path("arguments").asText("")));
        copyCacheControl(toolCall, toolUse);
      }
    }
    return blocks;
  }

  private JsonNode toolInput(String arguments) {
    if (arguments == null || arguments.isBlank()) {
      return objectMapper.createObjectNode();
    }
    try {
      return objectMapper.readTree(arguments);
    } catch (JsonProcessingException exception) {
      return objectMapper.createObjectNode();
    }
  }

  private ArrayNode toolResultContent(JsonNode message) {
    ArrayNode content = objectMapper.createArrayNode();
    ObjectNode block = content.addObject();
    block.put("type", "tool_result");
    block.put("tool_use_id", message.path("tool_call_id").asText(""));
    block.put("content", message.path("content").asText(""));
    return content;
  }

  private String textContent(JsonNode content) {
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

  private ArrayNode toolCalls(JsonNode content) throws JsonProcessingException {
    ArrayNode toolCalls = objectMapper.createArrayNode();
    if (!content.isArray()) {
      return toolCalls;
    }
    for (JsonNode item : content) {
      if (!"tool_use".equals(item.path("type").asText())) {
        continue;
      }
      ObjectNode toolCall = toolCalls.addObject();
      toolCall.put("id", item.path("id").asText(""));
      toolCall.put("type", "function");
      ObjectNode function = toolCall.putObject("function");
      function.put("name", item.path("name").asText(""));
      JsonNode input = item.path("input").isMissingNode() ? objectMapper.createObjectNode() : item.path("input");
      function.put("arguments", objectMapper.writeValueAsString(input));
    }
    return toolCalls;
  }

  private String transformError(JsonNode root) throws JsonProcessingException {
    ObjectNode response = objectMapper.createObjectNode();
    ObjectNode error = response.putObject("error");
    error.put("message", "anthropic error: " + root.path("error").path("message").asText(""));
    JsonNode type = root.path("error").get("type");
    if (type == null || type.isNull()) {
      error.set("type", objectMapper.nullNode());
    } else {
      error.put("type", type.asText());
    }
    error.set("param", objectMapper.nullNode());
    error.set("code", objectMapper.nullNode());
    response.put("provider", "anthropic");
    return objectMapper.writeValueAsString(response);
  }

  private static String finishReason(String stopReason) {
    if (stopReason == null || stopReason.isBlank()) {
      return "stop";
    }
    return switch (stopReason) {
      case "max_tokens" -> "length";
      case "tool_use" -> "tool_calls";
      default -> "stop";
    };
  }

  private void copyIfPresent(JsonNode source, ObjectNode target, String sourceName, String targetName) {
    JsonNode value = source.get(sourceName);
    if (value != null && !value.isNull()) {
      target.set(targetName, value.deepCopy());
    }
  }

  private void copyCacheControl(JsonNode source, ObjectNode target) {
    JsonNode cacheControl = source.get("cache_control");
    if (cacheControl != null && !cacheControl.isNull()) {
      target.set("cache_control", cacheControl.deepCopy());
    }
  }

  private static DataUrl parseDataUrl(String url) {
    int metadataEnd = url.indexOf(';');
    int dataStart = url.indexOf(',');
    if (metadataEnd < 0 || dataStart < metadataEnd || !url.startsWith("data:")) {
      return null;
    }
    String mediaType = url.substring(5, metadataEnd);
    String data = url.substring(dataStart + 1);
    if (mediaType.isBlank() || data.isBlank()) {
      return null;
    }
    return new DataUrl(mediaType, data);
  }

  private record DataUrl(String mediaType, String data) {}
}
