package com.modelgate.gateway.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.contrib.awsxray.propagator.AwsXrayLambdaPropagator;
import io.opentelemetry.contrib.awsxray.propagator.AwsXrayPropagator;
import io.opentelemetry.extension.trace.propagation.B3Propagator;
import io.opentelemetry.extension.trace.propagation.JaegerPropagator;
import io.opentelemetry.extension.trace.propagation.OtTracePropagator;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.LogLimits;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.ExemplarFilter;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.DefaultAggregationSelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.resources.ResourceBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import io.opentelemetry.sdk.metrics.data.MetricData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class OpenTelemetryPipeline implements AutoCloseable {
  private static final String INSTRUMENTATION_NAME = "com.modelgate.gateway";
  private static final OpenTelemetryPipeline NOOP =
      new OpenTelemetryPipeline(
          false,
          OpenTelemetry.noop().getTracer(INSTRUMENTATION_NAME),
          OpenTelemetry.noop().getMeter(INSTRUMENTATION_NAME),
          OpenTelemetry.noop().getLogsBridge().get(INSTRUMENTATION_NAME),
          null,
          null,
          null,
          new ExportFailureRecorder(),
          Duration.ZERO);

  private final boolean enabled;
  private final Tracer tracer;
  private final Meter meter;
  private final Logger logger;
  private final SdkTracerProvider tracerProvider;
  private final SdkMeterProvider meterProvider;
  private final SdkLoggerProvider loggerProvider;
  private final ExportFailureRecorder exportFailureRecorder;
  private final LongCounter requestCounter;
  private final DoubleHistogram requestDuration;
  private final Duration timeout;

  private OpenTelemetryPipeline(
      boolean enabled,
      Tracer tracer,
      Meter meter,
      Logger logger,
      SdkTracerProvider tracerProvider,
      SdkMeterProvider meterProvider,
      SdkLoggerProvider loggerProvider,
      ExportFailureRecorder exportFailureRecorder,
      Duration timeout) {
    this.enabled = enabled;
    this.tracer = tracer;
    this.meter = meter;
    this.logger = logger;
    this.tracerProvider = tracerProvider;
    this.meterProvider = meterProvider;
    this.loggerProvider = loggerProvider;
    this.exportFailureRecorder = exportFailureRecorder == null ? new ExportFailureRecorder() : exportFailureRecorder;
    this.requestCounter = meter.counterBuilder("modelgate.gateway.requests")
        .setDescription("Gateway requests observed by ModelGate")
        .build();
    this.requestDuration = meter.histogramBuilder("modelgate.gateway.request.duration")
        .setDescription("Gateway request duration")
        .setUnit("ms")
        .build();
    this.exportFailureRecorder.attach(meter);
    this.timeout = timeout;
  }

  public static OpenTelemetryPipeline noop() {
    return NOOP;
  }

  public static OpenTelemetryPipeline create(OpenTelemetrySettings settings) {
    OpenTelemetrySettings resolvedSettings = settings == null ? OpenTelemetrySettings.disabled() : settings;
    if (!resolvedSettings.enabled()) {
      return noop();
    }
    ResourceBuilder resourceBuilder = Resource.builder();
    resolvedSettings.resourceAttributes().forEach(resourceBuilder::put);
    Resource resource = Resource.getDefault().merge(resourceBuilder.build());
    ExportFailureRecorder exportFailureRecorder = new ExportFailureRecorder();
    SdkTracerProvider tracerProvider = resolvedSettings.tracesEnabled()
        ? SdkTracerProvider.builder()
            .setResource(resource)
            .setSpanLimits(spanLimits(resolvedSettings))
            .setSampler(traceSampler(resolvedSettings))
            .addSpanProcessor(BatchSpanProcessor.builder(spanExporter(resolvedSettings, exportFailureRecorder))
                .setExporterTimeout(resolvedSettings.spanExportTimeout())
                .setScheduleDelay(resolvedSettings.spanScheduleDelay())
                .setMaxQueueSize(resolvedSettings.spanMaxQueueSize())
                .setMaxExportBatchSize(resolvedSettings.spanMaxExportBatchSize())
                .build())
            .build()
        : null;
    SdkMeterProvider meterProvider = resolvedSettings.metricsEnabled()
        ? SdkMeterProvider.builder()
            .setResource(resource)
            .setExemplarFilter(metricExemplarFilter(resolvedSettings))
            .registerMetricReader(
                PeriodicMetricReader.builder(metricExporter(resolvedSettings, exportFailureRecorder))
                    .setInterval(resolvedSettings.metricExportInterval())
                    .build(),
                ignored -> resolvedSettings.metricCardinalityLimit())
            .build()
        : null;
    SdkLoggerProvider loggerProvider = resolvedSettings.logsEnabled()
        ? SdkLoggerProvider.builder()
            .setResource(resource)
            .setLogLimits(() -> logLimits(resolvedSettings))
            .addLogRecordProcessor(BatchLogRecordProcessor.builder(logRecordExporter(resolvedSettings, exportFailureRecorder))
                .setExporterTimeout(resolvedSettings.logExportTimeout())
                .setScheduleDelay(resolvedSettings.logScheduleDelay())
                .setMaxQueueSize(resolvedSettings.logMaxQueueSize())
                .setMaxExportBatchSize(resolvedSettings.logMaxExportBatchSize())
                .build())
            .build()
        : null;
    OpenTelemetrySdkBuilder sdkBuilder = OpenTelemetrySdk.builder()
        .setPropagators(configuredPropagators(resolvedSettings));
    if (tracerProvider != null) {
      sdkBuilder.setTracerProvider(tracerProvider);
    }
    if (meterProvider != null) {
      sdkBuilder.setMeterProvider(meterProvider);
    }
    if (loggerProvider != null) {
      sdkBuilder.setLoggerProvider(loggerProvider);
    }
    OpenTelemetrySdk sdk = sdkBuilder.build();
    return new OpenTelemetryPipeline(
        true,
        sdk.getTracer(INSTRUMENTATION_NAME),
        sdk.getMeter(INSTRUMENTATION_NAME),
        sdk.getLogsBridge().get(INSTRUMENTATION_NAME),
        tracerProvider,
        meterProvider,
        loggerProvider,
        exportFailureRecorder,
        resolvedSettings.shutdownTimeout());
  }

  public boolean enabled() {
    return enabled;
  }

  public long exportFailureCount(String signal) {
    return exportFailureRecorder.count(signal);
  }

  public void recordProviderSpan(
      String traceId,
      String spanId,
      String provider,
      String endpointPath,
      String model,
      int status,
      Duration duration) {
    recordProviderSpan(traceId, spanId, provider, endpointPath, model, status, duration, ProviderResponseMetadata.empty());
  }

  public void recordGatewayTrace(
      String traceId,
      String endpointPath,
      String method,
      String provider,
      String model,
      int status,
      Duration totalDuration,
      Duration inputGuardrailsDuration,
      Duration providerDuration,
      Duration outputGuardrailsDuration,
      ProviderResponseMetadata metadata) {
    if (!enabled || tracerProvider == null) {
      return;
    }
    ProviderResponseMetadata safeMetadata = metadata == null ? ProviderResponseMetadata.empty() : metadata;
    long endEpochNanos = System.currentTimeMillis() * 1_000_000L;
    long totalNanos = Math.max(0, totalDuration == null ? 0 : totalDuration.toNanos());
    long startEpochNanos = endEpochNanos - totalNanos;
    Span gatewaySpan = tracer.spanBuilder("gateway_request " + valueOrUnknown(method) + " " + valueOrUnknown(endpointPath))
        .setSpanKind(SpanKind.SERVER)
        .setStartTimestamp(startEpochNanos, TimeUnit.NANOSECONDS)
        .setAttribute("modelgate.component", "gateway")
        .setAttribute("modelgate.trace_id", valueOrUnknown(traceId))
        .setAttribute("http.request.method", valueOrUnknown(method))
        .setAttribute("http.route", valueOrUnknown(endpointPath))
        .setAttribute("http.response.status_code", status)
        .setAttribute("gen_ai.provider.name", valueOrUnknown(provider))
        .setAttribute("gen_ai.request.model", valueOrUnknown(model))
        .setAttribute("gen_ai.usage.input_tokens", safeMetadata.promptTokens())
        .setAttribute("gen_ai.usage.output_tokens", safeMetadata.completionTokens())
        .setAttribute("gen_ai.usage.total_tokens", safeMetadata.totalTokens())
        .setAttribute("gen_ai.response.finish_reasons", valueOrUnknown(safeMetadata.finishReason()))
        .startSpan();
    try (Scope ignored = gatewaySpan.makeCurrent()) {
      gatewaySpan.setStatus(status >= 500 ? StatusCode.ERROR : StatusCode.OK);
      long childStart = startEpochNanos;
      childStart = recordChildSpan(
          "guardrail_input",
          "guardrail",
          childStart,
          inputGuardrailsDuration,
          status,
          span -> span
              .setAttribute("modelgate.parent_component", "gateway")
              .setAttribute("modelgate.eval.phase", "input")
              .setAttribute("modelgate.eval.input_guardrails_ms", millis(inputGuardrailsDuration)));
      childStart = recordChildSpan(
          "provider_request " + valueOrUnknown(provider),
          "provider",
          childStart,
          providerDuration,
          status,
          span -> span
              .setAttribute("modelgate.parent_component", "gateway")
              .setAttribute("gen_ai.operation.name", "provider_request")
              .setAttribute("gen_ai.provider.name", valueOrUnknown(provider))
              .setAttribute("gen_ai.request.model", valueOrUnknown(model))
              .setAttribute("gen_ai.usage.input_tokens", safeMetadata.promptTokens())
              .setAttribute("gen_ai.usage.output_tokens", safeMetadata.completionTokens())
              .setAttribute("gen_ai.usage.total_tokens", safeMetadata.totalTokens())
              .setAttribute("gen_ai.response.finish_reasons", valueOrUnknown(safeMetadata.finishReason()))
              .setAttribute("modelgate.response.tool_call_count", safeMetadata.toolCallCount())
              .setAttribute("modelgate.response.refused", safeMetadata.refused())
              .setAttribute("modelgate.response.safety_flagged", safeMetadata.safetyFlagged())
              .setAttribute("modelgate.latency.provider_ms", millis(providerDuration)));
      childStart = recordChildSpan(
          "guardrail_output",
          "guardrail",
          childStart,
          outputGuardrailsDuration,
          status,
          span -> span
              .setAttribute("modelgate.parent_component", "gateway")
              .setAttribute("modelgate.eval.phase", "output")
              .setAttribute("modelgate.eval.output_guardrails_ms", millis(outputGuardrailsDuration)));
      recordChildSpan(
          "eval_guardrails",
          "eval",
          childStart,
          safeDuration(inputGuardrailsDuration).plus(safeDuration(outputGuardrailsDuration)),
          status,
          span -> span
              .setAttribute("modelgate.parent_component", "gateway")
              .setAttribute("modelgate.eval.input_guardrails_ms", millis(inputGuardrailsDuration))
              .setAttribute("modelgate.eval.output_guardrails_ms", millis(outputGuardrailsDuration))
              .setAttribute("modelgate.eval.decision", status == 246 ? "blocked" : "allowed"));
    } finally {
      gatewaySpan.end(endEpochNanos, TimeUnit.NANOSECONDS);
    }
  }

  public void recordProviderSpan(
      String traceId,
      String spanId,
      String provider,
      String endpointPath,
      String model,
      int status,
      Duration duration,
      ProviderResponseMetadata metadata) {
    if (!enabled) {
      return;
    }
    ProviderResponseMetadata safeMetadata = metadata == null ? ProviderResponseMetadata.empty() : metadata;
    long endEpochNanos = System.currentTimeMillis() * 1_000_000L;
    long startEpochNanos = endEpochNanos - Math.max(0, duration == null ? 0 : duration.toNanos());
    Span span = tracer.spanBuilder("provider_request " + valueOrUnknown(provider))
        .setSpanKind(SpanKind.CLIENT)
        .setStartTimestamp(startEpochNanos, TimeUnit.NANOSECONDS)
        .setAttribute("gen_ai.operation.name", "provider_request")
        .setAttribute("gen_ai.provider.name", valueOrUnknown(provider))
        .setAttribute("gen_ai.request.model", valueOrUnknown(model))
        .setAttribute("http.route", valueOrUnknown(endpointPath))
        .setAttribute("http.response.status_code", status)
        .setAttribute("modelgate.trace_id", valueOrUnknown(traceId))
        .setAttribute("modelgate.span_id", valueOrUnknown(spanId))
        .setAttribute("gen_ai.usage.input_tokens", safeMetadata.promptTokens())
        .setAttribute("gen_ai.usage.output_tokens", safeMetadata.completionTokens())
        .setAttribute("gen_ai.usage.total_tokens", safeMetadata.totalTokens())
        .setAttribute("gen_ai.response.finish_reasons", valueOrUnknown(safeMetadata.finishReason()))
        .setAttribute("modelgate.response.tool_call_count", safeMetadata.toolCallCount())
        .setAttribute("modelgate.response.refused", safeMetadata.refused())
        .setAttribute("modelgate.response.safety_flagged", safeMetadata.safetyFlagged())
        .setAttribute("modelgate.latency.provider_ms", duration == null ? 0L : duration.toMillis())
        .setAttribute(AttributeKey.stringKey("modelgate.component"), "provider")
        .startSpan();
    try {
      if (status >= 500) {
        span.setStatus(StatusCode.ERROR);
      } else {
        span.setStatus(StatusCode.OK);
      }
    } finally {
      span.end(endEpochNanos, TimeUnit.NANOSECONDS);
    }
  }

  public void recordRequestMetric(
      String endpointPath,
      String provider,
      String model,
      int status,
      Duration duration) {
    recordRequestMetric(endpointPath, provider, model, status, duration, ProviderResponseMetadata.empty());
  }

  public void recordRequestMetric(
      String endpointPath,
      String provider,
      String model,
      int status,
      Duration duration,
      ProviderResponseMetadata metadata) {
    if (!enabled || meterProvider == null) {
      return;
    }
    ProviderResponseMetadata safeMetadata = metadata == null ? ProviderResponseMetadata.empty() : metadata;
    Attributes attributes = commonAttributes(endpointPath, provider, model, status)
        .put("status_class", statusClass(status))
        .put("finish_reason", valueOrUnknown(safeMetadata.finishReason()))
        .put("refused", safeMetadata.refused())
        .put("safety_flagged", safeMetadata.safetyFlagged())
        .build();
    requestCounter.add(1, attributes);
    requestDuration.record(duration == null ? 0 : duration.toNanos() / 1_000_000.0d, attributes);
  }

  public void recordRequestLog(
      String traceId,
      String endpointPath,
      String method,
      String provider,
      String model,
      int status,
      Duration duration,
      String cacheStatus) {
    recordRequestLog(traceId, endpointPath, method, provider, model, status, duration, cacheStatus, ProviderResponseMetadata.empty());
  }

  public void recordRequestLog(
      String traceId,
      String endpointPath,
      String method,
      String provider,
      String model,
      int status,
      Duration duration,
      String cacheStatus,
      ProviderResponseMetadata metadata) {
    if (!enabled || loggerProvider == null) {
      return;
    }
    ProviderResponseMetadata safeMetadata = metadata == null ? ProviderResponseMetadata.empty() : metadata;
    logger.logRecordBuilder()
        .setTimestamp(System.currentTimeMillis() * 1_000_000L, TimeUnit.NANOSECONDS)
        .setObservedTimestamp(System.currentTimeMillis() * 1_000_000L, TimeUnit.NANOSECONDS)
        .setSeverity(status >= 500 ? Severity.ERROR : Severity.INFO)
        .setSeverityText(status >= 500 ? "ERROR" : "INFO")
        .setBody("modelgate.gateway.request")
        .setAttribute("modelgate.trace_id", valueOrUnknown(traceId))
        .setAttribute("modelgate.endpoint", valueOrUnknown(endpointPath))
        .setAttribute("modelgate.method", valueOrUnknown(method))
        .setAttribute("gen_ai.provider.name", valueOrUnknown(provider))
        .setAttribute("gen_ai.request.model", valueOrUnknown(model))
        .setAttribute("http.response.status_code", status)
        .setAttribute("modelgate.duration_ms", duration == null ? 0L : duration.toMillis())
        .setAttribute("modelgate.cache.status", valueOrUnknown(cacheStatus))
        .setAttribute("gen_ai.usage.input_tokens", safeMetadata.promptTokens())
        .setAttribute("gen_ai.usage.output_tokens", safeMetadata.completionTokens())
        .setAttribute("gen_ai.usage.total_tokens", safeMetadata.totalTokens())
        .setAttribute("gen_ai.response.finish_reasons", valueOrUnknown(safeMetadata.finishReason()))
        .setAttribute("modelgate.response.tool_call_count", safeMetadata.toolCallCount())
        .setAttribute("modelgate.response.refused", safeMetadata.refused())
        .setAttribute("modelgate.response.safety_flagged", safeMetadata.safetyFlagged())
        .emit();
  }

  public void flush() {
    if (tracerProvider != null) {
      tracerProvider.forceFlush().join(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    if (meterProvider != null) {
      meterProvider.forceFlush().join(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    if (loggerProvider != null) {
      loggerProvider.forceFlush().join(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public void close() {
    if (tracerProvider != null) {
      tracerProvider.shutdown().join(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    if (meterProvider != null) {
      meterProvider.shutdown().join(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    if (loggerProvider != null) {
      loggerProvider.shutdown().join(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  private static SpanExporter spanExporter(OpenTelemetrySettings settings, ExportFailureRecorder exportFailureRecorder) {
    SpanExporter exporter;
    if ("grpc".equals(settings.traceProtocol())) {
      var builder = OtlpGrpcSpanExporter.builder()
          .setEndpoint(settings.traceEndpoint().toString())
          .setTimeout(settings.traceTimeout())
          .setCompression(settings.traceCompression())
          .setMemoryMode(exporterMemoryMode(settings));
      applyRetryPolicy(builder::setRetryPolicy, settings);
      applyTls(builder::setTrustedCertificates, builder::setClientTls,
          settings.traceTrustedCertificatePath(), settings.traceClientKeyPath(), settings.traceClientCertificatePath());
      settings.traceHeaders().forEach(builder::addHeader);
      exporter = builder.build();
      return new ObservedSpanExporter("traces", exporter, exportFailureRecorder);
    }
    var builder = OtlpHttpSpanExporter.builder()
        .setEndpoint(settings.traceEndpoint().toString())
        .setTimeout(settings.traceTimeout())
        .setCompression(settings.traceCompression())
        .setMemoryMode(exporterMemoryMode(settings));
    applyRetryPolicy(builder::setRetryPolicy, settings);
    applyTls(builder::setTrustedCertificates, builder::setClientTls,
        settings.traceTrustedCertificatePath(), settings.traceClientKeyPath(), settings.traceClientCertificatePath());
    settings.traceHeaders().forEach(builder::addHeader);
    exporter = builder.build();
    return new ObservedSpanExporter("traces", exporter, exportFailureRecorder);
  }

  private interface SpanConfigurer {
    void configure(Span span);
  }

  private long recordChildSpan(
      String name,
      String component,
      long startEpochNanos,
      Duration duration,
      int status,
      SpanConfigurer configurer) {
    Duration safeDuration = safeDuration(duration);
    long endEpochNanos = startEpochNanos + safeDuration.toNanos();
    SpanKind kind = "provider".equals(component) ? SpanKind.CLIENT : SpanKind.INTERNAL;
    Span span = tracer.spanBuilder(name)
        .setSpanKind(kind)
        .setStartTimestamp(startEpochNanos, TimeUnit.NANOSECONDS)
        .setAttribute("modelgate.component", component)
        .setAttribute("http.response.status_code", status)
        .startSpan();
    try {
      if (configurer != null) {
        configurer.configure(span);
      }
      span.setStatus(status >= 500 ? StatusCode.ERROR : StatusCode.OK);
    } finally {
      span.end(endEpochNanos, TimeUnit.NANOSECONDS);
    }
    return endEpochNanos;
  }

  private static Sampler traceSampler(OpenTelemetrySettings settings) {
    return switch (settings.traceSampler()) {
      case "always_on" -> Sampler.alwaysOn();
      case "always_off" -> Sampler.alwaysOff();
      case "traceidratio" -> Sampler.traceIdRatioBased(traceSamplerRatio(settings.traceSamplerArg()));
      case "parentbased_always_off" -> Sampler.parentBased(Sampler.alwaysOff());
      case "parentbased_traceidratio" -> Sampler.parentBased(Sampler.traceIdRatioBased(traceSamplerRatio(settings.traceSamplerArg())));
      default -> Sampler.parentBased(Sampler.alwaysOn());
    };
  }

  private static double traceSamplerRatio(String value) {
    if (value == null || value.isBlank()) {
      return 1.0d;
    }
    try {
      double ratio = Double.parseDouble(value.trim());
      if (Double.isNaN(ratio) || ratio < 0.0d || ratio > 1.0d) {
        return 1.0d;
      }
      return ratio;
    } catch (NumberFormatException exception) {
      return 1.0d;
    }
  }

  private static ContextPropagators configuredPropagators(OpenTelemetrySettings settings) {
    List<TextMapPropagator> propagators = new ArrayList<>();
    for (String propagator : settings.propagators()) {
      if ("tracecontext".equals(propagator)) {
        propagators.add(W3CTraceContextPropagator.getInstance());
      } else if ("baggage".equals(propagator)) {
        propagators.add(W3CBaggagePropagator.getInstance());
      } else if ("b3".equals(propagator)) {
        propagators.add(B3Propagator.injectingSingleHeader());
      } else if ("b3multi".equals(propagator)) {
        propagators.add(B3Propagator.injectingMultiHeaders());
      } else if ("jaeger".equals(propagator)) {
        propagators.add(JaegerPropagator.getInstance());
      } else if ("ottrace".equals(propagator)) {
        propagators.add(OtTracePropagator.getInstance());
      } else if ("xray".equals(propagator)) {
        propagators.add(AwsXrayPropagator.getInstance());
      } else if ("xray-lambda".equals(propagator)) {
        propagators.add(AwsXrayLambdaPropagator.getInstance());
      }
    }
    if (propagators.isEmpty()) {
      return ContextPropagators.noop();
    }
    return ContextPropagators.create(TextMapPropagator.composite(propagators));
  }

  private static MetricExporter metricExporter(OpenTelemetrySettings settings, ExportFailureRecorder exportFailureRecorder) {
    MetricExporter exporter;
    if ("grpc".equals(settings.metricsProtocol())) {
      var builder = OtlpGrpcMetricExporter.builder()
          .setEndpoint(settings.metricsEndpoint().toString())
          .setTimeout(settings.metricsTimeout())
          .setCompression(settings.metricsCompression())
          .setMemoryMode(exporterMemoryMode(settings))
          .setAggregationTemporalitySelector(metricTemporalitySelector(settings))
          .setDefaultAggregationSelector(defaultAggregationSelector(settings));
      applyRetryPolicy(builder::setRetryPolicy, settings);
      applyTls(builder::setTrustedCertificates, builder::setClientTls,
          settings.metricsTrustedCertificatePath(), settings.metricsClientKeyPath(), settings.metricsClientCertificatePath());
      settings.metricsHeaders().forEach(builder::addHeader);
      exporter = builder.build();
      return new TimeoutMetricExporter(exporter, settings.metricExportTimeout(), exportFailureRecorder);
    }
    var builder = OtlpHttpMetricExporter.builder()
        .setEndpoint(settings.metricsEndpoint().toString())
        .setTimeout(settings.metricsTimeout())
        .setCompression(settings.metricsCompression())
        .setMemoryMode(exporterMemoryMode(settings))
        .setAggregationTemporalitySelector(metricTemporalitySelector(settings))
        .setDefaultAggregationSelector(defaultAggregationSelector(settings));
    applyRetryPolicy(builder::setRetryPolicy, settings);
    applyTls(builder::setTrustedCertificates, builder::setClientTls,
        settings.metricsTrustedCertificatePath(), settings.metricsClientKeyPath(), settings.metricsClientCertificatePath());
    settings.metricsHeaders().forEach(builder::addHeader);
    exporter = builder.build();
    return new TimeoutMetricExporter(exporter, settings.metricExportTimeout(), exportFailureRecorder);
  }

  private static SpanLimits spanLimits(OpenTelemetrySettings settings) {
    var builder = SpanLimits.builder()
        .setMaxNumberOfAttributes(settings.spanAttributeCountLimit())
        .setMaxNumberOfEvents(settings.spanEventCountLimit())
        .setMaxNumberOfLinks(settings.spanLinkCountLimit())
        .setMaxNumberOfAttributesPerEvent(settings.eventAttributeCountLimit())
        .setMaxNumberOfAttributesPerLink(settings.linkAttributeCountLimit());
    if (settings.spanAttributeValueLengthLimit() >= 0) {
      builder.setMaxAttributeValueLength(settings.spanAttributeValueLengthLimit());
    }
    return builder.build();
  }

  private static LogLimits logLimits(OpenTelemetrySettings settings) {
    var builder = LogLimits.builder()
        .setMaxNumberOfAttributes(settings.logRecordAttributeCountLimit());
    if (settings.logRecordAttributeValueLengthLimit() >= 0) {
      builder.setMaxAttributeValueLength(settings.logRecordAttributeValueLengthLimit());
    }
    return builder.build();
  }

  private static AggregationTemporalitySelector metricTemporalitySelector(OpenTelemetrySettings settings) {
    return switch (settings.metricsTemporalityPreference()) {
      case "delta" -> AggregationTemporalitySelector.deltaPreferred();
      case "lowmemory" -> AggregationTemporalitySelector.lowMemory();
      default -> AggregationTemporalitySelector.alwaysCumulative();
    };
  }

  private static DefaultAggregationSelector defaultAggregationSelector(OpenTelemetrySettings settings) {
    DefaultAggregationSelector selector = DefaultAggregationSelector.getDefault();
    if ("base2_exponential_bucket_histogram".equals(settings.metricsDefaultHistogramAggregation())) {
      return selector.with(InstrumentType.HISTOGRAM, Aggregation.base2ExponentialBucketHistogram());
    }
    return selector.with(InstrumentType.HISTOGRAM, Aggregation.explicitBucketHistogram());
  }

  private static ExemplarFilter metricExemplarFilter(OpenTelemetrySettings settings) {
    return switch (settings.metricsExemplarFilter()) {
      case "always_on" -> ExemplarFilter.alwaysOn();
      case "always_off" -> ExemplarFilter.alwaysOff();
      default -> ExemplarFilter.traceBased();
    };
  }

  private static MemoryMode exporterMemoryMode(OpenTelemetrySettings settings) {
    if ("immutable_data".equals(settings.exporterMemoryMode())) {
      return MemoryMode.IMMUTABLE_DATA;
    }
    return MemoryMode.REUSABLE_DATA;
  }

  private static LogRecordExporter logRecordExporter(OpenTelemetrySettings settings, ExportFailureRecorder exportFailureRecorder) {
    LogRecordExporter exporter;
    if ("grpc".equals(settings.logsProtocol())) {
      var builder = OtlpGrpcLogRecordExporter.builder()
          .setEndpoint(settings.logsEndpoint().toString())
          .setTimeout(settings.logsTimeout())
          .setCompression(settings.logsCompression())
          .setMemoryMode(exporterMemoryMode(settings));
      applyRetryPolicy(builder::setRetryPolicy, settings);
      applyTls(builder::setTrustedCertificates, builder::setClientTls,
          settings.logsTrustedCertificatePath(), settings.logsClientKeyPath(), settings.logsClientCertificatePath());
      settings.logsHeaders().forEach(builder::addHeader);
      exporter = builder.build();
      return new ObservedLogRecordExporter("logs", exporter, exportFailureRecorder);
    }
    var builder = OtlpHttpLogRecordExporter.builder()
        .setEndpoint(settings.logsEndpoint().toString())
        .setTimeout(settings.logsTimeout())
        .setCompression(settings.logsCompression())
        .setMemoryMode(exporterMemoryMode(settings));
    applyRetryPolicy(builder::setRetryPolicy, settings);
    applyTls(builder::setTrustedCertificates, builder::setClientTls,
        settings.logsTrustedCertificatePath(), settings.logsClientKeyPath(), settings.logsClientCertificatePath());
    settings.logsHeaders().forEach(builder::addHeader);
    exporter = builder.build();
    return new ObservedLogRecordExporter("logs", exporter, exportFailureRecorder);
  }

  private interface TrustedCertificateSetter {
    void setTrustedCertificates(byte[] certificate);
  }

  private interface ClientTlsSetter {
    void setClientTls(byte[] privateKeyPem, byte[] certificatePem);
  }

  private interface RetryPolicySetter {
    void setRetryPolicy(RetryPolicy retryPolicy);
  }

  private static void applyRetryPolicy(RetryPolicySetter retryPolicySetter, OpenTelemetrySettings settings) {
    if (!settings.otlpRetryDisabled()) {
      retryPolicySetter.setRetryPolicy(RetryPolicy.getDefault());
    }
  }

  private static void applyTls(
      TrustedCertificateSetter trustedCertificateSetter,
      ClientTlsSetter clientTlsSetter,
      Path trustedCertificatePath,
      Path clientKeyPath,
      Path clientCertificatePath) {
    if (trustedCertificatePath != null) {
      trustedCertificateSetter.setTrustedCertificates(readPem(trustedCertificatePath));
    }
    if (clientKeyPath != null && clientCertificatePath != null) {
      clientTlsSetter.setClientTls(readPem(clientKeyPath), readPem(clientCertificatePath));
    }
  }

  private static byte[] readPem(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Unable to read OpenTelemetry TLS file: " + path, exception);
    }
  }

  private static AttributesBuilder commonAttributes(
      String endpointPath,
      String provider,
      String model,
      int status) {
    return Attributes.builder()
        .put("gen_ai.operation.name", "provider_request")
        .put("gen_ai.provider.name", valueOrUnknown(provider))
        .put("gen_ai.request.model", valueOrUnknown(model))
        .put("http.route", valueOrUnknown(endpointPath))
        .put("http.response.status_code", status)
        .put("modelgate.component", "provider");
  }

  private static String statusClass(int status) {
    if (status >= 200 && status < 300) {
      return "2xx";
    }
    if (status >= 300 && status < 400) {
      return "3xx";
    }
    if (status >= 400 && status < 500) {
      return "4xx";
    }
    if (status >= 500) {
      return "5xx";
    }
    return "other";
  }

  private static String valueOrUnknown(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private static Duration safeDuration(Duration duration) {
    return duration == null || duration.isNegative() ? Duration.ZERO : duration;
  }

  private static long millis(Duration duration) {
    return safeDuration(duration).toMillis();
  }

  private static final class TimeoutMetricExporter implements MetricExporter {
    private final MetricExporter delegate;
    private final Duration timeout;
    private final ExportFailureRecorder exportFailureRecorder;

    private TimeoutMetricExporter(MetricExporter delegate, Duration timeout, ExportFailureRecorder exportFailureRecorder) {
      this.delegate = delegate;
      this.timeout = safeDuration(timeout);
      this.exportFailureRecorder = exportFailureRecorder == null ? new ExportFailureRecorder() : exportFailureRecorder;
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
      CompletableResultCode delegateResult = delegate.export(metrics);
      CompletableResultCode boundedResult = new CompletableResultCode();
      delegateResult.whenComplete(() -> {
        if (delegateResult.isSuccess()) {
          boundedResult.succeed();
        } else if (delegateResult.getFailureThrowable() != null) {
          exportFailureRecorder.record("metrics");
          boundedResult.failExceptionally(delegateResult.getFailureThrowable());
        } else {
          exportFailureRecorder.record("metrics");
          boundedResult.fail();
        }
      });
      Thread.ofVirtual().name("modelgate-otel-metric-export-timeout-", 0).start(() -> {
        try {
          Thread.sleep(timeout);
          if (!boundedResult.isDone()) {
            exportFailureRecorder.record("metrics");
            boundedResult.fail();
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          if (!boundedResult.isDone()) {
            exportFailureRecorder.record("metrics");
            boundedResult.failExceptionally(exception);
          }
        }
      });
      return boundedResult;
    }

    @Override
    public CompletableResultCode flush() {
      return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
      return delegate.shutdown();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
      return delegate.getAggregationTemporality(instrumentType);
    }

    @Override
    public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
      return delegate.getDefaultAggregation(instrumentType);
    }

    @Override
    public MemoryMode getMemoryMode() {
      return delegate.getMemoryMode();
    }
  }

  private static final class ObservedSpanExporter implements SpanExporter {
    private final String signal;
    private final SpanExporter delegate;
    private final ExportFailureRecorder exportFailureRecorder;

    private ObservedSpanExporter(String signal, SpanExporter delegate, ExportFailureRecorder exportFailureRecorder) {
      this.signal = signal;
      this.delegate = delegate;
      this.exportFailureRecorder = exportFailureRecorder == null ? new ExportFailureRecorder() : exportFailureRecorder;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      return observe(delegate.export(spans), signal, exportFailureRecorder);
    }

    @Override
    public CompletableResultCode flush() {
      return observe(delegate.flush(), signal, exportFailureRecorder);
    }

    @Override
    public CompletableResultCode shutdown() {
      return observe(delegate.shutdown(), signal, exportFailureRecorder);
    }
  }

  private static final class ObservedLogRecordExporter implements LogRecordExporter {
    private final String signal;
    private final LogRecordExporter delegate;
    private final ExportFailureRecorder exportFailureRecorder;

    private ObservedLogRecordExporter(String signal, LogRecordExporter delegate, ExportFailureRecorder exportFailureRecorder) {
      this.signal = signal;
      this.delegate = delegate;
      this.exportFailureRecorder = exportFailureRecorder == null ? new ExportFailureRecorder() : exportFailureRecorder;
    }

    @Override
    public CompletableResultCode export(Collection<LogRecordData> logs) {
      return observe(delegate.export(logs), signal, exportFailureRecorder);
    }

    @Override
    public CompletableResultCode flush() {
      return observe(delegate.flush(), signal, exportFailureRecorder);
    }

    @Override
    public CompletableResultCode shutdown() {
      return observe(delegate.shutdown(), signal, exportFailureRecorder);
    }
  }

  private static CompletableResultCode observe(
      CompletableResultCode result,
      String signal,
      ExportFailureRecorder exportFailureRecorder) {
    result.whenComplete(() -> {
      if (!result.isSuccess()) {
        exportFailureRecorder.record(signal);
      }
    });
    return result;
  }

  private static final class ExportFailureRecorder {
    private final Map<String, AtomicLong> failures = new ConcurrentHashMap<>();
    private volatile LongCounter counter;

    void attach(Meter meter) {
      if (meter != null) {
        this.counter = meter.counterBuilder("modelgate.otel.export.failures")
            .setDescription("Failed OTLP exports observed by ModelGate")
            .build();
      }
    }

    void record(String signal) {
      String normalizedSignal = normalizedSignal(signal);
      failures.computeIfAbsent(normalizedSignal, ignored -> new AtomicLong()).incrementAndGet();
      LongCounter currentCounter = counter;
      if (currentCounter != null) {
        currentCounter.add(1, Attributes.builder().put("signal", normalizedSignal).build());
      }
    }

    long count(String signal) {
      AtomicLong count = failures.get(normalizedSignal(signal));
      return count == null ? 0 : count.get();
    }

    private static String normalizedSignal(String signal) {
      return signal == null || signal.isBlank() ? "unknown" : signal.trim().toLowerCase(Locale.ROOT);
    }
  }
}
