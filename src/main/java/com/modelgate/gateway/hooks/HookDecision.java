package com.modelgate.gateway.hooks;

import java.util.List;
import java.util.Map;

public record HookDecision(
    boolean allowed,
    int status,
    String body,
    String transformedBody,
    List<Map<String, Object>> beforeRequestHooksResult,
    List<Map<String, Object>> afterRequestHooksResult) {
  public HookDecision {
    beforeRequestHooksResult = beforeRequestHooksResult == null ? List.of() : List.copyOf(beforeRequestHooksResult);
    afterRequestHooksResult = afterRequestHooksResult == null ? List.of() : List.copyOf(afterRequestHooksResult);
  }

  public static HookDecision allowed(String transformedBody) {
    return allowed(transformedBody, List.of(), List.of());
  }

  public static HookDecision allowed(
      String transformedBody,
      List<Map<String, Object>> beforeRequestHooksResult,
      List<Map<String, Object>> afterRequestHooksResult) {
    return new HookDecision(true, 200, null, transformedBody, beforeRequestHooksResult, afterRequestHooksResult);
  }

  public static HookDecision denied(String body) {
    return new HookDecision(false, 446, body, null, List.of(), List.of());
  }
}
