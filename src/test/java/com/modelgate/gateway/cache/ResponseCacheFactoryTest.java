package com.modelgate.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.modelgate.gateway.config.CacheSettings;
import com.modelgate.gateway.config.GatewayRuntimeConfig;
import java.time.Clock;
import java.time.Duration;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResponseCacheFactoryTest {
  @Test
  void selectsCacheImplementationFromCacheMode() {
    SimpleResponseCache simpleCache = new SimpleResponseCache(Clock.systemUTC());
    RedisResponseCache redisCache = new RedisResponseCache(new NoopRedisCacheStore());
    ResponseCacheFactory factory = new ResponseCacheFactory(simpleCache, redisCache);

    assertThat(factory.forSettings(new CacheSettings("simple", true, 1000)))
        .containsSame(simpleCache);
    assertThat(factory.forSettings(new CacheSettings("redis", true, 1000)))
        .containsSame(redisCache);
    assertThat(factory.forSettings(CacheSettings.defaults())).isEmpty();
  }

  @Test
  void runtimeFactoryIncludesRedisCacheWhenRedisUrlIsConfigured() {
    ResponseCacheFactory factory = ResponseCacheFactory.fromRuntimeConfig(
        Clock.systemUTC(),
        new GatewayRuntimeConfig(
            256,
            Set.of(),
            com.modelgate.gateway.observability.OpenTelemetrySettings.disabled(),
            URI.create("redis://localhost:6379/0")));

    assertThat(factory.forSettings(new CacheSettings("redis", true, 1000)))
        .isPresent()
        .get()
        .isInstanceOf(RedisResponseCache.class);
  }

  private static final class NoopRedisCacheStore implements RedisCacheStore {
    @Override
    public Optional<String> get(String key) {
      return Optional.empty();
    }

    @Override
    public void set(String key, String body, Duration ttl) {
    }
  }
}
