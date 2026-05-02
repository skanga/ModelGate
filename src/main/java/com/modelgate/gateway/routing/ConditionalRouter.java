package com.modelgate.gateway.routing;

import com.modelgate.gateway.config.GatewayConfig;
import com.modelgate.gateway.config.Strategy;
import com.modelgate.gateway.config.Target;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class ConditionalRouter {
  private final GatewayConfig config;
  private final Map<String, Target> targetsByName;

  public ConditionalRouter(GatewayConfig config) {
    this.config = config;
    this.targetsByName = config.targets().stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(Target::name, target -> target));
  }

  public Target resolve(RouterContext context) {
    Strategy strategy = config.strategy();
    if (strategy != null) {
      for (Strategy.Condition condition : strategy.conditions()) {
        if (matches(condition.query(), context)) {
          return targetNamed(condition.target());
        }
      }
      if (strategy.defaultTarget() != null) {
        return targetNamed(strategy.defaultTarget());
      }
    }
    if (config.targets().isEmpty()) {
      throw new IllegalArgumentException("No targets configured");
    }
    return config.targets().getFirst();
  }

  private Target targetNamed(String name) {
    Target target = targetsByName.get(name);
    if (target == null) {
      throw new IllegalArgumentException("Unknown target: " + name);
    }
    return target;
  }

  private boolean matches(Map<String, Object> query, RouterContext context) {
    for (Map.Entry<String, Object> entry : query.entrySet()) {
      if (!matchesEntry(entry.getKey(), entry.getValue(), context)) {
        return false;
      }
    }
    return true;
  }

  private boolean matchesEntry(String key, Object expected, RouterContext context) {
    return switch (key) {
      case "$and" -> asList(expected).stream().allMatch(item -> matches(asMap(item), context));
      case "$or" -> asList(expected).stream().anyMatch(item -> matches(asMap(item), context));
      default -> matchesValue(resolvePath(key, context), expected);
    };
  }

  private boolean matchesValue(Object actual, Object expected) {
    if (expected instanceof Map<?, ?> operators) {
      for (Map.Entry<?, ?> operator : operators.entrySet()) {
        if (!matchesOperator(actual, String.valueOf(operator.getKey()), operator.getValue())) {
          return false;
        }
      }
      return true;
    }
    return valuesEqual(actual, expected);
  }

  private boolean matchesOperator(Object actual, String operator, Object expected) {
    return switch (operator) {
      case "$eq" -> valuesEqual(actual, expected);
      case "$ne" -> !valuesEqual(actual, expected);
      case "$gt" -> compare(actual, expected) > 0;
      case "$gte" -> compare(actual, expected) >= 0;
      case "$lt" -> compare(actual, expected) < 0;
      case "$lte" -> compare(actual, expected) <= 0;
      case "$in" -> asCollection(expected).stream().anyMatch(item -> valuesEqual(actual, item));
      case "$nin" -> asCollection(expected).stream().noneMatch(item -> valuesEqual(actual, item));
      case "$regex" -> actual != null && Pattern.compile(String.valueOf(expected))
          .matcher(String.valueOf(actual))
          .find();
      default -> throw new IllegalArgumentException("Unknown conditional operator: " + operator);
    };
  }

  private Object resolvePath(String path, RouterContext context) {
    if (path == null || path.isBlank()) {
      return null;
    }
    String[] parts = path.split("\\.");
    Object current = switch (parts[0]) {
      case "metadata" -> context.metadata();
      case "params" -> context.params();
      case "path" -> context.path();
      default -> null;
    };
    for (int i = 1; i < parts.length; i++) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = map.get(parts[i]);
    }
    return current;
  }

  private int compare(Object actual, Object expected) {
    Double actualNumber = asNumber(actual);
    Double expectedNumber = asNumber(expected);
    if (actualNumber != null && expectedNumber != null) {
      return Double.compare(actualNumber, expectedNumber);
    }
    return String.valueOf(actual).compareTo(String.valueOf(expected));
  }

  private Double asNumber(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    try {
      return value == null ? null : Double.parseDouble(String.valueOf(value));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private boolean valuesEqual(Object actual, Object expected) {
    Double actualNumber = asNumber(actual);
    Double expectedNumber = asNumber(expected);
    if (actualNumber != null && expectedNumber != null) {
      return Double.compare(actualNumber, expectedNumber) == 0;
    }
    return java.util.Objects.equals(actual, expected);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> asMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    throw new IllegalArgumentException("Expected condition object");
  }

  private Collection<?> asCollection(Object value) {
    if (value instanceof Collection<?> collection) {
      return collection;
    }
    return List.of(value);
  }

  private List<?> asList(Object value) {
    if (value instanceof List<?> list) {
      return list;
    }
    return List.of(value);
  }
}
