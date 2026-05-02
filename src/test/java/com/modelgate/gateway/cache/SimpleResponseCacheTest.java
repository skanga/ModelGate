package com.modelgate.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimpleResponseCacheTest {
  @Test
  void storesAndReturnsResponseUntilTtlExpires() {
    MutableClock clock = new MutableClock(Instant.parse("2026-04-25T12:00:00Z"));
    SimpleResponseCache cache = new SimpleResponseCache(clock);
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
  void forceRefreshBypassesExistingEntry() {
    MutableClock clock = new MutableClock(Instant.parse("2026-04-25T12:00:00Z"));
    SimpleResponseCache cache = new SimpleResponseCache(clock);
    CacheRequest request = new CacheRequest(
        "https://api.openai.com/v1/chat/completions",
        "{\"model\":\"gpt-4o-mini\"}",
        Map.of());
    cache.put(request, "{\"cached\":true}", Duration.ofMinutes(5));

    CacheLookup lookup = cache.get(new CacheRequest(
        request.url(),
        request.body(),
        Map.of("x-portkey-cache-force-refresh", "true")));

    assertThat(lookup.status()).isEqualTo(CacheStatus.REFRESH);
    assertThat(lookup.body()).isNull();
  }

  @Test
  void modelgateForceRefreshHeaderBypassesExistingEntry() {
    MutableClock clock = new MutableClock(Instant.parse("2026-04-25T12:00:00Z"));
    SimpleResponseCache cache = new SimpleResponseCache(clock);
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

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
