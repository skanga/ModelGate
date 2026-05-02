package com.modelgate.gateway.observability;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record OpenTelemetrySettings(
    boolean enabled,
    boolean tracesEnabled,
    URI traceEndpoint,
    boolean metricsEnabled,
    URI metricsEndpoint,
    boolean logsEnabled,
    URI logsEndpoint,
    String traceProtocol,
    Map<String, String> traceHeaders,
    Duration traceTimeout,
    String traceCompression,
    Path traceTrustedCertificatePath,
    Path traceClientKeyPath,
    Path traceClientCertificatePath,
    String traceSampler,
    String traceSamplerArg,
    Duration spanScheduleDelay,
    Duration spanExportTimeout,
    int spanMaxQueueSize,
    int spanMaxExportBatchSize,
    String metricsProtocol,
    Map<String, String> metricsHeaders,
    Duration metricsTimeout,
    String metricsCompression,
    Path metricsTrustedCertificatePath,
    Path metricsClientKeyPath,
    Path metricsClientCertificatePath,
    String metricsTemporalityPreference,
    String metricsDefaultHistogramAggregation,
    String metricsExemplarFilter,
    int metricCardinalityLimit,
    String exporterMemoryMode,
    boolean otlpRetryDisabled,
    Duration metricExportInterval,
    Duration metricExportTimeout,
    String logsProtocol,
    Map<String, String> logsHeaders,
    Duration logsTimeout,
    String logsCompression,
    Path logsTrustedCertificatePath,
    Path logsClientKeyPath,
    Path logsClientCertificatePath,
    Duration logScheduleDelay,
    Duration logExportTimeout,
    int logMaxQueueSize,
    int logMaxExportBatchSize,
    int attributeValueLengthLimit,
    int spanAttributeValueLengthLimit,
    int logRecordAttributeValueLengthLimit,
    int attributeCountLimit,
    int spanAttributeCountLimit,
    int spanEventCountLimit,
    int spanLinkCountLimit,
    int eventAttributeCountLimit,
    int linkAttributeCountLimit,
    int logRecordAttributeCountLimit,
    String serviceName,
    Map<String, String> resourceAttributes,
    List<String> resourceDisabledKeys,
    List<String> propagators) {
  private static final URI DEFAULT_HTTP_TRACES_ENDPOINT = URI.create("http://localhost:4318/v1/traces");
  private static final URI DEFAULT_HTTP_METRICS_ENDPOINT = URI.create("http://localhost:4318/v1/metrics");
  private static final URI DEFAULT_HTTP_LOGS_ENDPOINT = URI.create("http://localhost:4318/v1/logs");
  private static final URI DEFAULT_GRPC_ENDPOINT = URI.create("http://localhost:4317");
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DEFAULT_PROCESSOR_EXPORT_TIMEOUT = Duration.ofSeconds(30);

  public OpenTelemetrySettings(
      boolean enabled,
      URI traceEndpoint,
      String protocol,
      Map<String, String> headers,
      String serviceName,
      Duration timeout) {
    this(
        enabled,
        enabled,
        traceEndpoint,
        enabled,
        endpointSibling(traceEndpoint, "metrics"),
        enabled,
        endpointSibling(traceEndpoint, "logs"),
        protocol,
        headers,
        timeout,
        "none",
        null,
        null,
        null,
        "parentbased_always_on",
        "",
        Duration.ofMillis(100),
        timeout,
        2048,
        512,
        protocol,
        headers,
        timeout,
        "none",
        null,
        null,
        null,
        "cumulative",
        "explicit_bucket_histogram",
        "trace_based",
        2000,
        "reusable_data",
        false,
        Duration.ofMillis(100),
        timeout,
        protocol,
        headers,
        timeout,
        "none",
        null,
        null,
        null,
        Duration.ofMillis(100),
        timeout,
        2048,
        512,
        -1,
        -1,
        -1,
        128,
        128,
        128,
        128,
        128,
        128,
        128,
        serviceName,
        Map.of(),
        List.of(),
        List.of("tracecontext", "baggage"));
  }

  public OpenTelemetrySettings {
    traceProtocol = normalizeProtocol(traceProtocol);
    metricsProtocol = normalizeProtocol(metricsProtocol);
    logsProtocol = normalizeProtocol(logsProtocol);
    traceCompression = normalizeCompression(traceCompression);
    metricsCompression = normalizeCompression(metricsCompression);
    logsCompression = normalizeCompression(logsCompression);
    traceTrustedCertificatePath = normalizePath(traceTrustedCertificatePath);
    traceClientKeyPath = normalizePath(traceClientKeyPath);
    traceClientCertificatePath = normalizePath(traceClientCertificatePath);
    metricsTrustedCertificatePath = normalizePath(metricsTrustedCertificatePath);
    metricsClientKeyPath = normalizePath(metricsClientKeyPath);
    metricsClientCertificatePath = normalizePath(metricsClientCertificatePath);
    logsTrustedCertificatePath = normalizePath(logsTrustedCertificatePath);
    logsClientKeyPath = normalizePath(logsClientKeyPath);
    logsClientCertificatePath = normalizePath(logsClientCertificatePath);
    traceSampler = normalizeTraceSampler(traceSampler);
    traceSamplerArg = traceSamplerArg == null ? "" : traceSamplerArg.trim();
    traceEndpoint = traceEndpoint == null
        ? ("grpc".equals(traceProtocol) ? DEFAULT_GRPC_ENDPOINT : DEFAULT_HTTP_TRACES_ENDPOINT)
        : traceEndpoint;
    metricsEndpoint = metricsEndpoint == null
        ? ("grpc".equals(metricsProtocol) ? DEFAULT_GRPC_ENDPOINT : DEFAULT_HTTP_METRICS_ENDPOINT)
        : metricsEndpoint;
    logsEndpoint = logsEndpoint == null
        ? ("grpc".equals(logsProtocol) ? DEFAULT_GRPC_ENDPOINT : DEFAULT_HTTP_LOGS_ENDPOINT)
        : logsEndpoint;
    traceHeaders = traceHeaders == null ? Map.of() : Map.copyOf(traceHeaders);
    metricsHeaders = metricsHeaders == null ? Map.of() : Map.copyOf(metricsHeaders);
    logsHeaders = logsHeaders == null ? Map.of() : Map.copyOf(logsHeaders);
    traceTimeout = validTimeout(traceTimeout);
    metricsTimeout = validTimeout(metricsTimeout);
    logsTimeout = validTimeout(logsTimeout);
    spanScheduleDelay = validDuration(spanScheduleDelay, Duration.ofSeconds(5));
    spanExportTimeout = validDuration(spanExportTimeout, DEFAULT_TIMEOUT);
    spanMaxQueueSize = positiveInt(spanMaxQueueSize, 2048);
    spanMaxExportBatchSize = boundedBatchSize(spanMaxExportBatchSize, 512, spanMaxQueueSize);
    logScheduleDelay = validDuration(logScheduleDelay, Duration.ofSeconds(1));
    logExportTimeout = validDuration(logExportTimeout, DEFAULT_TIMEOUT);
    logMaxQueueSize = positiveInt(logMaxQueueSize, 2048);
    logMaxExportBatchSize = boundedBatchSize(logMaxExportBatchSize, 512, logMaxQueueSize);
    metricsTemporalityPreference = normalizeMetricsTemporalityPreference(metricsTemporalityPreference);
    metricsDefaultHistogramAggregation = normalizeMetricsDefaultHistogramAggregation(metricsDefaultHistogramAggregation);
    metricsExemplarFilter = normalizeMetricsExemplarFilter(metricsExemplarFilter);
    metricCardinalityLimit = positiveInt(metricCardinalityLimit, 2000);
    exporterMemoryMode = normalizeExporterMemoryMode(exporterMemoryMode);
    metricExportInterval = validMetricExportInterval(metricExportInterval);
    metricExportTimeout = validDuration(metricExportTimeout, DEFAULT_TIMEOUT);
    attributeValueLengthLimit = limitInt(attributeValueLengthLimit, -1);
    spanAttributeValueLengthLimit = limitInt(spanAttributeValueLengthLimit, attributeValueLengthLimit);
    logRecordAttributeValueLengthLimit = limitInt(logRecordAttributeValueLengthLimit, attributeValueLengthLimit);
    attributeCountLimit = positiveInt(attributeCountLimit, 128);
    spanAttributeCountLimit = positiveInt(spanAttributeCountLimit, attributeCountLimit);
    spanEventCountLimit = positiveInt(spanEventCountLimit, 128);
    spanLinkCountLimit = positiveInt(spanLinkCountLimit, 128);
    eventAttributeCountLimit = positiveInt(eventAttributeCountLimit, attributeCountLimit);
    linkAttributeCountLimit = positiveInt(linkAttributeCountLimit, attributeCountLimit);
    logRecordAttributeCountLimit = positiveInt(logRecordAttributeCountLimit, attributeCountLimit);
    serviceName = serviceName == null || serviceName.isBlank() ? "modelgate" : serviceName.trim();
    Map<String, String> resourceAttributesCopy = new LinkedHashMap<>();
    resourceAttributesCopy.put("service.namespace", "modelgate");
    if (resourceAttributes != null) {
      resourceAttributesCopy.putAll(resourceAttributes);
    }
    resourceAttributesCopy.put("service.name", serviceName);
    if (resourceDisabledKeys != null) {
      resourceDisabledKeys.stream()
          .filter(key -> key != null && !key.isBlank())
          .map(String::trim)
          .forEach(resourceAttributesCopy::remove);
    }
    resourceAttributes = Map.copyOf(resourceAttributesCopy);
    resourceDisabledKeys = resourceDisabledKeys == null ? List.of() : List.copyOf(resourceDisabledKeys);
    propagators = normalizePropagators(propagators);
    enabled = enabled && (tracesEnabled || metricsEnabled || logsEnabled);
  }

  public String protocol() {
    return traceProtocol;
  }

  public Map<String, String> headers() {
    return traceHeaders;
  }

  public Duration timeout() {
    return traceTimeout;
  }

  public Duration shutdownTimeout() {
    return max(traceTimeout, metricsTimeout, logsTimeout);
  }

  public static OpenTelemetrySettings disabled() {
    return new OpenTelemetrySettings(
        false,
        false,
        DEFAULT_HTTP_TRACES_ENDPOINT,
        false,
        DEFAULT_HTTP_METRICS_ENDPOINT,
        false,
        DEFAULT_HTTP_LOGS_ENDPOINT,
        "http/protobuf",
        Map.of(),
        DEFAULT_TIMEOUT,
        "none",
        null,
        null,
        null,
        "parentbased_always_on",
        "",
        Duration.ofSeconds(5),
        DEFAULT_TIMEOUT,
        2048,
        512,
        "http/protobuf",
        Map.of(),
        DEFAULT_TIMEOUT,
        "none",
        null,
        null,
        null,
        "cumulative",
        "explicit_bucket_histogram",
        "trace_based",
        2000,
        "reusable_data",
        false,
        Duration.ofSeconds(60),
        DEFAULT_TIMEOUT,
        "http/protobuf",
        Map.of(),
        DEFAULT_TIMEOUT,
        "none",
        null,
        null,
        null,
        Duration.ofSeconds(1),
        DEFAULT_TIMEOUT,
        2048,
        512,
        -1,
        -1,
        -1,
        128,
        128,
        128,
        128,
        128,
        128,
        128,
        "modelgate",
        Map.of(),
        List.of(),
        List.of("none"));
  }

  public static OpenTelemetrySettings fromEnvironment(Map<String, String> environment) {
    Map<String, String> env = environment == null ? Map.of() : environment;
    String traceProtocol = normalizeProtocol(firstPresent(env, "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "OTEL_EXPORTER_OTLP_PROTOCOL"));
    String metricsProtocol = normalizeProtocol(firstPresent(env, "OTEL_EXPORTER_OTLP_METRICS_PROTOCOL", "OTEL_EXPORTER_OTLP_PROTOCOL"));
    String logsProtocol = normalizeProtocol(firstPresent(env, "OTEL_EXPORTER_OTLP_LOGS_PROTOCOL", "OTEL_EXPORTER_OTLP_PROTOCOL"));
    boolean sdkDisabled = booleanValue(configValue(env, "OTEL_SDK_DISABLED"));
    boolean tracesEnabled = signalEnabled(env, "TRACES");
    boolean metricsEnabled = signalEnabled(env, "METRICS");
    boolean logsEnabled = signalEnabled(env, "LOGS");
    boolean enabled = !sdkDisabled && (tracesEnabled || metricsEnabled || logsEnabled);
    Map<String, String> globalHeaders = parseHeaders(configValue(env, "OTEL_EXPORTER_OTLP_HEADERS"));
    Path globalCertificate = path(firstPresent(env, "OTEL_EXPORTER_OTLP_CERTIFICATE"));
    Path globalClientKey = path(firstPresent(env, "OTEL_EXPORTER_OTLP_CLIENT_KEY"));
    Path globalClientCertificate = path(firstPresent(env, "OTEL_EXPORTER_OTLP_CLIENT_CERTIFICATE"));
    return new OpenTelemetrySettings(
        enabled,
        tracesEnabled,
        signalEndpoint(env, "TRACES", traceProtocol),
        metricsEnabled,
        signalEndpoint(env, "METRICS", metricsProtocol),
        logsEnabled,
        signalEndpoint(env, "LOGS", logsProtocol),
        traceProtocol,
        mergeHeaders(globalHeaders, parseHeaders(configValue(env, "OTEL_EXPORTER_OTLP_TRACES_HEADERS"))),
        timeout(firstPresent(env, "OTEL_EXPORTER_OTLP_TRACES_TIMEOUT", "OTEL_EXPORTER_OTLP_TIMEOUT")),
        compression(firstPresent(env, "OTEL_EXPORTER_OTLP_TRACES_COMPRESSION", "OTEL_EXPORTER_OTLP_COMPRESSION")),
        firstPath(path(firstPresent(env, "OTEL_EXPORTER_OTLP_TRACES_CERTIFICATE")), globalCertificate),
        firstPath(path(firstPresent(env, "OTEL_EXPORTER_OTLP_TRACES_CLIENT_KEY")), globalClientKey),
        firstPath(path(firstPresent(env, "OTEL_EXPORTER_OTLP_TRACES_CLIENT_CERTIFICATE")), globalClientCertificate),
        traceSampler(configValue(env, "OTEL_TRACES_SAMPLER")),
        firstPresent(env, "OTEL_TRACES_SAMPLER_ARG"),
        durationMillis(configValue(env, "OTEL_BSP_SCHEDULE_DELAY"), Duration.ofSeconds(5)),
        durationMillis(configValue(env, "OTEL_BSP_EXPORT_TIMEOUT"), DEFAULT_PROCESSOR_EXPORT_TIMEOUT),
        intValue(configValue(env, "OTEL_BSP_MAX_QUEUE_SIZE"), 2048),
        intValue(configValue(env, "OTEL_BSP_MAX_EXPORT_BATCH_SIZE"), 512),
        metricsProtocol,
        mergeHeaders(globalHeaders, parseHeaders(configValue(env, "OTEL_EXPORTER_OTLP_METRICS_HEADERS"))),
        timeout(firstPresent(env, "OTEL_EXPORTER_OTLP_METRICS_TIMEOUT", "OTEL_EXPORTER_OTLP_TIMEOUT")),
        compression(firstPresent(env, "OTEL_EXPORTER_OTLP_METRICS_COMPRESSION", "OTEL_EXPORTER_OTLP_COMPRESSION")),
        firstPath(path(firstPresent(env, "OTEL_EXPORTER_OTLP_METRICS_CERTIFICATE")), globalCertificate),
        firstPath(path(firstPresent(env, "OTEL_EXPORTER_OTLP_METRICS_CLIENT_KEY")), globalClientKey),
        firstPath(path(firstPresent(env, "OTEL_EXPORTER_OTLP_METRICS_CLIENT_CERTIFICATE")), globalClientCertificate),
        metricsTemporalityPreference(configValue(env, "OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE")),
        metricsDefaultHistogramAggregation(configValue(env, "OTEL_EXPORTER_OTLP_METRICS_DEFAULT_HISTOGRAM_AGGREGATION")),
        metricsExemplarFilter(configValue(env, "OTEL_METRICS_EXEMPLAR_FILTER")),
        intValue(configValue(env, "OTEL_JAVA_METRICS_CARDINALITY_LIMIT"), 2000),
        exporterMemoryMode(configValue(env, "OTEL_JAVA_EXPORTER_MEMORY_MODE")),
        booleanValue(configValue(env, "OTEL_JAVA_EXPORTER_OTLP_RETRY_DISABLED")),
        metricExportInterval(configValue(env, "OTEL_METRIC_EXPORT_INTERVAL")),
        durationMillis(configValue(env, "OTEL_METRIC_EXPORT_TIMEOUT"), DEFAULT_TIMEOUT),
        logsProtocol,
        mergeHeaders(globalHeaders, parseHeaders(configValue(env, "OTEL_EXPORTER_OTLP_LOGS_HEADERS"))),
        timeout(firstPresent(env, "OTEL_EXPORTER_OTLP_LOGS_TIMEOUT", "OTEL_EXPORTER_OTLP_TIMEOUT")),
        compression(firstPresent(env, "OTEL_EXPORTER_OTLP_LOGS_COMPRESSION", "OTEL_EXPORTER_OTLP_COMPRESSION")),
        firstPath(path(firstPresent(env, "OTEL_EXPORTER_OTLP_LOGS_CERTIFICATE")), globalCertificate),
        firstPath(path(firstPresent(env, "OTEL_EXPORTER_OTLP_LOGS_CLIENT_KEY")), globalClientKey),
        firstPath(path(firstPresent(env, "OTEL_EXPORTER_OTLP_LOGS_CLIENT_CERTIFICATE")), globalClientCertificate),
        durationMillis(configValue(env, "OTEL_BLRP_SCHEDULE_DELAY"), Duration.ofSeconds(1)),
        durationMillis(configValue(env, "OTEL_BLRP_EXPORT_TIMEOUT"), DEFAULT_PROCESSOR_EXPORT_TIMEOUT),
        intValue(configValue(env, "OTEL_BLRP_MAX_QUEUE_SIZE"), 2048),
        intValue(configValue(env, "OTEL_BLRP_MAX_EXPORT_BATCH_SIZE"), 512),
        limitInt(configValue(env, "OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT"), -1),
        limitInt(configValue(env, "OTEL_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT"),
            limitInt(configValue(env, "OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT"), -1)),
        limitInt(configValue(env, "OTEL_LOGRECORD_ATTRIBUTE_VALUE_LENGTH_LIMIT"),
            limitInt(configValue(env, "OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT"), -1)),
        intValue(configValue(env, "OTEL_ATTRIBUTE_COUNT_LIMIT"), 128),
        intValue(configValue(env, "OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT"), intValue(configValue(env, "OTEL_ATTRIBUTE_COUNT_LIMIT"), 128)),
        intValue(configValue(env, "OTEL_SPAN_EVENT_COUNT_LIMIT"), 128),
        intValue(configValue(env, "OTEL_SPAN_LINK_COUNT_LIMIT"), 128),
        intValue(configValue(env, "OTEL_EVENT_ATTRIBUTE_COUNT_LIMIT"), intValue(configValue(env, "OTEL_ATTRIBUTE_COUNT_LIMIT"), 128)),
        intValue(configValue(env, "OTEL_LINK_ATTRIBUTE_COUNT_LIMIT"), intValue(configValue(env, "OTEL_ATTRIBUTE_COUNT_LIMIT"), 128)),
        intValue(configValue(env, "OTEL_LOGRECORD_ATTRIBUTE_COUNT_LIMIT"), intValue(configValue(env, "OTEL_ATTRIBUTE_COUNT_LIMIT"), 128)),
        firstPresent(env, "OTEL_SERVICE_NAME"),
        parseKeyValuePairs(configValue(env, "OTEL_RESOURCE_ATTRIBUTES")),
        parseList(configValue(env, "OTEL_RESOURCE_DISABLED_KEYS")),
        parsePropagators(configValue(env, "OTEL_PROPAGATORS")));
  }

  private static List<String> parseList(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(item -> !item.isEmpty())
        .distinct()
        .toList();
  }

  private static List<String> parsePropagators(String value) {
    if (value == null || value.isBlank()) {
      return List.of("tracecontext", "baggage");
    }
    return Arrays.stream(value.split(","))
        .map(OpenTelemetrySettings::normalized)
        .filter(item -> item != null && !item.isBlank())
        .toList();
  }

  private static List<String> normalizePropagators(List<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of("tracecontext", "baggage");
    }
    List<String> normalized = values.stream()
        .map(OpenTelemetrySettings::normalized)
        .filter(OpenTelemetrySettings::supportedPropagator)
        .distinct()
        .toList();
    return normalized.isEmpty() ? List.of("tracecontext", "baggage") : List.copyOf(normalized);
  }

  private static boolean supportedPropagator(String item) {
    return "tracecontext".equals(item)
        || "baggage".equals(item)
        || "b3".equals(item)
        || "b3multi".equals(item)
        || "jaeger".equals(item)
        || "ottrace".equals(item)
        || "xray".equals(item)
        || "xray-lambda".equals(item)
        || "none".equals(item);
  }

  private static boolean signalEnabled(Map<String, String> env, String signal) {
    List<String> exporters = exporterValues(configValue(env, "OTEL_" + signal + "_EXPORTER"));
    if (exporters.contains("otlp")) {
      return true;
    }
    if (exporters.contains("none")) {
      return false;
    }
    List<String> globalExporters = exporterValues(configValue(env, "OTEL_EXPORTER"));
    if (globalExporters.contains("otlp")) {
      return true;
    }
    if (globalExporters.contains("none")) {
      return false;
    }
    return firstPresent(env, "OTEL_EXPORTER_OTLP_" + signal + "_ENDPOINT", "OTEL_EXPORTER_OTLP_ENDPOINT") != null;
  }

  private static List<String> exporterValues(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split(","))
        .map(OpenTelemetrySettings::normalized)
        .filter(item -> item != null && !item.isBlank())
        .distinct()
        .toList();
  }

  private static URI signalEndpoint(Map<String, String> env, String signal, String protocol) {
    String signalEndpoint = firstPresent(env, "OTEL_EXPORTER_OTLP_" + signal + "_ENDPOINT");
    if (signalEndpoint != null && !signalEndpoint.isBlank()) {
      return URI.create(signalEndpoint.trim());
    }
    String baseEndpoint = firstPresent(env, "OTEL_EXPORTER_OTLP_ENDPOINT");
    if (baseEndpoint == null || baseEndpoint.isBlank()) {
      if ("grpc".equals(protocol)) {
        return DEFAULT_GRPC_ENDPOINT;
      }
      return switch (signal) {
        case "METRICS" -> DEFAULT_HTTP_METRICS_ENDPOINT;
        case "LOGS" -> DEFAULT_HTTP_LOGS_ENDPOINT;
        default -> DEFAULT_HTTP_TRACES_ENDPOINT;
      };
    }
    URI base = URI.create(baseEndpoint.trim());
    if ("grpc".equals(protocol)) {
      return base;
    }
    String baseText = base.toString();
    String separator = baseText.endsWith("/") ? "" : "/";
    return URI.create(baseText + separator + "v1/" + signal.toLowerCase(Locale.ROOT));
  }

  private static URI endpointSibling(URI traceEndpoint, String signal) {
    if (traceEndpoint == null) {
      return null;
    }
    String value = traceEndpoint.toString();
    if (value.endsWith("/v1/traces")) {
      return URI.create(value.substring(0, value.length() - "traces".length()) + signal);
    }
    return traceEndpoint;
  }

  private static Map<String, String> parseHeaders(String value) {
    return parseKeyValuePairs(value);
  }

  private static Map<String, String> parseKeyValuePairs(String value) {
    if (value == null || value.isBlank()) {
      return Map.of();
    }
    Map<String, String> pairs = new LinkedHashMap<>();
    Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(item -> !item.isEmpty())
        .forEach(item -> {
          int separator = item.indexOf('=');
          if (separator <= 0) {
            return;
          }
          String name = item.substring(0, separator).trim();
          String headerValue = item.substring(separator + 1).trim();
          if (!name.isEmpty()) {
            pairs.put(name, URLDecoder.decode(headerValue, StandardCharsets.UTF_8));
          }
        });
    return pairs;
  }

  private static Map<String, String> mergeHeaders(Map<String, String> global, Map<String, String> signal) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.putAll(global);
    headers.putAll(signal);
    return headers;
  }

  private static Duration timeout(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT_TIMEOUT;
    }
    try {
      long millis = Long.parseLong(value.trim());
      return millis <= 0 ? DEFAULT_TIMEOUT : Duration.ofMillis(millis);
    } catch (NumberFormatException exception) {
      return DEFAULT_TIMEOUT;
    }
  }

  private static Duration validTimeout(Duration timeout) {
    return timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
  }

  private static Duration durationMillis(String value, Duration defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      long millis = Long.parseLong(value.trim());
      return millis <= 0 ? defaultValue : Duration.ofMillis(millis);
    } catch (NumberFormatException exception) {
      return defaultValue;
    }
  }

  private static Duration validDuration(Duration value, Duration defaultValue) {
    return value == null || value.isNegative() || value.isZero() ? defaultValue : value;
  }

  private static Path path(String value) {
    return value == null || value.isBlank() ? null : Path.of(value.trim());
  }

  private static Path firstPath(Path preferred, Path fallback) {
    return preferred == null ? fallback : preferred;
  }

  private static Path normalizePath(Path path) {
    return path == null ? null : path.normalize();
  }

  private static int intValue(String value, int defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed <= 0 ? defaultValue : parsed;
    } catch (NumberFormatException exception) {
      return defaultValue;
    }
  }

  private static int limitInt(String value, int defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed < -1 || parsed == 0 ? defaultValue : parsed;
    } catch (NumberFormatException exception) {
      return defaultValue;
    }
  }

  private static int limitInt(int value, int defaultValue) {
    return value < -1 || value == 0 ? defaultValue : value;
  }

  private static int positiveInt(int value, int defaultValue) {
    return value <= 0 ? defaultValue : value;
  }

  private static int boundedBatchSize(int value, int defaultValue, int maxQueueSize) {
    int positive = positiveInt(value, defaultValue);
    return Math.min(positive, maxQueueSize);
  }

  private static Duration metricExportInterval(String value) {
    if (value == null || value.isBlank()) {
      return Duration.ofSeconds(60);
    }
    try {
      long millis = Long.parseLong(value.trim());
      return millis <= 0 ? Duration.ofSeconds(60) : Duration.ofMillis(millis);
    } catch (NumberFormatException exception) {
      return Duration.ofSeconds(60);
    }
  }

  private static Duration validMetricExportInterval(Duration interval) {
    return interval == null || interval.isNegative() || interval.isZero() ? Duration.ofSeconds(60) : interval;
  }

  private static String metricsTemporalityPreference(String value) {
    return normalizeMetricsTemporalityPreference(value);
  }

  private static String normalizeMetricsTemporalityPreference(String value) {
    String normalized = normalized(value);
    if ("delta".equals(normalized) || "lowmemory".equals(normalized)) {
      return normalized;
    }
    return "cumulative";
  }

  private static String metricsDefaultHistogramAggregation(String value) {
    return normalizeMetricsDefaultHistogramAggregation(value);
  }

  private static String normalizeMetricsDefaultHistogramAggregation(String value) {
    String normalized = normalized(value);
    if ("base2_exponential_bucket_histogram".equals(normalized)) {
      return normalized;
    }
    return "explicit_bucket_histogram";
  }

  private static String metricsExemplarFilter(String value) {
    return normalizeMetricsExemplarFilter(value);
  }

  private static String normalizeMetricsExemplarFilter(String value) {
    String normalized = normalized(value);
    if ("always_on".equals(normalized) || "always_off".equals(normalized)) {
      return normalized;
    }
    return "trace_based";
  }

  private static String exporterMemoryMode(String value) {
    return normalizeExporterMemoryMode(value);
  }

  private static String normalizeExporterMemoryMode(String value) {
    String normalized = normalized(value);
    if ("immutable_data".equals(normalized)) {
      return normalized;
    }
    return "reusable_data";
  }

  private static String compression(String value) {
    return normalizeCompression(value);
  }

  private static String traceSampler(String value) {
    return normalizeTraceSampler(value);
  }

  private static String normalizeTraceSampler(String value) {
    String normalized = normalized(value);
    if ("always_on".equals(normalized)
        || "always_off".equals(normalized)
        || "traceidratio".equals(normalized)
        || "parentbased_always_on".equals(normalized)
        || "parentbased_always_off".equals(normalized)
        || "parentbased_traceidratio".equals(normalized)) {
      return normalized;
    }
    return "parentbased_always_on";
  }

  private static String normalizeCompression(String value) {
    String normalized = normalized(value);
    if ("gzip".equals(normalized)) {
      return "gzip";
    }
    return "none";
  }

  private static Duration max(Duration first, Duration second, Duration third) {
    Duration value = first;
    if (second.compareTo(value) > 0) {
      value = second;
    }
    if (third.compareTo(value) > 0) {
      value = third;
    }
    return value;
  }

  private static String normalizeProtocol(String protocol) {
    String normalized = normalized(protocol);
    return normalized == null || normalized.isBlank() ? "http/protobuf" : normalized;
  }

  private static String normalized(String value) {
    return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean booleanValue(String value) {
    return "true".equalsIgnoreCase(value);
  }

  private static String firstPresent(Map<String, String> env, String... keys) {
    for (String key : keys) {
      String value = configValue(env, key);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static String configValue(Map<String, String> env, String key) {
    String property = System.getProperty(propertyName(key));
    if (property != null && !property.isBlank()) {
      return property;
    }
    return env.get(key);
  }

  private static String propertyName(String key) {
    return key.toLowerCase(Locale.ROOT).replace('_', '.');
  }
}
