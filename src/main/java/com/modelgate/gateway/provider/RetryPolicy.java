package com.modelgate.gateway.provider;

import java.util.List;

public record RetryPolicy(int attempts, List<Integer> onStatusCodes, boolean useRetryAfterHeader) {
  public RetryPolicy {
    onStatusCodes = onStatusCodes == null ? List.of() : List.copyOf(onStatusCodes);
  }
}
