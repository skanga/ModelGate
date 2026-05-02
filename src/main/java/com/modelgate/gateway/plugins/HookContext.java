package com.modelgate.gateway.plugins;

import java.util.Map;

public record HookContext(
    String responseText,
    Map<String, Object> requestJson,
    Map<String, Object> responseJson,
    Map<String, Object> metadata,
    Map<String, String> headers,
    String requestType,
    String eventType) {
  public HookContext {
    responseText = responseText == null ? "" : responseText;
    requestJson = JsonCopies.deepCopyMap(requestJson);
    responseJson = JsonCopies.deepCopyMap(responseJson);
    metadata = JsonCopies.deepCopyMap(metadata);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    eventType = eventType == null ? "" : eventType;
  }

  public HookContext(String responseText, Map<String, Object> requestJson) {
    this(responseText, requestJson, Map.of(), Map.of(), Map.of(), null, "");
  }

  public static HookContext forResponseText(String responseText) {
    return new HookContext(responseText, Map.of(), Map.of(), Map.of(), Map.of(), null, "afterRequestHook");
  }

  public static HookContext forResponse(String responseText, Map<String, ?> responseJson) {
    return new HookContext(responseText, Map.of(), JsonCopies.deepCopyMap(responseJson), Map.of(), Map.of(), null, "afterRequestHook");
  }

  public static HookContext forRequestJson(Map<String, ?> requestJson) {
    return new HookContext("", JsonCopies.deepCopyMap(requestJson), Map.of(), Map.of(), Map.of(), null, "beforeRequestHook");
  }

  public static HookContext forRequest(
      String responseText,
      Map<String, ?> requestJson,
      Map<String, ?> metadata,
      Map<String, String> headers,
      String requestType) {
    return forRequest(
        responseText,
        requestJson,
        metadata,
        headers,
        requestType,
        "beforeRequestHook");
  }

  public static HookContext forRequest(
      String responseText,
      Map<String, ?> requestJson,
      Map<String, ?> metadata,
      Map<String, String> headers,
      String requestType,
      String eventType) {
    return new HookContext(
        responseText,
        JsonCopies.deepCopyMap(requestJson),
        Map.of(),
        JsonCopies.deepCopyMap(metadata),
        headers,
        requestType,
        eventType);
  }

  public static HookContext forRequest(
      String responseText,
      Map<String, ?> requestJson,
      Map<String, ?> responseJson,
      Map<String, ?> metadata,
      Map<String, String> headers,
      String requestType) {
    return forRequest(
        responseText,
        requestJson,
        responseJson,
        metadata,
        headers,
        requestType,
        "");
  }

  public static HookContext forRequest(
      String responseText,
      Map<String, ?> requestJson,
      Map<String, ?> responseJson,
      Map<String, ?> metadata,
      Map<String, String> headers,
      String requestType,
      String eventType) {
    return new HookContext(
        responseText,
        JsonCopies.deepCopyMap(requestJson),
        JsonCopies.deepCopyMap(responseJson),
        JsonCopies.deepCopyMap(metadata),
        headers,
        requestType,
        eventType);
  }

  public static HookContext forOutput(
      String responseText,
      Map<String, ?> requestJson,
      Map<String, ?> responseJson,
      Map<String, String> headers,
      String requestType) {
    return forRequest(responseText, requestJson, responseJson, Map.of(), headers, requestType, "afterRequestHook");
  }

  HookContext withRequestJson(Map<String, ?> nextRequestJson) {
    return new HookContext(responseText, JsonCopies.deepCopyMap(nextRequestJson), responseJson, metadata, headers, requestType, eventType);
  }
}
