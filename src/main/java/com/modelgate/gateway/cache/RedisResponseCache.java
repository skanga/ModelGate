package com.modelgate.gateway.cache;

import java.time.Duration;
import java.util.Objects;

public final class RedisResponseCache implements ResponseCache {
  private final RedisCacheStore store;

  public RedisResponseCache(RedisCacheStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public CacheLookup get(CacheRequest request) {
    if (request.forceRefresh()) {
      return CacheLookup.refresh();
    }
    String key = ResponseCacheKeys.keyFor(request);
    return store.get(key)
        .map(body -> CacheLookup.hit(body, key))
        .orElseGet(() -> CacheLookup.miss(key));
  }

  @Override
  public void put(CacheRequest request, String responseBody, Duration ttl) {
    if (ttl == null || ttl.isNegative() || ttl.isZero()) {
      return;
    }
    store.set(ResponseCacheKeys.keyFor(request), responseBody, ttl);
  }
}
