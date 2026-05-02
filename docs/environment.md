# ModelGate Environment Matrix

ModelGate is configured mostly per request through `x-modelgate-config` or `x-portkey-config`. Process-level settings control the listener, outbound concurrency, custom-host trust, and telemetry export.

## Runtime

| Variable | Default | Required | Description |
| --- | --- | --- | --- |
| `PORT` | `8787` | No | HTTP listen port. `--port <value>` overrides this when running the jar directly. |
| `MODELGATE_PROVIDER_MAX_CONCURRENT_REQUESTS` | `256` | No | Global cap for concurrent outbound provider requests. Values below `1` or non-numeric values fall back to `256`. |
| `MODELGATE_PLUGIN_MAX_CONCURRENT_REQUESTS` | `256` | No | Global cap for concurrent guardrail/plugin executions, including external evaluator calls. Values below `1` or non-numeric values fall back to `256`. |
| `MODELGATE_TRUSTED_CUSTOM_HOSTS` | empty | No | Comma-separated allowlist for `custom_host` targets that would otherwise be blocked by SSRF protections. Entries are normalized as hostnames. |
| `MODELGATE_REDIS_URL` | empty | No | Enables production Redis response cache backing for request configs that use `{"cache":{"mode":"redis"}}`. Supports `redis://[:password@]host[:port][/db]` and `redis://user:password@host[:port][/db]`. |
| `JAVA_TOOL_OPTIONS` | empty | No | JVM-level options supplied by the runtime, for example heap caps or GC flags. |

## OpenTelemetry

OTLP is disabled unless at least one signal is configured for OTLP and `OTEL_SDK_DISABLED` is not `true`.
Every `OTEL_*` variable can also be supplied as the equivalent lowercase dotted Java system property, for example `-Dotel.exporter.otlp.endpoint=http://collector:4318`; system properties take precedence over environment variables.

