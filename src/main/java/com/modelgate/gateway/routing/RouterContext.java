package com.modelgate.gateway.routing;

import java.util.Map;

public record RouterContext(
    Map<String, Object> metadata,
    Map<String, Object> params,
    String path) {

  public RouterContext {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    params = params == null ? Map.of() : Map.copyOf(params);
  }
}
