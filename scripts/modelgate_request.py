#!/usr/bin/env python3
"""Send test traffic through a local ModelGate gateway."""

from __future__ import annotations

import argparse
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


DEFAULT_BASE_URL = "http://localhost:8787"
DEFAULT_ENDPOINT = "/v1/chat/completions"
DEFAULT_CLIENT_KEY = "modelgate-smoke-client-key"
MODELS_ENDPOINT = "/v1/models"
DEFAULT_LIVE_MANIFEST = Path(__file__).resolve().parents[1] / "src" / "test" / "resources" / "provider-contracts" / "live-validation.json"
DEFAULT_VALIDATION_MATRIX = (
    Path(__file__).resolve().parents[1]
    / "src"
    / "test"
    / "resources"
    / "provider-contracts"
    / "provider-validation-matrix.json"
)
PRESET_MODELS = {
    "openai": "gpt-4o-mini",
    "groq": "llama-3.3-70b-versatile",
    "gemini": "gemini-1.5-flash",
    "anthropic": "claude-3-5-haiku-20241022",
    "nvidia-nim": "meta/llama-3.1-8b-instruct",
    "sambanova": "Meta-Llama-3.1-8B-Instruct",
    "cerebras": "llama3.1-8b",
    "inception": "mercury-2",
}


