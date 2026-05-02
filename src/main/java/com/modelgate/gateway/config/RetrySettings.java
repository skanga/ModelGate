package com.modelgate.gateway.config;

import java.util.List;

public record RetrySettings(int attempts, List<Integer> onStatusCodes, boolean useRetryAfterHeader) {
  public RetrySettings {
    onStatusCodes = onStatusCodes == null ? List.of() : List.copyOf(onStatusCodes);
  }

  public RetrySettings(int attempts, boolean useRetryAfterHeader) {
    this(attempts, List.of(), useRetryAfterHeader);
  }

  public static RetrySettings defaults() {
    return new RetrySettings(0, List.of(), false);
  }
}
