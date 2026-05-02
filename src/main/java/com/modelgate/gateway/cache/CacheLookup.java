package com.modelgate.gateway.cache;

public record CacheLookup(CacheStatus status, String body, String key) {
  public static CacheLookup miss(String key) {
    return new CacheLookup(CacheStatus.MISS, null, key);
  }

  public static CacheLookup hit(String body, String key) {
    return new CacheLookup(CacheStatus.HIT, body, key);
  }

  public static CacheLookup refresh() {
    return new CacheLookup(CacheStatus.REFRESH, null, null);
  }
}
