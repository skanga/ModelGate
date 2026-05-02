# ModelGate

ModelGate is a Java 21+ AI gateway port of the TypeScript gateway in `gateway/`. It is built with Maven, Javalin 7, embedded Jetty 12, Java virtual threads, Java `HttpClient`, guardrail/plugin hooks, provider transforms, routing, caching, streaming, telemetry, and OpenAI-compatible `/v1/*` proxy surfaces.

The Java implementation lives at the repository root under `src/main/java` and `src/test/java`. The original TypeScript `gateway/` directory is kept as the reference implementation during parity work.

## Status

The current Java gateway is usable for local and staged provider traffic.

- Core gateway kernel: implemented.
- Default `default.*` guardrail/plugin registry: implemented.
- Provider routing/transforms: broad mocked contract coverage, with ongoing live-provider hardening.
- Observability: structured logs, `/log/stream`, `/metrics`, trace headers, Micrometer/Prometheus, and OTLP traces/metrics/logs.
- Packaging: executable shaded jar and Dockerfile.

See [PLAN.md](PLAN.md) for the original port plan and remaining parity goals.

## Features

- Java 21 Maven app using Javalin 7 and virtual threads.
- OpenAI-compatible gateway endpoints:
  - `/v1/chat/completions`
  - `/v1/completions`
  - `/v1/embeddings`
  - `/v1/models`
  - generic `/v1/*` passthrough
  - `/v1/proxy/*`
  - `/v1/realtime` WebSocket proxy
- Explicit handling for streaming, multipart, binary passthrough, images, audio, files, batches, fine-tuning, and prompt-style passthrough.
- Provider routing modes:
  - single target
  - fallback
  - load balance
  - conditional routing
- Retry, `Retry-After`, request timeout, fallback inheritance, and provider-attempt metadata.
- Cache support:
  - in-memory `simple` cache
  - Redis-backed cache when `MODELGATE_REDIS_URL` is configured
- Guardrail hooks:
  - input guardrails before provider calls
  - output guardrails after provider responses
  - denial mode through `deny: true`
  - hook result metadata suitable for eval collection
  - bounded plugin execution concurrency through `MODELGATE_PLUGIN_MAX_CONCURRENT_REQUESTS`
- Header compatibility:
  - `x-modelgate-*` primary headers
  - `x-portkey-*` compatibility aliases
- Security:
  - custom-host SSRF checks
  - forwarded-header controls
  - blocked internal/metadata host protection
  - provider/config validation
- Observability:
  - `/health`
  - `/ready`
  - `/metrics`
  - `/log/stream`
  - provider/cache/retry/timing headers
  - provider and plugin concurrency gauges
  - OTLP export pipeline

## Requirements

- Java 21+
- Maven 3.9+
- Optional: Docker, for container image builds
- Optional: Python 3.11+, for `scripts/modelgate_request.py`

## Build And Test

Run the full verification suite:

```cmd
mvn -q verify
```

Build the executable jar:

```cmd
mvn -q -DskipTests package
```

The jar is produced at:

```text
target/modelgate-0.1.jar
```

Run the gateway from Maven:

```cmd
mvn -q -DskipTests exec:java -Dexec.args=--port 8787
```

Run the packaged jar:

```cmd
java -jar target\modelgate-0.1.jar --port 8787
```

Check health:

```cmd
curl -s http://localhost:8787/health
curl -s http://localhost:8787/ready
```

## Docker

Build:

```cmd
docker build -t modelgate:0.1 .
```

Run:

```cmd
docker run --rm -p 8787:8787 --env-file examples\configs\otel.env.example modelgate:0.1
```

The image runs as a non-root `modelgate` user and includes a `/health` container healthcheck.

## Release Workflow

`.github/workflows/release.yml` publishes `target/modelgate-0.1.jar` as a GitHub release asset for tags matching `v*` or manual dispatch with a required `release_tag` input. The workflow runs `mvn -q verify`, packages the shaded jar, smoke-tests `/health` and `/ready`, and performs a Docker build smoke before publishing.

## Request Configuration

