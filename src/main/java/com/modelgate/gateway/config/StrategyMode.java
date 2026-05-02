package com.modelgate.gateway.config;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum StrategyMode {
  SINGLE,
  LOADBALANCE,
  FALLBACK,
  CONDITIONAL;

  @JsonCreator
  public static StrategyMode fromString(String value) {
    if (value == null || value.isBlank()) {
      return SINGLE;
    }
    return switch (value.toLowerCase()) {
      case "single" -> SINGLE;
      case "loadbalance" -> LOADBALANCE;
      case "fallback" -> FALLBACK;
      case "conditional" -> CONDITIONAL;
      default -> throw new IllegalArgumentException("Unknown strategy mode: " + value);
    };
  }
}
