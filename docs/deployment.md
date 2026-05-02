# ModelGate Deployment Notes

## Build Artifacts

Run the full verification suite:

```cmd
cmd /c --% mvn -q verify
```

Build the executable jar without rerunning tests:

```cmd
cmd /c --% mvn -q -DskipTests package
```

The production artifact is:

```text
target/modelgate-0.1.jar
```

Run it locally:

```cmd
java -jar target\modelgate-0.1.jar --port 8787
```

Health checks:

```cmd
curl.exe -s http://localhost:8787/health
curl.exe -s http://localhost:8787/ready
curl.exe -s http://localhost:8787/metrics
```

## Docker

Build the image:

```cmd
docker build -t modelgate:0.1 .
```

Run the image:

```cmd
docker run --rm -p 8787:8787 --env-file examples\configs\otel.env.example modelgate:0.1
```

The image runs as a non-root `modelgate` user, exposes `8787`, and uses `/health` for the container healthcheck.

## GitHub Release Workflow

The checked-in release workflow runs on tags matching `v*` or manual dispatch with a required `release_tag` input.

It performs the same production gates as the local deployment path:

- `mvn -q verify`
- `mvn -q -DskipTests package`
- shaded jar health/readiness smoke test on port `18788`
- Docker image build smoke tagged as `modelgate:${{ github.ref_name }}`
- `target/modelgate-0.1.jar` uploaded as both a workflow artifact and GitHub release asset

Create a release by pushing a version tag:

```cmd
git tag v0.1.0
git push origin v0.1.0
```

## Kubernetes Baseline

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: modelgate
spec:
  replicas: 2
  selector:
    matchLabels:
      app: modelgate
  template:
    metadata:
      labels:
        app: modelgate
    spec:
      containers:
        - name: modelgate
          image: modelgate:0.1
          ports:
            - containerPort: 8787
          env:
            - name: PORT
              value: "8787"
            - name: MODELGATE_PROVIDER_MAX_CONCURRENT_REQUESTS
              value: "256"
            - name: MODELGATE_PLUGIN_MAX_CONCURRENT_REQUESTS
              value: "256"
            - name: OTEL_SERVICE_NAME
              value: modelgate
            - name: OTEL_EXPORTER
              value: otlp
            - name: OTEL_EXPORTER_OTLP_ENDPOINT
              value: http://otel-collector:4318
          readinessProbe:
            httpGet:
              path: /ready
              port: 8787
            periodSeconds: 10
            failureThreshold: 2
          livenessProbe:
            httpGet:
              path: /health
              port: 8787
            periodSeconds: 30
            failureThreshold: 3
          resources:
            requests:
              cpu: 500m
              memory: 512Mi
            limits:
              cpu: "2"
              memory: 2Gi
```

Expose with a normal `Service` and terminate TLS at the ingress or load balancer. Keep provider API keys in your client-side gateway config delivery path or secret manager; do not bake them into the image.

## Production Defaults

- Start with `MODELGATE_PROVIDER_MAX_CONCURRENT_REQUESTS=256` and lower it if providers return throttling or if downstream latency increases under load.
- Start with `MODELGATE_PLUGIN_MAX_CONCURRENT_REQUESTS=256` and lower it if external guardrail evaluators return throttling or show queueing under load.
- Set `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` when running outside the Docker image.
- Set `MODELGATE_REDIS_URL=redis://...` when request configs use `cache.mode=redis`; leave it unset to keep only in-memory `simple` cache available.
- Use `/ready` for traffic admission and `/health` for process liveness.
- Scrape `/metrics` and export OTLP to your collector for traces, metrics, and logs.
- Keep `MODELGATE_TRUSTED_CUSTOM_HOSTS` empty unless a specific internal provider host is required.
- Prefer rolling or canary deploys. Gateway config is request-scoped, so old and new gateway versions can run side by side as long as header/config contracts remain backward compatible.

## Rollback

Rollback is artifact-based:

1. Keep the previous jar or image tag available.
2. Repoint the deployment to the previous image or jar.
3. Watch `/ready`, provider error rate, timeout rate, and p95 latency.
4. If telemetry export is degraded but provider traffic is healthy, set `OTEL_SDK_DISABLED=true` as a temporary mitigation.

## Smoke Test

With the gateway running on `localhost:8787`, send a mocked OpenAI-compatible request to a configured provider:

```cmd
python scripts\modelgate_request.py --base-url http://localhost:8787 --provider openai --model gpt-4o-mini --key-env OPENAI_API_KEY --message "Return a JSON object with an answer field."
```

For curl-based checks, use a file-backed config to avoid escaping large JSON headers in the shell:

```cmd
curl.exe -s http://localhost:8787/health
```
