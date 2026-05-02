package com.modelgate.gateway.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonCopies {
  private JsonCopies() {}

  static Map<String, Object> deepCopyMap(Map<String, ?> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }

    Map<String, Object> copy = new LinkedHashMap<>();
    source.forEach((key, value) -> copy.put(key, deepCopyValue(value)));
    return Collections.unmodifiableMap(copy);
  }

  static Object deepCopyValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach((key, nestedValue) -> copy.put(String.valueOf(key), deepCopyValue(nestedValue)));
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      for (Object item : list) {
        copy.add(deepCopyValue(item));
      }
      return Collections.unmodifiableList(copy);
    }
    return value;
  }

  static Map<String, Object> mutableDeepCopyMap(Map<String, ?> source) {
    Map<String, Object> copy = new LinkedHashMap<>();
    if (source == null) {
      return copy;
    }
    source.forEach((key, value) -> copy.put(key, mutableDeepCopyValue(value)));
    return copy;
  }

  static Object mutableDeepCopyValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach((key, nestedValue) -> copy.put(String.valueOf(key), mutableDeepCopyValue(nestedValue)));
      return copy;
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      for (Object item : list) {
        copy.add(mutableDeepCopyValue(item));
      }
      return copy;
    }
    return value;
  }
}
