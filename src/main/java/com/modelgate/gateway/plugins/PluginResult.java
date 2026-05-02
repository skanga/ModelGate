package com.modelgate.gateway.plugins;

import java.util.Map;

public record PluginResult(
    boolean verdict,
    String error,
    Map<String, Object> data,
    boolean transformed,
    TransformedData transformedData) {
  public PluginResult {
    data = JsonCopies.deepCopyMap(data);
  }

  public static PluginResult pass() {
    return new PluginResult(true, null, Map.of(), false, null);
  }

  public static PluginResult withVerdict(boolean verdict) {
    return new PluginResult(verdict, null, Map.of(), false, null);
  }

  public static PluginResult withData(boolean verdict, Map<String, ?> data) {
    return new PluginResult(verdict, null, JsonCopies.deepCopyMap(data), false, null);
  }

  public static PluginResult transformed(Map<String, ?> requestJson) {
    return new PluginResult(true, null, Map.of(), true, new TransformedData(JsonCopies.deepCopyMap(requestJson)));
  }
}