| Variable | Default | Required | Description |
| --- | --- | --- | --- |
| `OTEL_SDK_DISABLED` | `false` | No | Set to `true` to force telemetry off. |
| `OTEL_SERVICE_NAME` | `modelgate` | No | Service name attached to exported telemetry. |
| `OTEL_RESOURCE_ATTRIBUTES` | `service.namespace=modelgate` | No | Comma-separated URL-decoded OpenTelemetry resource attributes, for example `deployment.environment=prod,service.namespace=edge`. `OTEL_SERVICE_NAME` overrides any `service.name` value here. |
| `OTEL_RESOURCE_DISABLED_KEYS` | empty | No | Comma-separated resource attribute keys to remove after `OTEL_RESOURCE_ATTRIBUTES` and `OTEL_SERVICE_NAME` are merged. |
| `OTEL_PROPAGATORS` | `tracecontext,baggage` | No | Comma-separated propagators. Supported values are `tracecontext`, `baggage`, `b3`, `b3multi`, `jaeger`, `ottrace`, `xray`, `xray-lambda`, and `none`; unknown values are ignored. |
| `OTEL_EXPORTER` | empty | No | Comma-separated exporter list. If it contains `otlp`, all signals are enabled unless a signal exporter is `none`. If it contains `none` without `otlp`, shared endpoint auto-enablement is disabled. |
| `OTEL_TRACES_EXPORTER` | empty | No | Comma-separated trace exporter list. If it contains `otlp`, traces are enabled; if it contains `none` without `otlp`, traces are disabled. |
| `OTEL_METRICS_EXPORTER` | empty | No | Comma-separated metric exporter list. If it contains `otlp`, metrics are enabled; if it contains `none` without `otlp`, metrics are disabled. |
| `OTEL_LOGS_EXPORTER` | empty | No | Comma-separated log exporter list. If it contains `otlp`, logs are enabled; if it contains `none` without `otlp`, logs are disabled. |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` | No | `http/protobuf` or `grpc`. |
| `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL` | `OTEL_EXPORTER_OTLP_PROTOCOL` | No | Trace exporter protocol override. |
| `OTEL_EXPORTER_OTLP_METRICS_PROTOCOL` | `OTEL_EXPORTER_OTLP_PROTOCOL` | No | Metric exporter protocol override. |
| `OTEL_EXPORTER_OTLP_LOGS_PROTOCOL` | `OTEL_EXPORTER_OTLP_PROTOCOL` | No | Log exporter protocol override. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` for HTTP, `http://localhost:4317` for gRPC | No | Base OTLP endpoint. HTTP mode appends `/v1/traces`, `/v1/metrics`, or `/v1/logs`. |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | derived | No | Signal-specific traces endpoint. |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | derived | No | Signal-specific metrics endpoint. |
| `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` | derived | No | Signal-specific logs endpoint. |
| `OTEL_EXPORTER_OTLP_HEADERS` | empty | No | Comma-separated URL-decoded headers, for example `authorization=Bearer%20token`. |
| `OTEL_EXPORTER_OTLP_TRACES_HEADERS` | empty | No | Additional traces exporter headers. Values override global headers with the same name for traces only. |
| `OTEL_EXPORTER_OTLP_METRICS_HEADERS` | empty | No | Additional metrics exporter headers. Values override global headers with the same name for metrics only. |
| `OTEL_EXPORTER_OTLP_LOGS_HEADERS` | empty | No | Additional logs exporter headers. Values override global headers with the same name for logs only. |
| `OTEL_EXPORTER_OTLP_TIMEOUT` | `10000` | No | Export timeout in milliseconds. |
| `OTEL_EXPORTER_OTLP_TRACES_TIMEOUT` | `OTEL_EXPORTER_OTLP_TIMEOUT` | No | Trace export timeout override in milliseconds. |
| `OTEL_EXPORTER_OTLP_METRICS_TIMEOUT` | `OTEL_EXPORTER_OTLP_TIMEOUT` | No | Metric export timeout override in milliseconds. |
| `OTEL_EXPORTER_OTLP_LOGS_TIMEOUT` | `OTEL_EXPORTER_OTLP_TIMEOUT` | No | Log export timeout override in milliseconds. |
| `OTEL_EXPORTER_OTLP_COMPRESSION` | `none` | No | Export compression. Supported values are `gzip` and `none`; unknown values fall back to `none`. |
| `OTEL_EXPORTER_OTLP_TRACES_COMPRESSION` | `OTEL_EXPORTER_OTLP_COMPRESSION` | No | Trace export compression override. |
| `OTEL_EXPORTER_OTLP_METRICS_COMPRESSION` | `OTEL_EXPORTER_OTLP_COMPRESSION` | No | Metric export compression override. |
| `OTEL_EXPORTER_OTLP_LOGS_COMPRESSION` | `OTEL_EXPORTER_OTLP_COMPRESSION` | No | Log export compression override. |
| `OTEL_EXPORTER_OTLP_CERTIFICATE` | empty | No | PEM trusted CA certificate file for all OTLP exporters. |
| `OTEL_EXPORTER_OTLP_TRACES_CERTIFICATE` | `OTEL_EXPORTER_OTLP_CERTIFICATE` | No | Trace exporter trusted CA certificate override. |
| `OTEL_EXPORTER_OTLP_METRICS_CERTIFICATE` | `OTEL_EXPORTER_OTLP_CERTIFICATE` | No | Metric exporter trusted CA certificate override. |
| `OTEL_EXPORTER_OTLP_LOGS_CERTIFICATE` | `OTEL_EXPORTER_OTLP_CERTIFICATE` | No | Log exporter trusted CA certificate override. |
| `OTEL_EXPORTER_OTLP_CLIENT_KEY` | empty | No | PEM client private key file for mTLS across all OTLP exporters. Used only when a matching client certificate is configured. |
| `OTEL_EXPORTER_OTLP_CLIENT_CERTIFICATE` | empty | No | PEM client certificate file for mTLS across all OTLP exporters. Used only when a matching client key is configured. |
| `OTEL_EXPORTER_OTLP_TRACES_CLIENT_KEY` | `OTEL_EXPORTER_OTLP_CLIENT_KEY` | No | Trace exporter client private key override. |
| `OTEL_EXPORTER_OTLP_TRACES_CLIENT_CERTIFICATE` | `OTEL_EXPORTER_OTLP_CLIENT_CERTIFICATE` | No | Trace exporter client certificate override. |
| `OTEL_EXPORTER_OTLP_METRICS_CLIENT_KEY` | `OTEL_EXPORTER_OTLP_CLIENT_KEY` | No | Metric exporter client private key override. |
| `OTEL_EXPORTER_OTLP_METRICS_CLIENT_CERTIFICATE` | `OTEL_EXPORTER_OTLP_CLIENT_CERTIFICATE` | No | Metric exporter client certificate override. |
| `OTEL_EXPORTER_OTLP_LOGS_CLIENT_KEY` | `OTEL_EXPORTER_OTLP_CLIENT_KEY` | No | Log exporter client private key override. |
| `OTEL_EXPORTER_OTLP_LOGS_CLIENT_CERTIFICATE` | `OTEL_EXPORTER_OTLP_CLIENT_CERTIFICATE` | No | Log exporter client certificate override. |
| `OTEL_TRACES_SAMPLER` | `parentbased_always_on` | No | Trace sampler. Supported values are `always_on`, `always_off`, `traceidratio`, `parentbased_always_on`, `parentbased_always_off`, and `parentbased_traceidratio`. |
| `OTEL_TRACES_SAMPLER_ARG` | empty | No | Trace sampler argument. Used as the ratio for `traceidratio` and `parentbased_traceidratio`; invalid ratios fall back to `1.0`. |
| `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE` | `cumulative` | No | Metric temporality preference. Supported values are `cumulative`, `delta`, and `lowmemory`. |
| `OTEL_EXPORTER_OTLP_METRICS_DEFAULT_HISTOGRAM_AGGREGATION` | `explicit_bucket_histogram` | No | Default histogram aggregation. Supported values are `explicit_bucket_histogram` and `base2_exponential_bucket_histogram`. |
| `OTEL_METRICS_EXEMPLAR_FILTER` | `trace_based` | No | Metric exemplar filter. Supported values are `trace_based`, `always_on`, and `always_off`. |
| `OTEL_JAVA_METRICS_CARDINALITY_LIMIT` | `2000` | No | Java SDK metric cardinality limit: maximum distinct attribute combinations per metric per collection cycle. |
| `OTEL_JAVA_EXPORTER_MEMORY_MODE` | `reusable_data` | No | Java SDK exporter memory mode. Supported values are `reusable_data` and `immutable_data`. |
| `OTEL_JAVA_EXPORTER_OTLP_RETRY_DISABLED` | `false` | No | Set to `true` to disable Java SDK transient OTLP exporter retries. By default ModelGate applies the SDK default retry policy to all OTLP trace, metric, and log exporters. |
| `OTEL_METRIC_EXPORT_INTERVAL` | `60000` | No | Periodic metric export interval in milliseconds. |
| `OTEL_METRIC_EXPORT_TIMEOUT` | `10000` | No | Metric export timeout in milliseconds. ModelGate enforces this with a bounded metric exporter wrapper because the configured SDK reader version has no direct reader timeout setter. |
| `OTEL_BSP_SCHEDULE_DELAY` | `5000` | No | Batch span processor schedule delay in milliseconds. |
| `OTEL_BSP_EXPORT_TIMEOUT` | `30000` | No | Batch span processor export timeout in milliseconds. |
| `OTEL_BSP_MAX_QUEUE_SIZE` | `2048` | No | Batch span processor max queue size. |
| `OTEL_BSP_MAX_EXPORT_BATCH_SIZE` | `512` | No | Batch span processor max export batch size. Values above max queue size are capped. |
| `OTEL_BLRP_SCHEDULE_DELAY` | `1000` | No | Batch log record processor schedule delay in milliseconds. |
| `OTEL_BLRP_EXPORT_TIMEOUT` | `30000` | No | Batch log record processor export timeout in milliseconds. |
| `OTEL_BLRP_MAX_QUEUE_SIZE` | `2048` | No | Batch log record processor max queue size. |
| `OTEL_BLRP_MAX_EXPORT_BATCH_SIZE` | `512` | No | Batch log record processor max export batch size. Values above max queue size are capped. |
| `OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT` | unlimited | No | Max attribute value length for spans and log records. |
| `OTEL_ATTRIBUTE_COUNT_LIMIT` | `128` | No | Default max attribute count used by span event/link and log record limits when more specific variables are unset. |
| `OTEL_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT` | `OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT` | No | Max attribute value length for spans. |
| `OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT` | `OTEL_ATTRIBUTE_COUNT_LIMIT` | No | Max number of attributes on each span. |
| `OTEL_SPAN_EVENT_COUNT_LIMIT` | `128` | No | Max number of events on each span. |
| `OTEL_SPAN_LINK_COUNT_LIMIT` | `128` | No | Max number of links on each span. |
| `OTEL_EVENT_ATTRIBUTE_COUNT_LIMIT` | `OTEL_ATTRIBUTE_COUNT_LIMIT` | No | Max number of attributes on each span event. |
| `OTEL_LINK_ATTRIBUTE_COUNT_LIMIT` | `OTEL_ATTRIBUTE_COUNT_LIMIT` | No | Max number of attributes on each span link. |
| `OTEL_LOGRECORD_ATTRIBUTE_VALUE_LENGTH_LIMIT` | `OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT` | No | Max attribute value length for log records. |
| `OTEL_LOGRECORD_ATTRIBUTE_COUNT_LIMIT` | `OTEL_ATTRIBUTE_COUNT_LIMIT` | No | Max number of attributes on each log record. |

