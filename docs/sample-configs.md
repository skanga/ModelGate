# Sample Gateway Configs

Sample request configs live in [examples/configs](../examples/configs). They are templates: replace `${...}` placeholders before sending them in `x-modelgate-config` or `x-portkey-config`.

## Files

| File | Purpose |
| --- | --- |
| [openai-simple.json](../examples/configs/openai-simple.json) | Single OpenAI-compatible target with timeout and retry policy. |
| [fallback-loadbalance.json](../examples/configs/fallback-loadbalance.json) | Two-target fallback routing with simple in-memory cache. |
| [guardrails-evals.json](../examples/configs/guardrails-evals.json) | Input/output guardrail example with metadata suitable for eval grouping. |
| [redis-cache.json](../examples/configs/redis-cache.json) | Request config that uses process-wired Redis cache. |
| [sagemaker-sigv4.json](../examples/configs/sagemaker-sigv4.json) | Sagemaker invocation config using static AWS SigV4 credentials. |
| [otel.env.example](../examples/configs/otel.env.example) | Process-level env example for OTLP export and concurrency. |

## Header Use

ModelGate reads config from `x-modelgate-config` first and keeps `x-portkey-config` as a compatibility alias. For simple providers, `Authorization: Bearer <provider-key>` can also supply the API key when the config contains only the provider.

Minimum request:

```cmd
curl.exe -s http://localhost:8787/v1/chat/completions ^
  -H "content-type: application/json" ^
  -H "x-modelgate-config: {\"provider\":\"openai\",\"api_key\":\"replace-me\"}" ^
  -d "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"Say hello\"}]}"
```

For larger configs, use `scripts/modelgate_request.py` so the JSON stays in files instead of shell-escaped headers.

## Config Notes

- `retry.on_status_codes` controls both direct retries and fallback status decisions.
- `cache.mode` supports `simple` and `redis`. Redis mode requires `MODELGATE_REDIS_URL` at process startup.
- `custom_host` is blocked unless it passes SSRF validation. Internal hosts should be added to `MODELGATE_TRUSTED_CUSTOM_HOSTS`.
- Sagemaker static signing activates when both `aws_access_key_id` and `aws_secret_access_key` are present.
- Guardrail `deny: true` turns failed plugin checks into gateway denial responses. Without `deny`, plugin results are recorded and propagated as hook metadata.
- `default_input_guardrails` and `default_output_guardrails` are appended after explicit guardrails. They can also be supplied as JSON arrays in `x-modelgate-default-input-guardrails` and `x-modelgate-default-output-guardrails`; `x-portkey-*` aliases remain supported.
- Output guardrails run for ordinary and cached JSON responses. Streaming requests with output guardrails are rejected because the gateway cannot inspect a complete streamed response before forwarding chunks.