ModelGate is configured per request. Send JSON config in `x-modelgate-config`; `x-portkey-config` remains supported for compatibility.

Minimal OpenAI-compatible request:

```cmd
curl -s http://localhost:8787/v1/chat/completions ^
  -H "content-type: application/json" ^
  -H "x-modelgate-config: {\"provider\":\"openai\",\"api_key\":\"replace-me\"}" ^
  -d "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"Say hello\"}]}"
```

Common config fields:

| Field | Purpose |
| --- | --- |
| `provider` | Provider id, such as `openai`, `anthropic`, `google`, `bedrock`, `vertex-ai`, `sagemaker`. |
| `api_key` | Provider API key. |
| `custom_host` | Provider base URL override, subject to SSRF validation. |
| `request_timeout` | Provider request timeout in milliseconds. |
| `retry` | Retry config with `attempts`, `on_status_codes`, and `use_retry_after_header`. |
| `cache` | Response cache config. |
| `input_guardrails` | Guardrails/plugins executed before the provider call. |
| `output_guardrails` | Guardrails/plugins executed after the provider response. |
| `default_input_guardrails` | Default before-provider guardrails appended after explicit input guardrails. Can also be supplied with `x-modelgate-default-input-guardrails`. |
| `default_output_guardrails` | Default after-provider guardrails appended after explicit output guardrails. Can also be supplied with `x-modelgate-default-output-guardrails`. |
| `targets` | Multi-provider routing targets. |
| `strategy` | Routing mode and conditional/fallback behavior. |

Sample configs are in [examples/configs](examples/configs):

- [openai-simple.json](examples/configs/openai-simple.json)
- [fallback-loadbalance.json](examples/configs/fallback-loadbalance.json)
- [guardrails-evals.json](examples/configs/guardrails-evals.json)
- [redis-cache.json](examples/configs/redis-cache.json)
- [sagemaker-sigv4.json](examples/configs/sagemaker-sigv4.json)
- [otel.env.example](examples/configs/otel.env.example)

## Smoke Testing Providers

Use the Python helper to avoid shell-escaping large config JSON.

Dry run:

```cmd
python scripts\modelgate_request.py --preset openai --key-env OPENAI_API_KEY --dry-run
```

Send traffic through an already running gateway:

```cmd
python scripts\modelgate_request.py --base-url http://localhost:8787 --preset openai --key-env OPENAI_API_KEY --message "Return a JSON object with an answer field."
```

Start a temporary gateway and send traffic:

```cmd
python scripts\modelgate_request.py --start-gateway --preset openai --key-env OPENAI_API_KEY
```

List models through the gateway:

```cmd
python scripts\modelgate_request.py --base-url http://localhost:8787 --models --preset openai --key-env OPENAI_API_KEY
```

Build a request from the live provider validation manifest:

```cmd
set OPENAI_API_KEY=sk-...
set MODELGATE_LIVE_OPENAI_BASE_URL=https://api.openai.com/v1
python scripts\modelgate_request.py --live-scenario openai --dry-run
```

`--live-scenario` builds one manifest scenario. Use a provider id or exact scenario name; broad tags can match multiple scenarios and will be reported as ambiguous. It honors the manifest endpoint, model defaults, required env vars, API key env var, and optional `MODELGATE_LIVE_*_BASE_URL` override.

Inspect provider validation status without credentials or network calls:

```cmd
python scripts\modelgate_request.py --validation-report
python scripts\modelgate_request.py --validation-report --provider openai --format json
python scripts\modelgate_request.py --validation-check-ready openai
python scripts\modelgate_request.py --validation-check-ready all
```

The offline report summarizes tier counts, production readiness, live scenario metadata, and fails with exit code `2` if the live manifest and validation matrix drift. The readiness gate returns `0` only for selected providers marked `production_ready`, `1` for known providers that are not ready, and `2` for bad input or drift.

The same readiness gate can be enforced from Maven:

```cmd
cmd /c --% mvn -q -Pprovider-readiness -Dmodelgate.provider.ready=openai test
```

## Guardrails And Plugins