Signal-specific OTLP settings are isolated. For example, `OTEL_EXPORTER_OTLP_TRACES_HEADERS` is sent only with trace exports, and `OTEL_EXPORTER_OTLP_METRICS_PROTOCOL=grpc` does not force traces or logs onto gRPC. If a signal uses `http/protobuf` and only `OTEL_EXPORTER_OTLP_ENDPOINT` is set, ModelGate appends that signal's standard path. If a signal uses `grpc`, the base endpoint is used as-is. Exporter variables are parsed as comma-separated lists; ModelGate currently acts on `otlp` and `none` and ignores other exporter names such as `console` or `zipkin`. `OTEL_EXPORTER=none` prevents a shared OTLP endpoint from enabling every signal implicitly; use signal-specific `*_EXPORTER=otlp` values to opt individual signals back in. TLS/mTLS files are read at startup when configured, and signal-specific certificate/key settings override global files. Provider requests export a gateway root span with child spans for input guardrails, provider calls, and eval summaries when tracing is enabled.

ModelGate wraps OTLP exporters and records failed exports by signal. When OTLP metrics are enabled, failures are emitted as `modelgate.otel.export.failures` with a `signal` attribute of `traces`, `metrics`, or `logs`.

## Per-Request Secrets

Provider credentials are intentionally request config, not process environment. Pass them in `x-modelgate-config`, `x-portkey-config`, or `Authorization` according to the provider being used.

