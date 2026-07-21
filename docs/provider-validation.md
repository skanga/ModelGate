# Provider Validation

ModelGate has two provider validation layers:

1. Mocked contract tests run in normal `mvn verify`.
2. Live provider validation is opt-in and calls real provider APIs only when explicitly enabled.

The live campaign manifest is [live-validation.json](../src/test/resources/provider-contracts/live-validation.json). It covers the priority provider hardening set: Bedrock, Vertex AI, Cohere, Mistral, Workers AI, Sagemaker, Stability, Fireworks, HuggingFace, DeepSeek, OpenRouter, Perplexity, Voyage, and Jina. It also includes OpenAI and Anthropic baseline smokes.

The validation closure matrix is [provider-validation-matrix.json](../src/test/resources/provider-contracts/provider-validation-matrix.json). It lists every supported provider and its current evidence tier:

| Tier | Meaning |
| --- | --- |
| `mocked-smoke` | Gateway routing/auth shape is covered by mocked contracts, but provider-specific transforms still need live or recorded validation. |
| `mocked-contract` | Provider-specific mocked request/response transforms are covered. Live or recorded validation is still required before production enablement. |
| `recorded-contract` | Recorded upstream contracts exist for the provider. |
| `live-smoke` | An opt-in live validation scenario exists. Run it with real credentials before enabling production traffic. |

`production_ready` remains `false` in the matrix until live or recorded evidence is captured for the exact provider/account path being enabled. A ready row must include structured evidence; the Java and Python readiness gates fail closed when `production_ready=true` is set without it.

Ready evidence shape:

```json
{
  "provider": "openai",
  "validation_tier": "live-smoke",
  "live_validation": true,
  "recorded_contract": true,
  "production_ready": true,
  "evidence": {
    "type": "live",
    "scenario": "openai chat live smoke",
    "validated_at": "2026-05-02",
    "source": "ProviderLiveValidationTest"
  }
}
```

`evidence.type` must be `live` or `recorded`. Live evidence must name a scenario from `live-validation.json`. Recorded evidence requires `recorded_contract=true`. `validated_at` and `source` are required for both evidence types.

## Offline Validation Report

Use the Python helper to inspect the matrix without credentials and without network calls:

```cmd
python scripts\modelgate_request.py --validation-report
python scripts\modelgate_request.py --validation-report --format json
python scripts\modelgate_request.py --validation-report --provider openai --format json
```

The report summarizes tier counts, production-ready counts, live scenario names, required env vars, and base URL override env vars. It also checks drift between [live-validation.json](../src/test/resources/provider-contracts/live-validation.json) and [provider-validation-matrix.json](../src/test/resources/provider-contracts/provider-validation-matrix.json). A provider with a live scenario must be marked `live_validation: true` and `validation_tier: "live-smoke"` in the matrix, and any matrix row marked `live_validation: true` must have a live scenario.

Use the readiness gate in automation before enabling provider traffic:

```cmd
python scripts\modelgate_request.py --validation-check-ready openai
python scripts\modelgate_request.py --validation-check-ready all
```

The readiness gate is also offline. It returns `0` only when every selected matrix row is `production_ready: true`, has valid structured evidence, and the matrix/manifest drift check passes. It returns `1` for known providers that are not production-ready, which makes it suitable for CI or deployment scripts that need to fail closed before rollout.

Exit codes:

| Code | Meaning |
| --- | --- |
| `0` | Report generated, or readiness gate passed. |
| `1` | Readiness gate selected known providers that are not `production_ready`. |
| `2` | Unknown provider, unreadable/malformed JSON, matrix/manifest drift, or invalid readiness evidence. |

The same readiness gate semantics are available from Maven. Normal `mvn verify` always checks matrix/manifest drift, while the provider readiness gate is opt-in because the matrix intentionally starts with providers marked `production_ready: false`.

```cmd
cmd /c --% mvn -q -Pprovider-readiness -Dmodelgate.provider.ready=openai test
cmd /c --% mvn -q -Pprovider-readiness -Dmodelgate.provider.ready=all test
```

Use the Maven gate in CI or deployment jobs when a rollout requires a provider to be explicitly marked production-ready in the matrix.

## Run Live Validation