Guardrails are configured as input or output hook entries. Each entry names a plugin and its parameters. A failed check becomes a blocking gateway response when `deny` is `true`; otherwise results are recorded as hook metadata.

Default guardrails can be configured in `default_input_guardrails` and `default_output_guardrails`, or through `x-modelgate-default-input-guardrails` / `x-modelgate-default-output-guardrails`. `x-portkey-default-input-guardrails` and `x-portkey-default-output-guardrails` remain compatibility aliases. Explicit guardrails run first; defaults run after them. Output guardrails are enforced for ordinary and cached JSON responses; streaming requests with output guardrails are rejected because streaming responses cannot be evaluated before bytes are forwarded.

Example:

```json
{
  "provider": "openai",
  "api_key": "replace-me",
  "metadata": {
    "tenant": "example"
  },
  "input_guardrails": [
    {
      "id": "block-secrets",
      "default.regexMatch": {
        "rule": "(api[_-]?key|secret|password)\\s*[:=]",
        "not": true
      },
      "deny": true
    }
  ]
}
```

Implemented default plugins include checks and transforms such as contains, regex match, regex replace, word/character/sentence counts, JSON keys/schema, required metadata keys, allowed request types, model whitelist/rules, valid URLs, webhook, log, JWT validation, and code detection.

## Provider Notes

The provider layer includes request mapping, headers/auth, body transforms, response transforms, streaming transforms, and mocked gateway contracts for a broad provider set, including OpenAI-compatible providers, Anthropic, Gemini/Google, Vertex AI, Bedrock, Cohere, Mistral, Workers AI, Sagemaker, Stability, Fireworks, HuggingFace, DeepSeek, OpenRouter, Perplexity, Voyage, Jina, Groq, Nvidia NIM, Sambanova, Cerebras, and Inception.

Provider validation status is tracked in [provider-validation-matrix.json](src/test/resources/provider-contracts/provider-validation-matrix.json). Treat mocked contract parity as necessary but not sufficient for real-provider rollout; `production_ready` is intentionally false until live or recorded evidence exists for the exact provider/account path.

Sagemaker static AWS SigV4 signing activates when both `aws_access_key_id` and `aws_secret_access_key` are present. `aws_session_token` is forwarded and signed when supplied.

## Observability

Local endpoints:

| Endpoint | Purpose |
| --- | --- |
| `/health` | Liveness check. |
| `/ready` | Readiness check. |
| `/metrics` | Prometheus/Micrometer metrics. |
| `/log/stream` | Server-sent request log stream. |

Process-level telemetry is configured with standard `OTEL_*` environment variables. Each value can also be supplied as the equivalent lowercase dotted Java system property, for example `-Dotel.exporter.otlp.endpoint=http://localhost:4318`; system properties take precedence over environment variables. See [docs/environment.md](docs/environment.md) for the full matrix.

Useful starting env:

```cmd
set OTEL_SERVICE_NAME=modelgate
set OTEL_RESOURCE_ATTRIBUTES=service.namespace=modelgate,deployment.environment=local
set OTEL_RESOURCE_DISABLED_KEYS=
set OTEL_PROPAGATORS=tracecontext,baggage
set OTEL_EXPORTER=otlp
set OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
set OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
set OTEL_EXPORTER_OTLP_COMPRESSION=none
set OTEL_TRACES_SAMPLER=parentbased_always_on
set OTEL_METRIC_EXPORT_INTERVAL=60000
set OTEL_METRIC_EXPORT_TIMEOUT=10000
set OTEL_METRICS_EXEMPLAR_FILTER=trace_based
set OTEL_JAVA_METRICS_CARDINALITY_LIMIT=2000
set OTEL_JAVA_EXPORTER_MEMORY_MODE=reusable_data
set OTEL_JAVA_EXPORTER_OTLP_RETRY_DISABLED=false
set OTEL_BSP_SCHEDULE_DELAY=5000
set OTEL_BLRP_SCHEDULE_DELAY=1000
set OTEL_ATTRIBUTE_COUNT_LIMIT=128
set OTEL_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT=-1
set OTEL_LOGRECORD_ATTRIBUTE_VALUE_LENGTH_LIMIT=-1
```

