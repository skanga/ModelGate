package com.modelgate.gateway.config;

import java.util.List;
import java.util.Map;

public record GatewayConfig(
    String provider,
    String apiKey,
    Strategy strategy,
    RetrySettings retry,
    CacheSettings cache,
    ProviderOptions providerOptions,
    long requestTimeoutMillis,
    String customHost,
    List<String> forwardHeaders,
    List<Map<String, Object>> inputGuardrails,
    List<Map<String, Object>> outputGuardrails,
    List<Map<String, Object>> defaultInputGuardrails,
    List<Map<String, Object>> defaultOutputGuardrails,
    List<Target> targets) {

  public GatewayConfig {
    retry = retry == null ? RetrySettings.defaults() : retry;
    cache = cache == null ? CacheSettings.defaults() : cache;
    providerOptions = providerOptions == null ? ProviderOptions.EMPTY : providerOptions;
    forwardHeaders = forwardHeaders == null ? List.of() : List.copyOf(forwardHeaders);
    inputGuardrails = inputGuardrails == null ? List.of() : List.copyOf(inputGuardrails);
    outputGuardrails = outputGuardrails == null ? List.of() : List.copyOf(outputGuardrails);
    defaultInputGuardrails = defaultInputGuardrails == null ? List.of() : List.copyOf(defaultInputGuardrails);
    defaultOutputGuardrails = defaultOutputGuardrails == null ? List.of() : List.copyOf(defaultOutputGuardrails);
    targets = targets == null ? List.of() : List.copyOf(targets);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String provider;
    private String apiKey;
    private Strategy strategy;
    private RetrySettings retry;
    private CacheSettings cache;
    private ProviderOptions providerOptions = ProviderOptions.EMPTY;
    private long requestTimeoutMillis;
    private String customHost;
    private List<String> forwardHeaders = List.of();
    private List<Map<String, Object>> inputGuardrails = List.of();
    private List<Map<String, Object>> outputGuardrails = List.of();
    private List<Map<String, Object>> defaultInputGuardrails = List.of();
    private List<Map<String, Object>> defaultOutputGuardrails = List.of();
    private List<Target> targets = List.of();

    public Builder provider(String provider) {
      this.provider = provider;
      return this;
    }

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder strategy(Strategy strategy) {
      this.strategy = strategy;
      return this;
    }

    public Builder retry(RetrySettings retry) {
      this.retry = retry;
      return this;
    }

    public Builder cache(CacheSettings cache) {
      this.cache = cache;
      return this;
    }

    public Builder providerOptions(ProviderOptions providerOptions) {
      this.providerOptions = providerOptions;
      return this;
    }

    public Builder requestTimeoutMillis(long requestTimeoutMillis) {
      this.requestTimeoutMillis = requestTimeoutMillis;
      return this;
    }

    public Builder customHost(String customHost) {
      this.customHost = customHost;
      return this;
    }

    public Builder forwardHeaders(List<String> forwardHeaders) {
      this.forwardHeaders = forwardHeaders;
      return this;
    }

    public Builder inputGuardrails(List<Map<String, Object>> inputGuardrails) {
      this.inputGuardrails = inputGuardrails;
      return this;
    }

    public Builder outputGuardrails(List<Map<String, Object>> outputGuardrails) {
      this.outputGuardrails = outputGuardrails;
      return this;
    }

    public Builder defaultInputGuardrails(List<Map<String, Object>> defaultInputGuardrails) {
      this.defaultInputGuardrails = defaultInputGuardrails;
      return this;
    }

    public Builder defaultOutputGuardrails(List<Map<String, Object>> defaultOutputGuardrails) {
      this.defaultOutputGuardrails = defaultOutputGuardrails;
      return this;
    }

    public Builder targets(List<Target> targets) {
      this.targets = targets;
      return this;
    }

    public GatewayConfig build() {
      return new GatewayConfig(
          provider,
          apiKey,
          strategy,
          retry,
          cache,
          providerOptions,
          requestTimeoutMillis,
          customHost,
          forwardHeaders,
          inputGuardrails,
          outputGuardrails,
          defaultInputGuardrails,
          defaultOutputGuardrails,
          targets);
    }
  }
}
