package com.modelgate.gateway.config;

import java.util.List;

public record Target(
    String name,
    String provider,
    String apiKey,
    String customHost,
    ProviderOptions providerOptions,
    double weight,
    RetrySettings retry,
    long requestTimeoutMillis,
    List<String> forwardHeaders) {
  public Target {
    providerOptions = providerOptions == null ? ProviderOptions.EMPTY : providerOptions;
    if (Double.isNaN(weight) || Double.isInfinite(weight)) {
      throw new IllegalArgumentException("Target weight must be finite");
    }
    forwardHeaders = forwardHeaders == null ? null : List.copyOf(forwardHeaders);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String name;
    private String provider;
    private String apiKey;
    private String customHost;
    private ProviderOptions providerOptions = ProviderOptions.EMPTY;
    private double weight = 1;
    private RetrySettings retry;
    private long requestTimeoutMillis;
    private List<String> forwardHeaders;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder provider(String provider) {
      this.provider = provider;
      return this;
    }

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder customHost(String customHost) {
      this.customHost = customHost;
      return this;
    }

    public Builder providerOptions(ProviderOptions providerOptions) {
      this.providerOptions = providerOptions;
      return this;
    }

    public Builder weight(double weight) {
      this.weight = weight;
      return this;
    }

    public Builder retry(RetrySettings retry) {
      this.retry = retry;
      return this;
    }

    public Builder requestTimeoutMillis(long requestTimeoutMillis) {
      this.requestTimeoutMillis = requestTimeoutMillis;
      return this;
    }

    public Builder forwardHeaders(List<String> forwardHeaders) {
      this.forwardHeaders = forwardHeaders;
      return this;
    }

    public Target build() {
      return new Target(name, provider, apiKey, customHost, providerOptions, weight, retry, requestTimeoutMillis, forwardHeaders);
    }
  }
}