Set `MODELGATE_LIVE_PROVIDER_VALIDATION=true` and `MODELGATE_LIVE_PROVIDER_FILTER`, then provide the env vars for the provider scenarios you want to run. Scenarios without their required env vars are skipped.

```cmd
set MODELGATE_LIVE_PROVIDER_VALIDATION=true
set MODELGATE_LIVE_PROVIDER_FILTER=openai
set OPENAI_API_KEY=sk-...
cmd /c --% mvn -q -Dtest=ProviderLiveValidationTest test
```

Run a single provider by setting `MODELGATE_LIVE_PROVIDER_FILTER` to that provider id and supplying that provider's required env vars. The test starts an in-process ModelGate instance and sends requests through the public gateway API, so it validates endpoint mapping, auth/header behavior, request transforms, provider response handling, and gateway response normalization together.

The same command is safe in normal CI without secrets: scenarios are skipped unless `MODELGATE_LIVE_PROVIDER_VALIDATION=true`, `MODELGATE_LIVE_PROVIDER_FILTER` is set, and that scenario's required env vars are present.

The filter is case-insensitive and matches scenario provider id, scenario name, or tags. Comma-separated values match any term. Use `all` or `*` only when you intentionally want every scenario with available secrets to call upstream providers.

```cmd
set MODELGATE_LIVE_PROVIDER_VALIDATION=true
set MODELGATE_LIVE_PROVIDER_FILTER=openai
set OPENAI_API_KEY=sk-...
cmd /c --% mvn -q -Dtest=ProviderLiveValidationTest test
```

Useful filters include `chat`, `embeddings`, `images`, `count-tokens`, `openai-compatible`, `aws`, `bedrock`, `vertex-ai`, `voyage`, and `jina`.

Failure messages redact common secret fields and header names such as `api_key`, `authorization`, `x-api-key`, `aws_secret_access_key`, and session/access tokens before response bodies are included in assertion output.

## Base URL Overrides

Live validation honors provider base URL overrides. Each scenario has a `MODELGATE_LIVE_*_BASE_URL` env var; when set, the harness writes that value to `custom_host` in `x-modelgate-config`. That exercises the same base URL override path used by real requests.

Example:

```cmd
set MODELGATE_LIVE_PROVIDER_VALIDATION=true
set MODELGATE_LIVE_PROVIDER_FILTER=openai
set OPENAI_API_KEY=sk-...
set MODELGATE_LIVE_OPENAI_BASE_URL=https://api.openai.com/v1
cmd /c --% mvn -q -Dtest=ProviderLiveValidationTest test
```

Custom hosts are still subject to ModelGate SSRF validation. For private test endpoints or local record/replay servers, add the host to `MODELGATE_TRUSTED_CUSTOM_HOSTS`.

You can also build an equivalent sample request from the live manifest without invoking JUnit:

```cmd
set OPENAI_API_KEY=sk-...
set MODELGATE_LIVE_OPENAI_BASE_URL=https://api.openai.com/v1
python scripts\modelgate_request.py --live-scenario openai --dry-run
```

`--live-scenario` builds one request. Prefer a provider id such as `openai` or an exact scenario name such as `openai chat live smoke`. Broad tags such as `chat` can match multiple scenarios; the Python helper reports that ambiguity instead of choosing one. The generated request uses the scenario endpoint, request body, provider config, API key env var, and optional `MODELGATE_LIVE_*_BASE_URL` value as `custom_host`.

## Required Env Vars

| Provider | Required env vars |
| --- | --- |
| OpenAI | `OPENAI_API_KEY` |
| Anthropic | `ANTHROPIC_API_KEY` |
| Bedrock | `BEDROCK_API_KEY` |
| Vertex AI | `VERTEX_AI_BEARER_TOKEN`, `VERTEX_AI_PROJECT_ID` |
| Cohere | `COHERE_API_KEY` |
| Mistral | `MISTRAL_API_KEY` |
| Workers AI | `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` |
| Sagemaker | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `SAGEMAKER_ENDPOINT_NAME` |
| Stability | `STABILITY_API_KEY` |
| Fireworks | `FIREWORKS_API_KEY` |
| HuggingFace | `HUGGINGFACE_API_KEY` |
| DeepSeek | `DEEPSEEK_API_KEY` |
| OpenRouter | `OPENROUTER_API_KEY` |
| Perplexity | `PERPLEXITY_API_KEY` |
| Voyage | `VOYAGE_API_KEY` |
| Jina | `JINA_API_KEY` |

