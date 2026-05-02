# Javalin Java 21 AI Gateway Port Plan

## Summary

Port the TypeScript `gateway` into a root-level Maven Java 21+ project using **Javalin 7.x on embedded Jetty 12.x**, with virtual threads enabled via `config.concurrency.useVirtualThreads = true`. Keep `gateway/` unchanged as the reference implementation and build the Java port under `src/main/java` and `src/test/java`.

Use staged parity: first build the gateway kernel and representative provider/plugin parity, then port the full provider and guardrail catalog in tracked waves. Current version references: Javalin docs show virtual-thread support and Javalin 7 requirements, Maven listings show Javalin 7.2.0 activity, Jetty 12.1.8, and Resilience4j 2.3.0; implementation should re-check Maven Central and pin latest non-alpha/non-RC releases.

Sources: [Javalin docs](https://javalin.io/documentation), [Jetty virtual threads](https://jetty.org/docs/jetty/12.1/programming-guide/arch/threads.html), [Javalin versions](https://mvnrepository.com/artifact/io.javalin/javalin/versions), [Jetty BOM](https://repo1.maven.org/maven2/org/eclipse/jetty/jetty-bom/), [Resilience4j BOM](https://repo1.maven.org/maven2/io/github/resilience4j/resilience4j-bom/).

## Key Changes

- Create a root Maven project with Java 21 release settings, Javalin, Jetty BOM, Jackson, Resilience4j, Caffeine, Redis client, AWS SDK v2, Nimbus JOSE JWT, Micrometer/OpenTelemetry, JUnit 5, AssertJ, Mockito, WireMock/MockWebServer, and Testcontainers as needed.
- Implement the same public HTTP surface: `/`, `/public/`, `/log/stream`, `/v1/models`, chat/completions, completions, embeddings, images, audio, files, batches, responses, fine-tuning, prompts passthrough, generic `/v1/*` proxy, and `/v1/realtime` WebSocket.
- Preserve Portkey-compatible headers and config semantics: `x-portkey-provider`, `x-portkey-config`, `Authorization`, metadata, trace ID, custom host, forwarded headers, retry count, request timeout, strict OpenAI compliance, default input/output guardrails. These are treated as backups/compatibility alongside the base implementation that uses x-modelgate-* first while preserving x-portkey-* compatibility.
- Model gateway internals as typed Java records/classes: `GatewayConfig`, `Target`, `Strategy`, `ProviderOptions`, `RetrySettings`, `CacheSettings`, `GatewayRequestContext`, `ProviderContext`, `HookSpan`, `HookResult`, and `PluginHandler`.
- Use virtual threads for request handlers and blocking provider/guardrail work; use bounded semaphores or Resilience4j bulkheads for external provider and plugin calls so virtual threads do not create unbounded outbound concurrency.
- Use Java `HttpClient` for provider calls, streaming responses, and WebSocket proxying; map multipart/audio/binary endpoints explicitly instead of relying on generic JSON handling.
- Recreate routing modes exactly: `single`, weighted `loadbalance`, ordered `fallback`, nested targets, inherited config, conditional routing with `$eq`, `$ne`, `$gt`, `$gte`, `$lt`, `$lte`, `$in`, `$nin`, `$regex`, `$and`, `$or`.
- Recreate reliability behavior: retries on configured status codes, provider `Retry-After`/`retry-after-ms` support, request timeout returning 408-compatible payloads, fallback interruption on gateway exceptions, and retry metadata response headers.
	- Recreate cache behavior: simple response cache with Caffeine in-memory first, Redis backend when configured, cache TTL/max-age, force refresh header, no stream/file-upload caching, and hook-result preservation on cache hits.
- Recreate guardrails/plugins with a Java plugin registry. Port all built-in `default.*` plugins first, then partner plugins in waves. External guardrail integrations keep their existing wire contracts and credentials structure.
- Recreate security validation: content-type checks, provider allowlist, `x-portkey-config` schema validation, forbidden recursive `forward_headers`, custom-host SSRF protections, blocked metadata/internal IPs, blocked schemes, and trusted custom hosts env config.
- Recreate observability: structured request logs, `/log/stream` SSE, trace ID propagation, provider/cache/retry headers, timing fields, Micrometer metrics, virtual-thread metrics where available, and health/readiness endpoints.

## Provider Porting Waves

- Wave 1: core provider framework plus OpenAI, Anthropic, Azure OpenAI, Bedrock, Google Vertex AI, Google, Cohere, Mistral, Groq, Together, Ollama, and generic proxy.
- Wave 2: image/audio/files/batches/finetune-capable providers including Stability, Fireworks, Workers AI, HuggingFace, DeepSeek, OpenRouter, Perplexity, Voyage, Jina, Sagemaker.
- Wave 3: remaining providers from `gateway/src/providers`, each ported by copying endpoint mapping, header/auth logic, request transforms, response transforms, stream transforms, and provider-specific tests.
- Every provider must have contract tests against recorded/mocked HTTP requests before it is marked parity-complete.

## Test Plan

- Port existing TypeScript unit/integration scenarios into JUnit 5: request validation, config parsing, conditional routing, load balancing, fallback, retry, timeout, cache, hook execution, mutators, guardrail deny responses, response headers, and proxy behavior.
- Add WireMock/MockWebServer tests for provider request mapping and response mapping, including streaming SSE chunks, multipart uploads, audio buffers, and binary passthrough.
- Add plugin parity tests for all `default.*` plugins before partner plugins; partner plugins use mocked upstream APIs and credential/error cases.
- Add security tests for SSRF custom-host validation, forwarded-header recursion, blocked schemes, internal IP forms, metadata hosts, malformed config JSON, and invalid providers.
- Add Maven verification commands: `mvn test`, `mvn verify`, and a Docker image build smoke test. Include a small compatibility smoke suite that sends OpenAI SDK-style requests to the Java gateway.

## Assumptions

- Runtime is **Javalin**, not Quarkus.
- Project layout is root Maven project; existing `gateway/` remains as reference source during the port.
- Parity is staged but comprehensive: no feature is intentionally dropped; incomplete provider/plugin parity is tracked explicitly.
- Latest library versions means latest stable, non-alpha, non-RC Maven Central versions at implementation time.
- Enterprise-only hosted Portkey features mentioned in docs, such as RBAC/org governance, are not implemented unless they exist in the local TypeScript source.
