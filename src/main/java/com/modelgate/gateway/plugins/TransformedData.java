package com.modelgate.gateway.plugins;

import java.util.Map;

public record TransformedData(Map<String, Object> requestJson, Map<String, Object> responseJson) {
  public TransformedData(Map<String, Object> requestJson) {
    this(requestJson, Map.of());
  }

  public TransformedData {
    requestJson = JsonCopies.deepCopyMap(requestJson);
    responseJson = JsonCopies.deepCopyMap(responseJson);
  }

  public static TransformedData forResponse(Map<String, ?> responseJson) {
    return new TransformedData(Map.of(), JsonCopies.deepCopyMap(responseJson));
  }
}
