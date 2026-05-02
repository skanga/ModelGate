package com.modelgate.gateway.cache;

import java.time.Duration;
import java.util.Optional;

public interface RedisCacheStore {
  Optional<String> get(String key);

  void set(String key, String body, Duration ttl);
}
