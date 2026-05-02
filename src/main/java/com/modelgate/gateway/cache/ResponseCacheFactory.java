package com.modelgate.gateway.cache;

import com.modelgate.gateway.config.CacheSettings;
import com.modelgate.gateway.config.GatewayRuntimeConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

public final class ResponseCacheFactory {
  private final ResponseCache simpleCache;
  private final ResponseCache redisCache;

  public ResponseCacheFactory(ResponseCache simpleCache, ResponseCache redisCache) {
    this.simpleCache = simpleCache;
    this.redisCache = redisCache;
  }

  public static ResponseCacheFactory inMemoryOnly(Clock clock) {
    return new ResponseCacheFactory(new SimpleResponseCache(clock), null);
  }

  public static ResponseCacheFactory fromRuntimeConfig(Clock clock, GatewayRuntimeConfig runtimeConfig) {
    ResponseCache simpleCache = new SimpleResponseCache(clock);
    if (runtimeConfig == null || runtimeConfig.redisCacheUrl() == null) {
      return new ResponseCacheFactory(simpleCache, null);
    }
    return new ResponseCacheFactory(
        simpleCache,
        new RedisResponseCache(new SocketRedisCacheStore(
            runtimeConfig.redisCacheUrl(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2))));
  }

  public Optional<ResponseCache> forSettings(CacheSettings settings) {
    if (settings == null || !settings.enabled()) {
      return Optional.empty();
    }
    return switch (settings.mode().toLowerCase(Locale.ROOT)) {
      case "simple" -> Optional.ofNullable(simpleCache);
      case "redis" -> Optional.ofNullable(redisCache);
      default -> Optional.empty();
    };
  }
}