@dataclass(frozen=True)
class BuiltRequest:
    url: str
    method: str
    headers: dict[str, str]
    body: bytes | None


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.validation_check_ready:
        try:
            return validation_check_ready(
                Path(args.validation_matrix),
                Path(args.live_manifest),
                args.validation_check_ready,
            )
        except ValueError as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 2

    if args.validation_report:
        try:
            report = validation_report(
                Path(args.validation_matrix),
                Path(args.live_manifest),
                provider_filter=args.provider,
            )
        except ValueError as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 2
        if report["drift"]:
            print("error: provider validation drift detected: " + "; ".join(report["drift"]), file=sys.stderr)
            return 2
        print_validation_report(report, args.format)
        return 0

    gateway_process: subprocess.Popen[bytes] | None = None
    try:
        if args.start_gateway and not args.dry_run:
            gateway_process, args.base_url = start_gateway(args)
        request = build_request(args)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    try:
        if args.dry_run:
            print(json.dumps(dry_run_payload(request), indent=2, sort_keys=True))
            return 0

        return send_request(request, timeout=args.timeout)
    finally:
        if gateway_process is not None:
            stop_gateway(gateway_process)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Send configurable sample requests through ModelGate.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="ModelGate base URL")
    parser.add_argument("--endpoint", help="Gateway endpoint path")
    parser.add_argument("--method", default="POST", help="HTTP method")
    parser.add_argument("--models", action="store_true", help="List provider models")
    parser.add_argument(
        "--preset",
        choices=tuple(PRESET_MODELS),
        help="Provider preset that supplies provider and default model",
    )
    parser.add_argument("--provider", help="Provider name, e.g. openai, groq, gemini")
    parser.add_argument("--model", help="Model name to put in the request body")
    parser.add_argument("--custom-host", help="Provider base URL to put in x-modelgate-config")
    parser.add_argument("--key", help="Provider API key")
    parser.add_argument("--key-env", help="Environment variable containing provider API key")
    parser.add_argument(
        "--sdk-style",
        action="store_true",
        help="Send headers shaped like an OpenAI SDK client with default headers",
    )
    parser.add_argument("--client-key", help="Gateway client key for the Authorization bearer header")
    parser.add_argument("--client-key-env", help="Environment variable containing the gateway client key")
    parser.add_argument("--organization", help="OpenAI-Organization header value")
    parser.add_argument("--project", help="OpenAI-Project header value")
    parser.add_argument("--beta", action="append", default=[], help="OpenAI-Beta header value; repeatable")
    parser.add_argument("--config", help="Gateway config JSON, or @path/to/config.json")
    parser.add_argument("--data", help="Request body JSON, or @path/to/body.json")
    parser.add_argument(
        "--live-scenario",
        help="Build one live validation scenario request from a provider id or exact scenario name",
    )
    parser.add_argument(
        "--live-manifest",
        default=str(DEFAULT_LIVE_MANIFEST),
        help="Path to live-validation.json used by --live-scenario",
    )
    parser.add_argument(
        "--validation-report",
        action="store_true",
        help="Offline provider validation matrix report; reads local JSON and never calls the network",
    )
    parser.add_argument(
        "--validation-check-ready",
        metavar="PROVIDER|all",
        help=(
            "Offline production readiness gate for one provider or all providers; "
            "returns 0 only when selected rows are production_ready"
        ),
    )
    parser.add_argument(
        "--validation-matrix",
        default=str(DEFAULT_VALIDATION_MATRIX),
        help="Path to provider-validation-matrix.json used by validation report/check commands",
    )
    parser.add_argument(
        "--format",
        choices=("text", "json"),
        default="text",
        help="Output format for --validation-report",
    )
    parser.add_argument(
        "--message",
        default="Say hello from ModelGate in one sentence.",
        help="User message used when --data is not supplied",
    )
    parser.add_argument(
        "--max-tokens",
        type=int,
        help="max_tokens value used when --data is not supplied",
    )
    parser.add_argument(
        "--header",
        action="append",
        default=[],
        metavar="NAME: VALUE",
        help="Extra request header; repeatable",
    )
    parser.add_argument("--timeout", type=float, default=60.0, help="Request timeout in seconds")
    parser.add_argument("--start-gateway", action="store_true", help="Start the Java gateway for this smoke run")
    parser.add_argument(
        "--gateway-port",
        type=int,
        default=0,
        help="Port for --start-gateway; 0 chooses an available local port",
    )
    parser.add_argument(
        "--gateway-cwd",
        default=str(Path(__file__).resolve().parents[1]),
        help="Working directory for the Java gateway Maven command",
    )
    parser.add_argument(
        "--gateway-ready-timeout",
        type=float,
        default=30.0,
        help="Seconds to wait for a started gateway to report ready",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print request instead of sending it")
    args = parser.parse_args(argv)
    if args.models:
        args.method = "GET"
        if args.endpoint is None:
            args.endpoint = MODELS_ENDPOINT
    elif args.endpoint is None:
        args.endpoint = DEFAULT_ENDPOINT
    return args


def validation_check_ready(matrix_path: Path, manifest_path: Path, selector: str) -> int:
    normalized_selector = selector.strip().lower()
    if not normalized_selector:
        raise ValueError("validation readiness selector must not be blank")
    provider_filter = None if normalized_selector == "all" else selector
    report = validation_report(matrix_path, manifest_path, provider_filter=provider_filter)
    if report["drift"]:
        print("error: provider validation drift detected: " + "; ".join(report["drift"]), file=sys.stderr)
        return 2

    providers = report["providers"]
    not_ready = [
        provider
        for provider in providers
        if provider.get("production_ready") is not True
    ]
    if not_ready:
        if normalized_selector == "all":
            sample = ", ".join(str(provider["provider"]) for provider in not_ready[:10])
            suffix = "" if len(not_ready) <= 10 else f", +{len(not_ready) - 10} more"
            print(
                f"{len(not_ready)} provider(s) are not production_ready: {sample}{suffix}",
                file=sys.stderr,
            )
        else:
            provider = not_ready[0]
            print(
                f"{provider['provider']} production_ready=false "
                f"validation_tier={provider.get('validation_tier')}",
                file=sys.stderr,
            )
        return 1

    if normalized_selector == "all":
        print(f"all {len(providers)} provider(s) production_ready=true")
    else:
        provider = providers[0]
        print(f"{provider['provider']} production_ready=true validation_tier={provider.get('validation_tier')}")
    return 0


def validation_report(
    matrix_path: Path,
    manifest_path: Path,
    *,
    provider_filter: str | None = None,
) -> dict[str, Any]:
    matrix = read_validation_matrix(matrix_path)
    manifest = read_live_manifest(manifest_path)
    scenarios_by_provider = live_scenarios_by_provider(manifest)
    matrix_providers = matrix["providers"]
    matrix_by_provider = {str(provider["provider"]).lower(): provider for provider in matrix_providers}
    drift = validation_matrix_drift(matrix_by_provider, scenarios_by_provider)
    providers = [
        validation_provider_row(provider, scenarios_by_provider.get(str(provider["provider"]).lower(), []))
        for provider in matrix_providers
    ]

    if provider_filter:
        normalized_provider = provider_filter.strip().lower()
        providers = [
            provider
            for provider in providers
            if str(provider.get("provider", "")).lower() == normalized_provider
        ]
        if not providers:
            raise ValueError(f"provider {provider_filter!r} was not found in {matrix_path}")

    return {
        "summary": validation_summary(providers),
        "providers": providers,
        "drift": drift,
    }


def read_validation_matrix(path: Path) -> dict[str, Any]:
    matrix = read_json_file(path, "provider validation matrix")
    providers = matrix.get("providers")
    if not isinstance(providers, list):
        raise ValueError(f"provider validation matrix {path} must contain a providers array")
    for index, provider in enumerate(providers):
        if not isinstance(provider, dict):
            raise ValueError(f"provider validation matrix entry {index} must be an object")
        if not has_text(str(provider.get("provider", ""))):
            raise ValueError(f"provider validation matrix entry {index} is missing provider")
        if not has_text(str(provider.get("validation_tier", ""))):
            raise ValueError(f"provider validation matrix entry {index} is missing validation_tier")
    return matrix


def read_live_manifest(path: Path) -> dict[str, Any]:
    manifest = read_json_file(path, "live validation manifest")
    scenarios = manifest.get("scenarios")
    if not isinstance(scenarios, list):
        raise ValueError(f"live validation manifest {path} must contain a scenarios array")
    return manifest


def read_json_file(path: Path, label: str) -> dict[str, Any]:
    try:
        parsed = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ValueError(f"cannot read {label} {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid {label} JSON: {exc.msg}") from exc
    if not isinstance(parsed, dict):
        raise ValueError(f"{label} JSON must be an object")
    return parsed


def live_scenarios_by_provider(manifest: dict[str, Any]) -> dict[str, list[dict[str, Any]]]:
    scenarios_by_provider: dict[str, list[dict[str, Any]]] = {}
    for index, scenario in enumerate(manifest.get("scenarios", [])):
        if not isinstance(scenario, dict):
            raise ValueError(f"live validation scenario {index} must be an object")
        provider = str(scenario.get("provider", "")).strip().lower()
        if not provider:
            raise ValueError(f"live validation scenario {index} is missing provider")
        scenarios_by_provider.setdefault(provider, []).append(scenario)
    return scenarios_by_provider


def validation_matrix_drift(
    matrix_by_provider: dict[str, dict[str, Any]],
    scenarios_by_provider: dict[str, list[dict[str, Any]]],
) -> list[str]:
    drift: list[str] = []
    for provider in sorted(scenarios_by_provider):
        matrix_entry = matrix_by_provider.get(provider)
        if matrix_entry is None:
            drift.append(f"{provider} has live scenarios but is missing from the validation matrix")
            continue
        if matrix_entry.get("live_validation") is not True:
            drift.append(f"{provider} has live scenarios but live_validation is not true")
        if matrix_entry.get("validation_tier") != "live-smoke":
            drift.append(f"{provider} has live scenarios but validation_tier is {matrix_entry.get('validation_tier')!r}")
    for provider, matrix_entry in sorted(matrix_by_provider.items()):
        if matrix_entry.get("live_validation") is True and provider not in scenarios_by_provider:
            drift.append(f"{provider} is marked live_validation but has no live scenario")
    return drift


def validation_provider_row(provider: dict[str, Any], scenarios: list[dict[str, Any]]) -> dict[str, Any]:
    row = dict(provider)
    row["live_scenarios"] = [str(scenario.get("name", "")) for scenario in scenarios if has_text(str(scenario.get("name", "")))]
    row["required_env"] = unique_values(
        str(env_name)
        for scenario in scenarios
        for env_name in scenario.get("required_env", [])
        if has_text(str(env_name))
    )
    row["base_url_env"] = unique_values(
        str(scenario.get("base_url_env"))
        for scenario in scenarios
        if has_text(str(scenario.get("base_url_env", "")))
    )
    return row


def unique_values(values: Any) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value not in seen:
            seen.add(value)
            result.append(value)
    return result


def validation_summary(providers: list[dict[str, Any]]) -> dict[str, Any]:
    tiers: dict[str, int] = {}
    production_ready = 0
    live_validation = 0
    recorded_contract = 0
    mocked_contract = 0
    for provider in providers:
        tier = str(provider.get("validation_tier", "unknown"))
        tiers[tier] = tiers.get(tier, 0) + 1
        if provider.get("production_ready") is True:
            production_ready += 1
        if provider.get("live_validation") is True:
            live_validation += 1
        if provider.get("recorded_contract") is True:
            recorded_contract += 1
        if provider.get("mocked_contract") is True:
            mocked_contract += 1
    return {
        "total": len(providers),
        "tiers": dict(sorted(tiers.items())),
        "mocked_contract": mocked_contract,
        "recorded_contract": recorded_contract,
        "live_validation": live_validation,
        "production_ready": production_ready,
    }


def print_validation_report(report: dict[str, Any], output_format: str) -> None:
    if output_format == "json":
        print(json.dumps(report, indent=2, sort_keys=True))
        return
    summary = report["summary"]
    print(f"providers: {summary['total']}")
    print(f"production_ready: {summary['production_ready']}")
    print("tiers:")
    for tier, count in summary["tiers"].items():
        print(f"  {tier}: {count}")
    print()
    for provider in report["providers"]:
        scenario_text = ", ".join(provider["live_scenarios"]) if provider["live_scenarios"] else "-"
        print(
            f"{provider['provider']}: {provider['validation_tier']} "
            f"production_ready={str(provider.get('production_ready') is True).lower()} "
            f"live_scenarios={scenario_text}"
        )


def build_request(args: argparse.Namespace) -> BuiltRequest:
    if getattr(args, "live_scenario", None):
        apply_live_scenario(args)

    method = args.method.upper()
    url = join_url(args.base_url, args.endpoint)
    headers = {
        "content-type": "application/json",
    }
    headers.update(parse_headers(args.header))
    if getattr(args, "sdk_style", False):
        apply_openai_sdk_headers(headers, args)

    config = read_json_arg(args.config, "config") if args.config else {}
    preset = getattr(args, "preset", None)
    preset_model = PRESET_MODELS.get(preset) if preset else None
    provider = args.provider or preset or config.get("provider")
    model = args.model or preset_model
    api_key = resolve_key(args.key, args.key_env)
    if provider:
        config["provider"] = provider
    custom_host = getattr(args, "custom_host", None)
    if custom_host:
        config["custom_host"] = custom_host
    if api_key:
        config["api_key"] = api_key
    if config:
        headers["x-modelgate-config"] = json.dumps(config, separators=(",", ":"))

    body_obj = (
        read_json_arg(args.data, "data")
        if args.data
        else default_body(model, args.message, getattr(args, "max_tokens", None))
    )
    body = None if method == "GET" else json.dumps(body_obj, separators=(",", ":")).encode("utf-8")

    return BuiltRequest(url=url, method=method, headers=headers, body=body)


def apply_live_scenario(args: argparse.Namespace) -> None:
    scenario = find_live_scenario(Path(args.live_manifest), args.live_scenario)
    missing = [
        env_name
        for env_name in scenario.get("required_env", [])
        if not has_text(os.environ.get(env_name))
    ]
    if missing:
        raise ValueError(
            f"missing required environment for live scenario {scenario.get('name', args.live_scenario)!r}: "
            + ", ".join(missing)
        )

    args.endpoint = resolve_live_value(scenario.get("endpoint", args.endpoint))
    args.method = "POST"
    args.provider = scenario.get("provider", args.provider)
    args.config = json.dumps(live_scenario_config(scenario), separators=(",", ":"))
    args.data = json.dumps(resolve_live_value(scenario.get("request_body", {})), separators=(",", ":"))


def find_live_scenario(manifest_path: Path, selector: str) -> dict[str, Any]:
    normalized_selector = selector.strip()
    if not normalized_selector:
        raise ValueError("live scenario selector must not be blank")
    if normalized_selector in {"all", "*"} or "," in normalized_selector:
        raise ValueError("--live-scenario selects one scenario; use a provider id or exact scenario name")

    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ValueError(f"cannot read live manifest {manifest_path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid live manifest JSON: {exc.msg}") from exc

    normalized_selector = normalized_selector.lower()
    scenarios = manifest.get("scenarios", [])
    matches = [
        scenario
        for scenario in scenarios
        if live_scenario_matches(scenario, normalized_selector, exact=True)
    ]
    if not matches:
        matches = [
            scenario
            for scenario in scenarios
            if live_scenario_matches(scenario, normalized_selector, exact=False)
        ]
    if not matches:
        raise ValueError(f"live scenario {selector!r} was not found in {manifest_path}")
    if len(matches) > 1:
        names = ", ".join(str(scenario.get("name", "<unnamed>")) for scenario in matches[:5])
        raise ValueError(
            f"live scenario selector {selector!r} matched multiple scenarios: {names}. "
            "Use a provider id or exact scenario name."
        )
    return matches[0]


def live_scenario_matches(scenario: dict[str, Any], selector: str, *, exact: bool) -> bool:
    values = [
        str(scenario.get("provider", "")),
        str(scenario.get("name", "")),
        *(str(tag) for tag in scenario.get("tags", [])),
    ]
    if exact:
        return any(selector == value.lower() for value in values)
    return any(selector in value.lower() for value in values)


def live_scenario_config(scenario: dict[str, Any]) -> dict[str, Any]:
    config: dict[str, Any] = {"provider": scenario.get("provider")}
    api_key_env = scenario.get("api_key_env")
    if api_key_env:
        config["api_key"] = os.environ.get(api_key_env, "")
    base_url_env = scenario.get("base_url_env")
    if base_url_env and has_text(os.environ.get(base_url_env)):
        config["custom_host"] = os.environ[base_url_env].strip()
    scenario_config = scenario.get("config")
    if isinstance(scenario_config, dict):
        config.update(resolve_live_value(scenario_config))
    return config


def resolve_live_value(value: Any) -> Any:
    if isinstance(value, str):
        return resolve_live_placeholders(value)
    if isinstance(value, list):
        return [resolve_live_value(item) for item in value]
    if isinstance(value, dict):
        return {key: resolve_live_value(item) for key, item in value.items()}
    return value


def resolve_live_placeholders(value: str) -> str:
    import re

    def replace(match: re.Match[str]) -> str:
        env_name = match.group(1)
        fallback = match.group(3)
        env_value = os.environ.get(env_name)
        return env_value if has_text(env_value) else (fallback or "")

    return re.sub(r"\$\{([A-Z0-9_]+)(:-([^}]*))?}", replace, value)


def has_text(value: str | None) -> bool:
    return value is not None and bool(value.strip())


def apply_openai_sdk_headers(headers: dict[str, str], args: argparse.Namespace) -> None:
    client_key = resolve_client_key(args)
    headers.setdefault("authorization", f"Bearer {client_key}")
    organization = getattr(args, "organization", None)
    if organization:
        headers["openai-organization"] = organization
    project = getattr(args, "project", None)
    if project:
        headers["openai-project"] = project
    beta_values = getattr(args, "beta", None) or []
    if beta_values:
        headers["openai-beta"] = ",".join(beta_values)


def resolve_client_key(args: argparse.Namespace) -> str:
    client_key = getattr(args, "client_key", None)
    client_key_env = getattr(args, "client_key_env", None)
    if client_key or client_key_env:
        resolved = resolve_key(client_key, client_key_env)
        if resolved:
            return resolved
    return DEFAULT_CLIENT_KEY


def start_gateway(args: argparse.Namespace) -> tuple[subprocess.Popen[bytes], str]:
    port = args.gateway_port if args.gateway_port else find_open_port()
    base_url = f"http://localhost:{port}"
    process = subprocess.Popen(gateway_command(args, port), cwd=args.gateway_cwd)
    try:
        wait_for_gateway(base_url, process, args.gateway_ready_timeout)
    except ValueError:
        stop_gateway(process)
        raise
    return process, base_url


def gateway_command(args: argparse.Namespace, port: int) -> list[str]:
    return ["mvn", "-q", "-DskipTests", "exec:java", f"-Dexec.args=--port {port}"]


def find_open_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server_socket:
        server_socket.bind(("localhost", 0))
        return int(server_socket.getsockname()[1])


def wait_for_gateway(
    base_url: str,
    process: subprocess.Popen[bytes],
    timeout_seconds: float,
) -> None:
    deadline = time.monotonic() + timeout_seconds
    ready_url = join_url(base_url, "/ready")
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise ValueError(f"gateway process exited with code {process.returncode}")
        try:
            with urllib.request.urlopen(ready_url, timeout=1.0) as response:
                if 200 <= response.status < 300:
                    return
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            last_error = exc
        time.sleep(0.2)
    detail = f": {last_error}" if last_error else ""
    raise ValueError(f"gateway did not become ready at {ready_url}{detail}")


def stop_gateway(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def send_request(request: BuiltRequest, timeout: float) -> int:
    urllib_request = urllib.request.Request(
        request.url,
        data=request.body,
        headers=request.headers,
        method=request.method,
    )
    try:
        with urllib.request.urlopen(urllib_request, timeout=timeout) as response:
            print_response(response.status, dict(response.headers.items()), response.read())
            return 0 if 200 <= response.status < 300 else 1
    except urllib.error.HTTPError as exc:
        print_response(exc.code, dict(exc.headers.items()), exc.read())
        return 1
    except urllib.error.URLError as exc:
        print(f"request failed: {exc}", file=sys.stderr)
        return 1


def print_response(status: int, headers: dict[str, str], body: bytes) -> None:
    print(f"HTTP {status}")
    content_type = first_header(headers, "content-type")
    if content_type:
        print(f"content-type: {content_type}")
    print()
    text = body.decode("utf-8", errors="replace")
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        print(text)
        return
    print(json.dumps(parsed, indent=2, sort_keys=True))


def dry_run_payload(request: BuiltRequest) -> dict[str, Any]:
    return {
        "url": request.url,
        "method": request.method,
        "headers": redact_headers(request.headers),
        "body": None if request.body is None else json.loads(request.body.decode("utf-8")),
    }


def redact_headers(headers: dict[str, str]) -> dict[str, str]:
    redacted = dict(headers)
    config_header_name = first_matching_header(redacted, "x-modelgate-config")
    if config_header_name:
        config_header = redacted[config_header_name]
        config = json.loads(config_header)
        redact_config_secrets(config)
        redacted[config_header_name] = json.dumps(config, separators=(",", ":"))
    for header_name, value in list(redacted.items()):
        if header_name.lower() in {"authorization", "api-key", "x-api-key"}:
            redacted[header_name] = redact_secret(value)
    return redacted


def first_matching_header(headers: dict[str, str], name: str) -> str | None:
    for header_name in headers:
        if header_name.lower() == name.lower():
            return header_name
    return None


def redact_config_secrets(value: Any) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            if is_secret_config_key(key):
                value[key] = redact_secret(str(item))
            else:
                redact_config_secrets(item)
        return
    if isinstance(value, list):
        for item in value:
            redact_config_secrets(item)


def is_secret_config_key(key: str) -> bool:
    normalized = key.replace("_", "").replace("-", "").lower()
    return (
        normalized.endswith("apikey")
        or normalized.endswith("adtoken")
        or normalized in {"authorization", "bearertoken", "token", "secret"}
    )


def parse_headers(values: list[str]) -> dict[str, str]:
    headers: dict[str, str] = {}
    for value in values:
        if ":" not in value:
            raise ValueError(f"invalid header {value!r}; expected NAME: VALUE")
        name, header_value = value.split(":", 1)
        if not name.strip():
            raise ValueError(f"invalid header {value!r}; header name is blank")
        headers[name.strip().lower()] = header_value.strip()
    return headers


def read_json_arg(value: str, label: str) -> dict[str, Any]:
    raw = read_arg(value)
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid {label} JSON: {exc.msg}") from exc
    if not isinstance(parsed, dict):
        raise ValueError(f"{label} JSON must be an object")
    return parsed


def read_arg(value: str) -> str:
    if value.startswith("@"):
        path = Path(value[1:])
        try:
            return path.read_text(encoding="utf-8")
        except OSError as exc:
            raise ValueError(f"cannot read {path}: {exc}") from exc
    return value


def resolve_key(key: str | None, key_env: str | None) -> str | None:
    if key:
        return key
    if not key_env:
        return None
    value = os.environ.get(key_env)
    if not value:
        raise ValueError(f"environment variable {key_env} is not set")
    return value


def default_body(model: str | None, message: str, max_tokens: int | None = None) -> dict[str, Any]:
    body: dict[str, Any] = {
        "messages": [
            {
                "role": "user",
                "content": message,
            }
        ]
    }
    if model:
        body["model"] = model
    if max_tokens is not None:
        body["max_tokens"] = max_tokens
    return body


def join_url(base_url: str, endpoint: str) -> str:
    normalized_base = base_url[:-1] if base_url.endswith("/") else base_url
    normalized_endpoint = endpoint if endpoint.startswith("/") else f"/{endpoint}"
    return f"{normalized_base}{normalized_endpoint}"


def first_header(headers: dict[str, str], name: str) -> str | None:
    for header_name, value in headers.items():
        if header_name.lower() == name.lower():
            return value
    return None


def redact_secret(value: str) -> str:
    if len(value) <= 8:
        return "***"
    return f"{value[:4]}...{value[-4:]}"


if __name__ == "__main__":
    raise SystemExit(main())