ModelGate supports OTLP traces, metrics, and logs over HTTP/protobuf or gRPC. Global `OTEL_EXPORTER_OTLP_*` values can be overridden per signal with `OTEL_EXPORTER_OTLP_TRACES_*`, `OTEL_EXPORTER_OTLP_METRICS_*`, and `OTEL_EXPORTER_OTLP_LOGS_*` for endpoint, protocol, headers, timeout, and compression. Resource attributes follow `OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES`, and `OTEL_RESOURCE_DISABLED_KEYS`. `OTEL_PROPAGATORS` supports `tracecontext`, `baggage`, `b3`, `b3multi`, `jaeger`, `ottrace`, `xray`, `xray-lambda`, and `none`. Exporter variables are parsed as comma-separated lists; ModelGate acts on `otlp` and `none` and ignores unsupported exporter names such as `console` or `zipkin`. `OTEL_EXPORTER=none` disables implicit signal enablement from a shared OTLP endpoint; individual signals can still opt in with `OTEL_TRACES_EXPORTER=otlp`, `OTEL_METRICS_EXPORTER=otlp`, or `OTEL_LOGS_EXPORTER=otlp`. Trace sampling follows `OTEL_TRACES_SAMPLER` / `OTEL_TRACES_SAMPLER_ARG`; metric exemplar filtering follows `OTEL_METRICS_EXEMPLAR_FILTER`; Java metric cardinality follows `OTEL_JAVA_METRICS_CARDINALITY_LIMIT`; exporter memory mode follows `OTEL_JAVA_EXPORTER_MEMORY_MODE`; transient OTLP export retries follow `OTEL_JAVA_EXPORTER_OTLP_RETRY_DISABLED`; batch span and log processor behavior follows `OTEL_BSP_*` and `OTEL_BLRP_*`; SDK span/log limits follow the standard `OTEL_*_LIMIT` variables. Provider traffic exports a gateway root span with child spans for guardrails, provider calls, and eval summaries.

OTLP TLS and mTLS use standard PEM file env vars: `OTEL_EXPORTER_OTLP_CERTIFICATE`, `OTEL_EXPORTER_OTLP_CLIENT_KEY`, and `OTEL_EXPORTER_OTLP_CLIENT_CERTIFICATE`, with `TRACES`, `METRICS`, and `LOGS` signal-specific overrides.

Failed OTLP exports are counted by signal as `modelgate.otel.export.failures` when OTLP metrics are enabled.

## Production Docs

- [Deployment notes](docs/deployment.md)
- [Environment matrix](docs/environment.md)
- [Provider validation](docs/provider-validation.md)
- [Sample config guide](docs/sample-configs.md)
- [Production readiness checklist](docs/production-readiness.md)

## Repository Layout

```text
.
├── src/main/java/com/modelgate/gateway
│   ├── cache
│   ├── config
│   ├── headers
│   ├── hooks
│   ├── http
│   ├── observability
│   ├── plugins
│   ├── provider
│   ├── routing
│   └── validation
├── src/test/java/com/modelgate/gateway
├── src/test/resources/provider-contracts
├── scripts
├── examples/configs
├── docs
├── gateway
├── Dockerfile
├── pom.xml
└── PLAN.md
```

## Operational Guidance

- Keep provider credentials out of the Docker image and committed config files.
- Prefer `x-modelgate-config` for new integrations; keep `x-portkey-config` only for compatibility.
- Keep `MODELGATE_TRUSTED_CUSTOM_HOSTS` empty unless a reviewed internal provider host is required.
- Start with `MODELGATE_PROVIDER_MAX_CONCURRENT_REQUESTS=256` and tune based on provider throttle behavior and p95 latency.
- Start with `MODELGATE_PLUGIN_MAX_CONCURRENT_REQUESTS=256` and tune based on guardrail evaluator limits and queueing.
- Use canary or rolling deployments. Request-scoped config means old and new gateway versions can usually run side by side.
- Run live-provider smoke tests for any provider before enabling it in production traffic.
