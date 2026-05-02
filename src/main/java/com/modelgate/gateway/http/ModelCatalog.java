package com.modelgate.gateway.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

final class ModelCatalog {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Map<String, Object> EMPTY_RESPONSE = Map.of(
      "object", "list",
      "data", List.of());
  private static final Map<String, Object> RESPONSE = load();

  private ModelCatalog() {}

  static Map<String, Object> response() {
    return RESPONSE;
  }

  private static Map<String, Object> load() {
    try (InputStream stream = ModelCatalog.class.getResourceAsStream("/modelgate/models.json")) {
      if (stream == null) {
        return EMPTY_RESPONSE;
      }
      return OBJECT_MAPPER.readValue(stream, MAP_TYPE);
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to load bundled model catalog", exception);
    }
  }
}
