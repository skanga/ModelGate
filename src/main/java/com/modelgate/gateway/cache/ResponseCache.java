package com.modelgate.gateway.cache;

import java.time.Duration;

public interface ResponseCache {
  CacheLookup get(CacheRequest request);

  void put(CacheRequest request, String responseBody, Duration ttl);
}
