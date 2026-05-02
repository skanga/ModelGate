# Production Readiness Checklist

Use this before promoting a ModelGate build.

## Build

- [ ] `cmd /c --% mvn -q verify` passes.
- [ ] `cmd /c --% mvn -q -DskipTests package` produces `target/modelgate-0.1.jar`.
- [ ] Docker image builds from the root `Dockerfile`.
- [ ] Image tag includes an immutable version or commit SHA.
- [ ] GitHub release workflow has run for the release tag or has been manually dispatched.

## Runtime

- [ ] `PORT` is set by the platform or defaults to `8787`.
- [ ] `MODELGATE_PROVIDER_MAX_CONCURRENT_REQUESTS` is sized for provider limits.
- [ ] `MODELGATE_PLUGIN_MAX_CONCURRENT_REQUESTS` is sized for guardrail/plugin evaluator limits.
- [ ] `MODELGATE_TRUSTED_CUSTOM_HOSTS` is empty or limited to approved hosts.
- [ ] JVM memory settings are set through `JAVA_TOOL_OPTIONS` or container limits.
- [ ] `/health` and `/ready` are wired to platform probes.

## Observability

- [ ] `/metrics` is scraped.
- [ ] OTLP endpoint and headers are configured if traces/metrics/logs should be exported.
- [ ] Provider status, retry, cache, guardrail, token, cost, and latency fields are collected downstream.
- [ ] Alerts exist for 5xx rate, provider timeout rate, p95 latency, and concurrency saturation.

## Security

- [ ] Provider API keys are delivered as request config or secret-managed runtime values, not baked into the image.
- [ ] Custom provider hosts are explicitly reviewed.
- [ ] Logs are reviewed for sensitive prompt, response, or metadata fields before production retention is enabled.
- [ ] Public ingress is TLS-only.
- [ ] Rate limits are enforced at the edge or load balancer.

## Rollout

- [ ] Deploy using rolling or canary strategy.
- [ ] Keep the previous jar/image available for rollback.
- [ ] Run smoke traffic against `/v1/models` and one real provider route.
- [ ] Check `src/test/resources/provider-contracts/provider-validation-matrix.json` for the provider. Do not treat a provider as production-ready unless live or recorded evidence exists for the exact account/path being enabled.
- [ ] Run `cmd /c --% mvn -q -Pprovider-readiness -Dmodelgate.provider.ready=<provider> test` before enabling a provider in production traffic.
- [ ] Validate guardrail denial and allowed-flow paths.
- [ ] Verify telemetry export after deployment.
