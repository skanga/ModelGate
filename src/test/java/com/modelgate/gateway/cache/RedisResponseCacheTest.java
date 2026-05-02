package com.modelgate.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class RedisResponseCacheTest {
  @Test
  void storesAndReturnsResponseThroughRedisStoreUntilTtlExpires() {
    MutableClock clock = new MutableClock(Instant.parse("2026-04-25T12:00:00Z"));
    ExpiringFakeRedisStore store = new ExpiringFakeRedisStore(clock);
    RedisResponseCache cache = new RedisResponseCache(store);
    CacheRequest request = new CacheRequest(
        "https://api.openai.com/v1/chat/completions",
        "{\"model\":\"gpt-4o-mini\"}",
        Map.of());

    CacheLookup firstLookup = cache.get(request);
    cache.put(request, "{\"choices\":[]}", Duration.ofMinutes(1));
    CacheLookup secondLookup = cache.get(request);
    clock.advance(Duration.ofMinutes(2));
    CacheLookup expiredLookup = cache.get(request);

    assertThat(firstLookup.status()).isEqualTo(CacheStatus.MISS);
    assertThat(secondLookup.status()).isEqualTo(CacheStatus.HIT);
    assertThat(secondLookup.body()).isEqualTo("{\"choices\":[]}");
    assertThat(expiredLookup.status()).isEqualTo(CacheStatus.MISS);
  }

  @Test
  void forceRefreshBypassesRedisStore() {
    ExpiringFakeRedisStore store = new ExpiringFakeRedisStore(Clock.systemUTC());
    RedisResponseCache cache = new RedisResponseCache(store);
    CacheRequest request = new CacheRequest(
        "https://api.openai.com/v1/chat/completions",
        "{\"model\":\"gpt-4o-mini\"}",
        Map.of());
    cache.put(request, "{\"cached\":true}", Duration.ofMinutes(5));

    CacheLookup lookup = cache.get(new CacheRequest(
        request.url(),
        request.body(),
        Map.of("x-modelgate-cache-force-refresh", "true")));

    assertThat(lookup.status()).isEqualTo(CacheStatus.REFRESH);
    assertThat(lookup.body()).isNull();
  }

  private record StoredValue(String body, Instant expiresAt) {}

  private static final class ExpiringFakeRedisStore implements RedisCacheStore {
    private final Clock clock;
    private final Map<String, StoredValue> values = new ConcurrentHashMap<>();

    private ExpiringFakeRedisStore(Clock clock) {
      this.clock = clock;
    }

    @Override
    public Optional<String> get(String key) {
      StoredValue storedValue = values.get(key);
      if (storedValue == null) {
        return Optional.empty();
      }
      if (!storedValue.expiresAt().isAfter(clock.instant())) {
        values.remove(key);
        return Optional.empty();
      }
      return Optional.of(storedValue.body());
    }

    @Override
    public void set(String key, String body, Duration ttl) {
      values.put(key, new StoredValue(body, clock.instant().plus(ttl)));
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
