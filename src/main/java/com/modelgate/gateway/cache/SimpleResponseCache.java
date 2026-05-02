package com.modelgate.gateway.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SimpleResponseCache implements ResponseCache {
  private final Clock clock;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  public SimpleResponseCache(Clock clock) {
    this.clock = clock;
  }

  @Override
  public CacheLookup get(CacheRequest request) {
    if (request.forceRefresh()) {
      return CacheLookup.refresh();
    }
    String key = ResponseCacheKeys.keyFor(request);
    Entry entry = entries.get(key);
    if (entry == null) {
      return CacheLookup.miss(key);
    }
    if (entry.expiresAt().isBefore(clock.instant()) || entry.expiresAt().equals(clock.instant())) {
      entries.remove(key);
      return CacheLookup.miss(key);
    }
    return CacheLookup.hit(entry.body(), key);
  }

  @Override
  public void put(CacheRequest request, String responseBody, Duration ttl) {
    if (ttl == null || ttl.isNegative() || ttl.isZero()) {
      return;
    }
    entries.put(ResponseCacheKeys.keyFor(request), new Entry(responseBody, clock.instant().plus(ttl)));
  }

  private record Entry(String body, Instant expiresAt) {}
}