The exception is the opt-in live-provider validation test suite, which reads provider credentials from environment variables so secrets are not committed to fixtures. See [provider-validation.md](provider-validation.md).

Common config fields:

| Field | Description |
| --- | --- |
| `provider` | Provider id, for example `openai`, `anthropic`, `bedrock`, `vertex-ai`, `sagemaker`. |
| `api_key` | Provider API key. Also accepted as `apiKey` after normalization. |
| `custom_host` | Provider base URL override. Subject to SSRF validation and trusted-host checks. |
| `request_timeout` | Per-provider request timeout in milliseconds. |
| `retry` | Retry policy with `attempts`, `on_status_codes`, and `use_retry_after_header`. |
| `cache` | Response cache settings. Use `{"mode":"simple","max_age":300000}` for in-memory cache. |
| `input_guardrails` | Guardrail/plugin checks before provider call. |
| `output_guardrails` | Guardrail/plugin checks after provider response. |
| `default_input_guardrails` | Default before-provider checks appended after `input_guardrails`. Header aliases: `x-modelgate-default-input-guardrails`, `x-portkey-default-input-guardrails`. |
| `default_output_guardrails` | Default after-provider checks appended after `output_guardrails`. Header aliases: `x-modelgate-default-output-guardrails`, `x-portkey-default-output-guardrails`. |
| `targets` | Multi-provider routing targets for fallback/load balancing/conditional routing. |
