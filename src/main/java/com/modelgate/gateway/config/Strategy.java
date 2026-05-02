package com.modelgate.gateway.config;

import java.util.List;
import java.util.Map;

public record Strategy(
    StrategyMode mode,
    List<Integer> onStatusCodes,
    List<Condition> conditions,
    String defaultTarget) {

  public Strategy {
    mode = mode == null ? StrategyMode.SINGLE : mode;
    onStatusCodes = onStatusCodes == null ? List.of() : List.copyOf(onStatusCodes);
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
  }

  public record Condition(Map<String, Object> query, String target) {
    public Condition {
      query = query == null ? Map.of() : Map.copyOf(query);
    }
  }
}
