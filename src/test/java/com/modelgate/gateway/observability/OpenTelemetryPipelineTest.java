package com.modelgate.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class OpenTelemetryPipelineTest {
  @Test
  void settingsBuildTraceEndpointFromBaseOtlpEndpointAndMergeHeaders() {
    OpenTelemetrySettings settings = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector.example.com:4318/otel",
        "OTEL_EXPORTER_OTLP_HEADERS", "authorization=Bearer%20abc,x-tenant=modelgate",
        "OTEL_EXPORTER_OTLP_TRACES_HEADERS", "x-trace-route=provider",
        "OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf",
        "OTEL_EXPORTER_OTLP_TIMEOUT", "2500",
        "OTEL_SERVICE_NAME", "modelgate-prod"));

    assertThat(settings.enabled()).isTrue();
    assertThat(settings.tracesEnabled()).isTrue();
    assertThat(settings.metricsEnabled()).isTrue();
    assertThat(settings.logsEnabled()).isTrue();
    assertThat(settings.traceEndpoint()).isEqualTo(URI.create("http://collector.example.com:4318/otel/v1/traces"));
    assertThat(settings.metricsEndpoint()).isEqualTo(URI.create("http://collector.example.com:4318/otel/v1/metrics"));
    assertThat(settings.logsEndpoint()).isEqualTo(URI.create("http://collector.example.com:4318/otel/v1/logs"));
    assertThat(settings.protocol()).isEqualTo("http/protobuf");
    assertThat(settings.serviceName()).isEqualTo("modelgate-prod");
    assertThat(settings.timeout()).isEqualTo(Duration.ofMillis(2500));
    assertThat(settings.headers()).containsEntry("authorization", "Bearer abc");
    assertThat(settings.headers()).containsEntry("x-tenant", "modelgate");
    assertThat(settings.headers()).containsEntry("x-trace-route", "provider");
  }

  @Test
  void settingsGiveSystemPropertiesPriorityOverEnvironmentVariables() {
    String property = "otel.exporter.otlp.endpoint";
    String previous = System.getProperty(property);
    try {
      System.setProperty(property, "http://system.example.com:4318/system");

      OpenTelemetrySettings settings = OpenTelemetrySettings.fromEnvironment(Map.of(
          "OTEL_EXPORTER_OTLP_ENDPOINT", "http://env.example.com:4318/env"));

      assertThat(settings.traceEndpoint()).isEqualTo(URI.create("http://system.example.com:4318/system/v1/traces"));
      assertThat(settings.metricsEndpoint()).isEqualTo(URI.create("http://system.example.com:4318/system/v1/metrics"));
      assertThat(settings.logsEndpoint()).isEqualTo(URI.create("http://system.example.com:4318/system/v1/logs"));
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  @Test
  void settingsHonorSignalSpecificExporterDisablesAndEndpoints() {
    OpenTelemetrySettings settings = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector.example.com:4318",
        "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", "http://metrics.example.com/custom-metrics",
        "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT", "http://logs.example.com/custom-logs",
        "OTEL_TRACES_EXPORTER", "none",
        "OTEL_METRICS_EXPORTER", "otlp",
        "OTEL_LOGS_EXPORTER", "otlp"));

    assertThat(settings.enabled()).isTrue();
    assertThat(settings.tracesEnabled()).isFalse();
    assertThat(settings.metricsEnabled()).isTrue();
    assertThat(settings.logsEnabled()).isTrue();
    assertThat(settings.metricsEndpoint()).isEqualTo(URI.create("http://metrics.example.com/custom-metrics"));
    assertThat(settings.logsEndpoint()).isEqualTo(URI.create("http://logs.example.com/custom-logs"));
  }

  @Test
  void settingsHonorGlobalExporterNoneUnlessSignalExplicitlyEnablesOtlp() {
    OpenTelemetrySettings disabled = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_EXPORTER", "none",
        "OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector.example.com:4318"));
    OpenTelemetrySettings signalOverride = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_EXPORTER", "none",
        "OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector.example.com:4318",
        "OTEL_TRACES_EXPORTER", "otlp"));

    assertThat(disabled.enabled()).isFalse();
    assertThat(disabled.tracesEnabled()).isFalse();
    assertThat(disabled.metricsEnabled()).isFalse();
    assertThat(disabled.logsEnabled()).isFalse();
    assertThat(signalOverride.enabled()).isTrue();
    assertThat(signalOverride.tracesEnabled()).isTrue();
    assertThat(signalOverride.metricsEnabled()).isFalse();
    assertThat(signalOverride.logsEnabled()).isFalse();
  }

  @Test
  void settingsParseCommaSeparatedExporterLists() {
    OpenTelemetrySettings signalList = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_TRACES_EXPORTER", "console,otlp",
        "OTEL_METRICS_EXPORTER", "none",
        "OTEL_LOGS_EXPORTER", "logging,none",
        "OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector.example.com:4318"));
    OpenTelemetrySettings globalList = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_EXPORTER", "console,otlp",
        "OTEL_METRICS_EXPORTER", "none"));

    assertThat(signalList.enabled()).isTrue();
    assertThat(signalList.tracesEnabled()).isTrue();
    assertThat(signalList.metricsEnabled()).isFalse();
    assertThat(signalList.logsEnabled()).isFalse();
    assertThat(globalList.enabled()).isTrue();
    assertThat(globalList.tracesEnabled()).isTrue();
    assertThat(globalList.metricsEnabled()).isFalse();
    assertThat(globalList.logsEnabled()).isTrue();
  }

  @Test
  void settingsApplySignalSpecificProtocolsHeadersAndTimeouts() {
    OpenTelemetrySettings settings = OpenTelemetrySettings.fromEnvironment(Map.ofEntries(
        Map.entry("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector.example.com:4318/base"),
        Map.entry("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf"),
        Map.entry("OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "http/protobuf"),
        Map.entry("OTEL_EXPORTER_OTLP_METRICS_PROTOCOL", "grpc"),
        Map.entry("OTEL_EXPORTER_OTLP_LOGS_PROTOCOL", "http/protobuf"),
        Map.entry("OTEL_EXPORTER_OTLP_HEADERS", "authorization=Bearer%20global,x-shared=global"),
        Map.entry("OTEL_EXPORTER_OTLP_TRACES_HEADERS", "x-trace=true,x-shared=trace"),
        Map.entry("OTEL_EXPORTER_OTLP_METRICS_HEADERS", "x-metric=true,x-shared=metric"),
        Map.entry("OTEL_EXPORTER_OTLP_LOGS_HEADERS", "x-log=true,x-shared=log"),
        Map.entry("OTEL_EXPORTER_OTLP_TIMEOUT", "1000"),
        Map.entry("OTEL_EXPORTER_OTLP_TRACES_TIMEOUT", "1500"),
        Map.entry("OTEL_EXPORTER_OTLP_METRICS_TIMEOUT", "2500"),
        Map.entry("OTEL_EXPORTER_OTLP_LOGS_TIMEOUT", "3500"),
        Map.entry("OTEL_TRACES_EXPORTER", "otlp"),
        Map.entry("OTEL_METRICS_EXPORTER", "otlp"),
        Map.entry("OTEL_LOGS_EXPORTER", "otlp")));

    assertThat(settings.traceProtocol()).isEqualTo("http/protobuf");
    assertThat(settings.metricsProtocol()).isEqualTo("grpc");
    assertThat(settings.logsProtocol()).isEqualTo("http/protobuf");
    assertThat(settings.traceEndpoint()).isEqualTo(URI.create("http://collector.example.com:4318/base/v1/traces"));
    assertThat(settings.metricsEndpoint()).isEqualTo(URI.create("http://collector.example.com:4318/base"));
    assertThat(settings.logsEndpoint()).isEqualTo(URI.create("http://collector.example.com:4318/base/v1/logs"));
    assertThat(settings.traceHeaders())
        .containsEntry("authorization", "Bearer global")
        .containsEntry("x-shared", "trace")
        .containsEntry("x-trace", "true")
        .doesNotContainKey("x-metric");
    assertThat(settings.metricsHeaders())
        .containsEntry("authorization", "Bearer global")
        .containsEntry("x-shared", "metric")
        .containsEntry("x-metric", "true")
        .doesNotContainKey("x-trace");
    assertThat(settings.logsHeaders())
        .containsEntry("authorization", "Bearer global")
        .containsEntry("x-shared", "log")
        .containsEntry("x-log", "true")
        .doesNotContainKey("x-trace");
    assertThat(settings.traceTimeout()).isEqualTo(Duration.ofMillis(1500));
    assertThat(settings.metricsTimeout()).isEqualTo(Duration.ofMillis(2500));
    assertThat(settings.logsTimeout()).isEqualTo(Duration.ofMillis(3500));
  }

  @Test
  void settingsParseResourceAttributesAndServiceNameOverridesServiceNameAttribute() {
    OpenTelemetrySettings settings = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_EXPORTER", "otlp",
        "OTEL_SERVICE_NAME", "modelgate-api",
        "OTEL_RESOURCE_ATTRIBUTES", "service.namespace=edge,deployment.environment=prod,service.name=ignored"));

    assertThat(settings.serviceName()).isEqualTo("modelgate-api");
    assertThat(settings.resourceAttributes())
        .containsEntry("service.name", "modelgate-api")
        .containsEntry("service.namespace", "edge")
        .containsEntry("deployment.environment", "prod");
  }

  @Test
  void settingsFilterDisabledResourceAttributeKeys() {
    OpenTelemetrySettings settings = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_EXPORTER", "otlp",
        "OTEL_SERVICE_NAME", "modelgate-api",
        "OTEL_RESOURCE_ATTRIBUTES", "service.namespace=edge,deployment.environment=prod,service.instance.id=abc",
        "OTEL_RESOURCE_DISABLED_KEYS", "deployment.environment,service.instance.id"));

    assertThat(settings.resourceAttributes())
        .containsEntry("service.name", "modelgate-api")
        .containsEntry("service.namespace", "edge")
        .doesNotContainKey("deployment.environment")
        .doesNotContainKey("service.instance.id");
  }

  @Test
  void settingsParseConfiguredPropagators() {
    OpenTelemetrySettings defaults = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_EXPORTER", "otlp"));
    OpenTelemetrySettings configured = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_EXPORTER", "otlp",
        "OTEL_PROPAGATORS", "tracecontext,baggage,b3,b3multi,jaeger,ottrace,xray,xray-lambda,none,unknown"));

    assertThat(defaults.propagators()).containsExactly("tracecontext", "baggage");
    assertThat(configured.propagators())
        .containsExactly("tracecontext", "baggage", "b3", "b3multi", "jaeger", "ottrace", "xray", "xray-lambda", "none");
  }

  @Test
  void settingsApplySignalSpecificCompression() {
    OpenTelemetrySettings settings = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_EXPORTER", "otlp",
        "OTEL_EXPORTER_OTLP_COMPRESSION", "gzip",
        "OTEL_EXPORTER_OTLP_METRICS_COMPRESSION", "none"));

    assertThat(settings.traceCompression()).isEqualTo("gzip");
    assertThat(settings.metricsCompression()).isEqualTo("none");
    assertThat(settings.logsCompression()).isEqualTo("gzip");
  }

  @Test
  void settingsApplyOtlpTlsAndMtlsFilesWithSignalOverrides() throws Exception {
    Path globalCa = Files.createTempFile("modelgate-otel-ca", ".pem");
    Path traceCa = Files.createTempFile("modelgate-otel-trace-ca", ".pem");
    Path clientKey = Files.createTempFile("modelgate-otel-client", ".key");
    Path clientCert = Files.createTempFile("modelgate-otel-client", ".crt");

    OpenTelemetrySettings settings = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_EXPORTER", "otlp",
        "OTEL_EXPORTER_OTLP_CERTIFICATE", globalCa.toString(),
        "OTEL_EXPORTER_OTLP_TRACES_CERTIFICATE", traceCa.toString(),
        "OTEL_EXPORTER_OTLP_CLIENT_KEY", clientKey.toString(),
        "OTEL_EXPORTER_OTLP_CLIENT_CERTIFICATE", clientCert.toString()));

    assertThat(settings.traceTrustedCertificatePath()).isEqualTo(traceCa);
    assertThat(settings.metricsTrustedCertificatePath()).isEqualTo(globalCa);
    assertThat(settings.logsTrustedCertificatePath()).isEqualTo(globalCa);
    assertThat(settings.traceClientKeyPath()).isEqualTo(clientKey);
    assertThat(settings.traceClientCertificatePath()).isEqualTo(clientCert);
    assertThat(settings.metricsClientKeyPath()).isEqualTo(clientKey);
    assertThat(settings.metricsClientCertificatePath()).isEqualTo(clientCert);
    assertThat(settings.logsClientKeyPath()).isEqualTo(clientKey);
    assertThat(settings.logsClientCertificatePath()).isEqualTo(clientCert);
  }

  @Test
  void settingsApplyMetricTemporalityAndHistogramAggregationPreferences() {
    OpenTelemetrySettings settings = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_METRICS_EXPORTER", "otlp",
        "OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE", "delta",
        "OTEL_EXPORTER_OTLP_METRICS_DEFAULT_HISTOGRAM_AGGREGATION", "base2_exponential_bucket_histogram",
        "OTEL_METRICS_EXEMPLAR_FILTER", "always_off",
        "OTEL_JAVA_METRICS_CARDINALITY_LIMIT", "5000",
        "OTEL_JAVA_EXPORTER_MEMORY_MODE", "immutable_data",
        "OTEL_JAVA_EXPORTER_OTLP_RETRY_DISABLED", "true",
        "OTEL_METRIC_EXPORT_INTERVAL", "30000",
        "OTEL_METRIC_EXPORT_TIMEOUT", "45000"));

    assertThat(settings.metricsTemporalityPreference()).isEqualTo("delta");
    assertThat(settings.metricsDefaultHistogramAggregation()).isEqualTo("base2_exponential_bucket_histogram");
    assertThat(settings.metricsExemplarFilter()).isEqualTo("always_off");
    assertThat(settings.metricCardinalityLimit()).isEqualTo(5000);
    assertThat(settings.exporterMemoryMode()).isEqualTo("immutable_data");
    assertThat(settings.otlpRetryDisabled()).isTrue();
    assertThat(settings.metricExportInterval()).isEqualTo(Duration.ofMillis(30000));
    assertThat(settings.metricExportTimeout()).isEqualTo(Duration.ofMillis(45000));
  }

  @Test
  void settingsApplyTraceAndLogLimits() {
    OpenTelemetrySettings settings = OpenTelemetrySettings.fromEnvironment(Map.ofEntries(
        Map.entry("OTEL_EXPORTER", "otlp"),
        Map.entry("OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT", "128"),
        Map.entry("OTEL_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT", "64"),
        Map.entry("OTEL_LOGRECORD_ATTRIBUTE_VALUE_LENGTH_LIMIT", "96"),
        Map.entry("OTEL_ATTRIBUTE_COUNT_LIMIT", "16"),
        Map.entry("OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT", "12"),
        Map.entry("OTEL_SPAN_EVENT_COUNT_LIMIT", "8"),
        Map.entry("OTEL_SPAN_LINK_COUNT_LIMIT", "4"),
        Map.entry("OTEL_EVENT_ATTRIBUTE_COUNT_LIMIT", "6"),
        Map.entry("OTEL_LINK_ATTRIBUTE_COUNT_LIMIT", "3"),
        Map.entry("OTEL_LOGRECORD_ATTRIBUTE_COUNT_LIMIT", "10")));

    assertThat(settings.attributeValueLengthLimit()).isEqualTo(128);
    assertThat(settings.spanAttributeValueLengthLimit()).isEqualTo(64);
    assertThat(settings.logRecordAttributeValueLengthLimit()).isEqualTo(96);
    assertThat(settings.attributeCountLimit()).isEqualTo(16);
    assertThat(settings.spanAttributeCountLimit()).isEqualTo(12);
    assertThat(settings.spanEventCountLimit()).isEqualTo(8);
    assertThat(settings.spanLinkCountLimit()).isEqualTo(4);
    assertThat(settings.eventAttributeCountLimit()).isEqualTo(6);
    assertThat(settings.linkAttributeCountLimit()).isEqualTo(3);
    assertThat(settings.logRecordAttributeCountLimit()).isEqualTo(10);
  }

  @Test
  void settingsApplyBatchSpanAndBatchLogProcessorSettings() {
    OpenTelemetrySettings settings = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_EXPORTER", "otlp",
        "OTEL_BSP_SCHEDULE_DELAY", "250",
        "OTEL_BSP_EXPORT_TIMEOUT", "1500",
        "OTEL_BSP_MAX_QUEUE_SIZE", "2048",
        "OTEL_BSP_MAX_EXPORT_BATCH_SIZE", "256",
        "OTEL_BLRP_SCHEDULE_DELAY", "350",
        "OTEL_BLRP_EXPORT_TIMEOUT", "2500",
        "OTEL_BLRP_MAX_QUEUE_SIZE", "4096",
        "OTEL_BLRP_MAX_EXPORT_BATCH_SIZE", "512"));

    assertThat(settings.spanScheduleDelay()).isEqualTo(Duration.ofMillis(250));
    assertThat(settings.spanExportTimeout()).isEqualTo(Duration.ofMillis(1500));
    assertThat(settings.spanMaxQueueSize()).isEqualTo(2048);
    assertThat(settings.spanMaxExportBatchSize()).isEqualTo(256);
    assertThat(settings.logScheduleDelay()).isEqualTo(Duration.ofMillis(350));
    assertThat(settings.logExportTimeout()).isEqualTo(Duration.ofMillis(2500));
    assertThat(settings.logMaxQueueSize()).isEqualTo(4096);
    assertThat(settings.logMaxExportBatchSize()).isEqualTo(512);
  }

  @Test
  void settingsUseJavaSdkBatchProcessorExportTimeoutDefaults() {
    OpenTelemetrySettings settings = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_EXPORTER", "otlp"));

    assertThat(settings.traceTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(settings.logsTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(settings.spanExportTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(settings.logExportTimeout()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void settingsApplyTraceSamplerSettings() {
    OpenTelemetrySettings settings = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_TRACES_EXPORTER", "otlp",
        "OTEL_TRACES_SAMPLER", "traceidratio",
        "OTEL_TRACES_SAMPLER_ARG", "0.25"));

    assertThat(settings.traceSampler()).isEqualTo("traceidratio");
    assertThat(settings.traceSamplerArg()).isEqualTo("0.25");
  }

  @Test
  void settingsUseTraceEndpointAsIsAndRespectSdkDisabled() {
    OpenTelemetrySettings settings = OpenTelemetrySettings.fromEnvironment(Map.of(
        "OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector.example.com:4318",
        "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://trace.example.com/custom-traces",
        "OTEL_SDK_DISABLED", "true"));

    assertThat(settings.enabled()).isFalse();
    assertThat(settings.traceEndpoint()).isEqualTo(URI.create("http://trace.example.com/custom-traces"));
  }

  @Test
  void pipelinePostsProviderSpanToOtlpHttpCollector() throws Exception {
    CapturedOtlpRequest captured = new CapturedOtlpRequest();
    try (RunningCollector collector = RunningCollector.start(captured);
        OpenTelemetryPipeline pipeline = OpenTelemetryPipeline.create(new OpenTelemetrySettings(
            true,
            URI.create(collector.url("/v1/traces")),
            "http/protobuf",
            Map.of("x-test-tenant", "modelgate"),
            "modelgate-test",
            Duration.ofSeconds(2)))) {
      pipeline.recordProviderSpan(
          "trace-1",
          "span-1",
          "openai",
          "/v1/chat/completions",
          "gpt-test",
          200,
          Duration.ofMillis(17));
      pipeline.flush();

      assertThat(captured.await()).isTrue();
      assertThat(captured.path()).isEqualTo("/v1/traces");
      assertThat(captured.header("x-test-tenant")).isEqualTo("modelgate");
      assertThat(captured.header("content-type")).contains("application/x-protobuf");
      assertThat(captured.body()).isNotEmpty();
      assertThat(new String(captured.body(), StandardCharsets.ISO_8859_1)).contains("modelgate-test");
    }
  }

  @Test
  void pipelinePostsTracesMetricsAndLogsToOtlpHttpCollector() throws Exception {
    CapturedOtlpRequests captured = new CapturedOtlpRequests(3);
    ProviderResponseMetadata metadata = new ProviderResponseMetadata(
        "openai",
        "gpt-test",
        9,
        4,
        13,
        2,
        3,
        0,
        0,
        "tool_calls",
        1,
        true,
        "cannot comply",
        1,
        true);
    try (RunningCollector collector = RunningCollector.start(captured);
        OpenTelemetryPipeline pipeline = OpenTelemetryPipeline.create(OpenTelemetrySettings.fromEnvironment(Map.of(
            "OTEL_EXPORTER_OTLP_ENDPOINT", collector.url("/otel"),
            "OTEL_EXPORTER_OTLP_HEADERS", "x-test-tenant=modelgate",
            "OTEL_EXPORTER_OTLP_COMPRESSION", "gzip",
            "OTEL_RESOURCE_ATTRIBUTES", "service.namespace=edge,deployment.environment=prod",
            "OTEL_SERVICE_NAME", "modelgate-test")))) {
      pipeline.recordProviderSpan(
          "trace-1",
          "span-1",
          "openai",
          "/v1/chat/completions",
          "gpt-test",
          200,
          Duration.ofMillis(17),
          metadata);
      pipeline.recordRequestMetric(
          "/v1/chat/completions",
          "openai",
          "gpt-test",
          200,
          Duration.ofMillis(17),
          metadata);
      pipeline.recordRequestLog(
          "trace-1",
          "/v1/chat/completions",
          "POST",
          "openai",
          "gpt-test",
          200,
          Duration.ofMillis(17),
          "MISS",
          metadata);
      pipeline.flush();

      assertThat(captured.await()).isTrue();
      assertThat(captured.paths()).contains("/otel/v1/traces", "/otel/v1/metrics", "/otel/v1/logs");
      assertThat(captured.headerValues("x-test-tenant")).containsOnly("modelgate");
      assertThat(captured.headerValues("content-encoding")).containsOnly("gzip");
      assertThat(captured.headerValues("content-type")).allSatisfy(value ->
          assertThat(value).contains("application/x-protobuf"));
      assertThat(captured.bodiesAsIso88591()).anySatisfy(body -> assertThat(body).contains("modelgate-test"));
      assertThat(captured.bodiesAsIso88591()).anySatisfy(body -> assertThat(body)
          .contains("service.namespace")
          .contains("edge")
          .contains("deployment.environment")
          .contains("prod"));
      assertThat(captured.bodiesAsIso88591()).anySatisfy(body -> assertThat(body).contains("modelgate.gateway.request"));
      assertThat(captured.bodiesAsIso88591()).anySatisfy(body -> assertThat(body).contains("provider_request openai"));
      assertThat(captured.bodiesAsIso88591()).anySatisfy(body -> assertThat(body)
          .contains("gen_ai.usage.input_tokens")
          .contains("gen_ai.usage.output_tokens")
          .contains("gen_ai.usage.total_tokens")
          .contains("gen_ai.response.finish_reasons")
          .contains("modelgate.response.tool_call_count")
          .contains("modelgate.response.refused")
          .contains("modelgate.response.safety_flagged")
          .contains("modelgate.latency.provider_ms")
          .contains("tool_calls"));
    }
  }

  @Test
  void pipelinePostsGatewayTraceHierarchyToOtlpHttpCollector() throws Exception {
    CapturedOtlpRequest captured = new CapturedOtlpRequest();
    ProviderResponseMetadata metadata = new ProviderResponseMetadata(
        "openai",
        "gpt-test",
        9,
        4,
        13,
        2,
        3,
        0,
        0,
        "stop",
        0,
        false,
        "",
        0,
        false);
    try (RunningCollector collector = RunningCollector.start(captured);
        OpenTelemetryPipeline pipeline = OpenTelemetryPipeline.create(OpenTelemetrySettings.fromEnvironment(Map.of(
            "OTEL_TRACES_EXPORTER", "otlp",
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", collector.url("/v1/traces"),
            "OTEL_SERVICE_NAME", "modelgate-test")))) {
      pipeline.recordGatewayTrace(
          "trace-1",
          "/v1/chat/completions",
          "POST",
          "openai",
          "gpt-test",
          200,
          Duration.ofMillis(40),
          Duration.ofMillis(5),
          Duration.ofMillis(20),
          Duration.ofMillis(7),
          metadata);
      pipeline.flush();

      assertThat(captured.await()).isTrue();
      String body = new String(captured.body(), StandardCharsets.ISO_8859_1);
      assertThat(body)
          .contains("gateway_request POST /v1/chat/completions")
          .contains("guardrail_input")
          .contains("provider_request openai")
          .contains("guardrail_output")
          .contains("eval_guardrails")
          .contains("modelgate.parent_component")
          .contains("modelgate.component")
          .contains("modelgate.eval.input_guardrails_ms")
          .contains("modelgate.eval.output_guardrails_ms");
    }
  }

  @Test
  void pipelinePostsSignalSpecificHeadersToOtlpHttpCollectors() throws Exception {
    CapturedOtlpRequests captured = new CapturedOtlpRequests(3);
    try (RunningCollector collector = RunningCollector.start(captured);
        OpenTelemetryPipeline pipeline = OpenTelemetryPipeline.create(OpenTelemetrySettings.fromEnvironment(Map.of(
            "OTEL_EXPORTER_OTLP_ENDPOINT", collector.url("/otel"),
            "OTEL_EXPORTER_OTLP_HEADERS", "x-global=modelgate",
            "OTEL_EXPORTER_OTLP_TRACES_HEADERS", "x-signal=trace",
            "OTEL_EXPORTER_OTLP_METRICS_HEADERS", "x-signal=metric",
            "OTEL_EXPORTER_OTLP_LOGS_HEADERS", "x-signal=log",
            "OTEL_SERVICE_NAME", "modelgate-test")))) {
      pipeline.recordProviderSpan(
          "trace-1",
          "span-1",
          "openai",
          "/v1/chat/completions",
          "gpt-test",
          200,
          Duration.ofMillis(17));
      pipeline.recordRequestMetric(
          "/v1/chat/completions",
          "openai",
          "gpt-test",
          200,
          Duration.ofMillis(17));
      pipeline.recordRequestLog(
          "trace-1",
          "/v1/chat/completions",
          "POST",
          "openai",
          "gpt-test",
          200,
          Duration.ofMillis(17),
          "MISS");
      pipeline.flush();

      assertThat(captured.await()).isTrue();
      assertThat(captured.headerValueByPath("/otel/v1/traces", "x-global")).isEqualTo("modelgate");
      assertThat(captured.headerValueByPath("/otel/v1/metrics", "x-global")).isEqualTo("modelgate");
      assertThat(captured.headerValueByPath("/otel/v1/logs", "x-global")).isEqualTo("modelgate");
      assertThat(captured.headerValueByPath("/otel/v1/traces", "x-signal")).isEqualTo("trace");
      assertThat(captured.headerValueByPath("/otel/v1/metrics", "x-signal")).isEqualTo("metric");
      assertThat(captured.headerValueByPath("/otel/v1/logs", "x-signal")).isEqualTo("log");
    }
  }

  @Test
  void pipelineCountsOtlpExportFailuresBySignal() throws Exception {
    CapturedOtlpRequests captured = new CapturedOtlpRequests(3);
    try (RunningCollector collector = RunningCollector.start(captured, 500);
        OpenTelemetryPipeline pipeline = OpenTelemetryPipeline.create(OpenTelemetrySettings.fromEnvironment(Map.of(
            "OTEL_EXPORTER_OTLP_ENDPOINT", collector.url("/otel"),
            "OTEL_SERVICE_NAME", "modelgate-test")))) {
      pipeline.recordProviderSpan(
          "trace-1",
          "span-1",
          "openai",
          "/v1/chat/completions",
          "gpt-test",
          200,
          Duration.ofMillis(17));
      pipeline.recordRequestMetric(
          "/v1/chat/completions",
          "openai",
          "gpt-test",
          200,
          Duration.ofMillis(17));
      pipeline.recordRequestLog(
          "trace-1",
          "/v1/chat/completions",
          "POST",
          "openai",
          "gpt-test",
          200,
          Duration.ofMillis(17),
          "MISS");
      pipeline.flush();

      assertThat(captured.await()).isTrue();
      assertThat(pipeline.exportFailureCount("traces")).isGreaterThanOrEqualTo(1);
      assertThat(pipeline.exportFailureCount("metrics")).isGreaterThanOrEqualTo(1);
      assertThat(pipeline.exportFailureCount("logs")).isGreaterThanOrEqualTo(1);
    }
  }

  @Test
  void pipelineExportsOtlpFailureCounterAfterExporterRecovers() throws Exception {
    CapturedOtlpRequests captured = new CapturedOtlpRequests(4);
    try (RunningCollector collector = RunningCollector.start(captured, List.of(500, 500, 500, 200, 200, 200));
        OpenTelemetryPipeline pipeline = OpenTelemetryPipeline.create(OpenTelemetrySettings.fromEnvironment(Map.of(
            "OTEL_EXPORTER_OTLP_ENDPOINT", collector.url("/otel"),
            "OTEL_SERVICE_NAME", "modelgate-test",
            "OTEL_METRIC_EXPORT_INTERVAL", "600000")))) {
      pipeline.recordProviderSpan(
          "trace-1",
          "span-1",
          "openai",
          "/v1/chat/completions",
          "gpt-test",
          200,
          Duration.ofMillis(17));
      pipeline.recordRequestMetric(
          "/v1/chat/completions",
          "openai",
          "gpt-test",
          200,
          Duration.ofMillis(17));
      pipeline.recordRequestLog(
          "trace-1",
          "/v1/chat/completions",
          "POST",
          "openai",
          "gpt-test",
          200,
          Duration.ofMillis(17),
          "MISS");
      pipeline.flush();

      assertThat(pipeline.exportFailureCount("traces")).isGreaterThanOrEqualTo(1);
      assertThat(pipeline.exportFailureCount("metrics")).isGreaterThanOrEqualTo(1);
      assertThat(pipeline.exportFailureCount("logs")).isGreaterThanOrEqualTo(1);

      pipeline.recordRequestMetric(
          "/v1/chat/completions",
          "openai",
          "gpt-test",
          200,
          Duration.ofMillis(19));
      pipeline.flush();

      assertThat(captured.await()).isTrue();
      assertThat(captured.bodiesAsIso88591()).anySatisfy(body -> assertThat(body)
          .contains("modelgate.otel.export.failures")
          .contains("traces")
          .contains("metrics")
          .contains("logs"));
    }
  }

  @Test
  void pipelineHonorsAlwaysOffTraceSampler() throws Exception {
    CapturedOtlpRequest captured = new CapturedOtlpRequest();
    try (RunningCollector collector = RunningCollector.start(captured);
        OpenTelemetryPipeline pipeline = OpenTelemetryPipeline.create(OpenTelemetrySettings.fromEnvironment(Map.of(
            "OTEL_TRACES_EXPORTER", "otlp",
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", collector.url("/v1/traces"),
            "OTEL_TRACES_SAMPLER", "always_off",
            "OTEL_SERVICE_NAME", "modelgate-test")))) {
      pipeline.recordProviderSpan(
          "trace-1",
          "span-1",
          "openai",
          "/v1/chat/completions",
          "gpt-test",
          200,
          Duration.ofMillis(17));
      pipeline.flush();

      assertThat(pipeline.enabled()).isTrue();
      assertThat(captured.await(300, TimeUnit.MILLISECONDS)).isFalse();
    }
  }

  private record RunningCollector(HttpServer server, int port) implements AutoCloseable {
    static RunningCollector start(CapturedOtlpRequest captured) throws Exception {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> {
        captured.path.set(exchange.getRequestURI().getPath());
        captured.headers.set(new LinkedHashMap<>());
        exchange.getRequestHeaders().forEach((name, values) ->
            captured.headers.get().put(name.toLowerCase(), values.isEmpty() ? "" : values.getFirst()));
        captured.body.set(exchange.getRequestBody().readAllBytes());
        captured.latch.countDown();
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().close();
      });
      server.start();
      return new RunningCollector(server, server.getAddress().getPort());
    }

    static RunningCollector start(CapturedOtlpRequests captured) throws Exception {
      return start(captured, 200);
    }

    static RunningCollector start(CapturedOtlpRequests captured, int responseStatus) throws Exception {
      return start(captured, List.of(responseStatus));
    }

    static RunningCollector start(CapturedOtlpRequests captured, List<Integer> responseStatuses) throws Exception {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
      server.createContext("/", exchange -> {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
            headers.put(name.toLowerCase(), values.isEmpty() ? "" : values.getFirst()));
        captured.record(
            exchange.getRequestURI().getPath(),
            headers,
            exchange.getRequestBody().readAllBytes());
        int index = Math.min(requests.getAndIncrement(), responseStatuses.size() - 1);
        exchange.sendResponseHeaders(responseStatuses.get(index), 0);
        exchange.getResponseBody().close();
      });
      server.start();
      return new RunningCollector(server, server.getAddress().getPort());
    }

    String url(String path) {
      return "http://127.0.0.1:" + port + path;
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static final class CapturedOtlpRequest {
    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> headers = new AtomicReference<>(Map.of());
    private final AtomicReference<byte[]> body = new AtomicReference<>(new byte[0]);

    boolean await() throws InterruptedException {
      return latch.await(5, TimeUnit.SECONDS);
    }

    boolean await(long timeout, TimeUnit unit) throws InterruptedException {
      return latch.await(timeout, unit);
    }

    String path() {
      return path.get();
    }

    String header(String name) {
      return headers.get().get(name.toLowerCase());
    }

    byte[] body() {
      return body.get();
    }
  }

  private static final class CapturedOtlpRequests {
    private final CountDownLatch latch;
    private final List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Map<String, String>> headers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<byte[]> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();

    private CapturedOtlpRequests(int expectedRequests) {
      this.latch = new CountDownLatch(expectedRequests);
    }

    boolean await() throws InterruptedException {
      return latch.await(5, TimeUnit.SECONDS);
    }

    void record(String path, Map<String, String> headers, byte[] body) {
      paths.add(path);
      this.headers.add(headers);
      bodies.add(body);
      latch.countDown();
    }

    List<String> paths() {
      return paths;
    }

    List<String> headerValues(String name) {
      return headers.stream()
          .map(item -> item.get(name.toLowerCase()))
          .filter(value -> value != null)
          .toList();
    }

    String headerValueByPath(String path, String name) {
      for (int i = 0; i < paths.size(); i++) {
        if (path.equals(paths.get(i))) {
          return headers.get(i).get(name.toLowerCase());
        }
      }
      return null;
    }

    List<String> bodiesAsIso88591() {
      return java.util.stream.IntStream.range(0, bodies.size())
          .mapToObj(index -> new String(decodedBody(index), StandardCharsets.ISO_8859_1))
          .toList();
    }

    private byte[] decodedBody(int index) {
      byte[] body = bodies.get(index);
      if (!"gzip".equalsIgnoreCase(headers.get(index).get("content-encoding"))) {
        return body;
      }
      try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
        return gzip.readAllBytes();
      } catch (IOException exception) {
        throw new IllegalStateException("Failed to decode gzip OTLP body", exception);
      }
    }
  }
}