## Optional Model/Region Overrides

Most scenarios have conservative defaults. Override them when your account has a different model allowlist:

| Variable | Purpose |
| --- | --- |
| `MODELGATE_LIVE_OPENAI_MODEL` | OpenAI chat model |
| `MODELGATE_LIVE_ANTHROPIC_MODEL` | Anthropic chat model |
| `MODELGATE_LIVE_BEDROCK_MODEL` | Bedrock count-token model |
| `MODELGATE_LIVE_BEDROCK_REGION` | Bedrock runtime region |
| `MODELGATE_LIVE_VERTEX_MODEL` | Vertex AI model id |
| `MODELGATE_LIVE_VERTEX_REGION` | Vertex AI region |
| `MODELGATE_LIVE_COHERE_MODEL` | Cohere chat model |
| `MODELGATE_LIVE_MISTRAL_MODEL` | Mistral chat model |
| `MODELGATE_LIVE_WORKERS_AI_MODEL` | Workers AI model id |
| `MODELGATE_LIVE_SAGEMAKER_REGION` | Sagemaker runtime region |
| `MODELGATE_LIVE_STABILITY_MODEL` | Stability image model |
| `MODELGATE_LIVE_FIREWORKS_MODEL` | Fireworks chat model |
| `MODELGATE_LIVE_HUGGINGFACE_MODEL` | HuggingFace model id |
| `MODELGATE_LIVE_DEEPSEEK_MODEL` | DeepSeek chat model |
| `MODELGATE_LIVE_OPENROUTER_MODEL` | OpenRouter routed model |
| `MODELGATE_LIVE_PERPLEXITY_MODEL` | Perplexity chat model |
| `MODELGATE_LIVE_VOYAGE_MODEL` | Voyage embeddings model |
| `MODELGATE_LIVE_JINA_MODEL` | Jina embeddings model |

## Optional Base URL Overrides

| Variable | Purpose |
| --- | --- |
| `MODELGATE_LIVE_OPENAI_BASE_URL` | OpenAI-compatible base URL |
| `MODELGATE_LIVE_ANTHROPIC_BASE_URL` | Anthropic base URL |
| `MODELGATE_LIVE_BEDROCK_BASE_URL` | Bedrock runtime base URL |
| `MODELGATE_LIVE_VERTEX_BASE_URL` | Vertex AI base URL |
| `MODELGATE_LIVE_COHERE_BASE_URL` | Cohere base URL |
| `MODELGATE_LIVE_MISTRAL_BASE_URL` | Mistral base URL |
| `MODELGATE_LIVE_WORKERS_AI_BASE_URL` | Workers AI account run base URL |
| `MODELGATE_LIVE_SAGEMAKER_BASE_URL` | Sagemaker runtime base URL |
| `MODELGATE_LIVE_STABILITY_BASE_URL` | Stability base URL |
| `MODELGATE_LIVE_FIREWORKS_BASE_URL` | Fireworks base URL |
| `MODELGATE_LIVE_HUGGINGFACE_BASE_URL` | HuggingFace inference base URL |
| `MODELGATE_LIVE_DEEPSEEK_BASE_URL` | DeepSeek base URL |
| `MODELGATE_LIVE_OPENROUTER_BASE_URL` | OpenRouter base URL |
| `MODELGATE_LIVE_PERPLEXITY_BASE_URL` | Perplexity base URL |
| `MODELGATE_LIVE_VOYAGE_BASE_URL` | Voyage base URL |
| `MODELGATE_LIVE_JINA_BASE_URL` | Jina base URL |

## Interpreting Results

Passing mocked contracts means Java transforms match expected wire contracts.

Passing live validation means the current upstream API, credentials, model access, auth headers, and response shape also work. Live validation can still fail because of provider-side quota, region/model availability, account permissions, or transient upstream failures; treat failures as deployment blockers only after checking the provider error body.
