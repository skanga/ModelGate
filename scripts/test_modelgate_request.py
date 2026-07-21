import argparse
import contextlib
import io
import json
import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import modelgate_request


def capture_parse_help(argv):
    stdout = io.StringIO()
    with contextlib.redirect_stdout(stdout):
        with contextlib.suppress(SystemExit):
            modelgate_request.parse_args(argv)
    return stdout.getvalue()


class ModelGateRequestTest(unittest.TestCase):
    def test_models_flag_builds_get_models_request_without_body_and_with_config(self):
        args = modelgate_request.parse_args([
            "--models",
            "--provider", "openai",
            "--key", "sk-test",
            "--config", '{"cache":{"mode":"simple"}}',
        ])

        request = modelgate_request.build_request(args)

        self.assertEqual("http://localhost:8787/v1/models", request.url)
        self.assertEqual("GET", request.method)
        self.assertIsNone(request.body)
        self.assertEqual(
            {"provider": "openai", "api_key": "sk-test", "cache": {"mode": "simple"}},
            json.loads(request.headers["x-modelgate-config"]),
        )

    def test_models_flag_allows_explicit_endpoint_override(self):
        args = modelgate_request.parse_args(["--models", "--endpoint", "/v1/provider/models"])

        request = modelgate_request.build_request(args)

        self.assertEqual("http://localhost:8787/v1/provider/models", request.url)
        self.assertEqual("GET", request.method)
        self.assertIsNone(request.body)

    def test_builds_default_chat_request_from_flags(self):
        request = modelgate_request.build_request(argparse.Namespace(
            base_url="http://localhost:8787",
            endpoint="/v1/chat/completions",
            method="POST",
            sdk_style=False,
            client_key=None,
            client_key_env=None,
            organization=None,
            project=None,
            beta=[],
            provider="groq",
            model="llama-3.3-70b-versatile",
            custom_host=None,
            key="sk-test",
            key_env=None,
            config=None,
            data=None,
            message="hello",
            header=[],
        ))

        self.assertEqual("http://localhost:8787/v1/chat/completions", request.url)
        self.assertEqual("POST", request.method)
        self.assertEqual(
            {"provider": "groq", "api_key": "sk-test"},
            json.loads(request.headers["x-modelgate-config"]),
        )
        self.assertEqual(
            {
                "model": "llama-3.3-70b-versatile",
                "messages": [{"role": "user", "content": "hello"}],
            },
            json.loads(request.body.decode("utf-8")),
        )

    def test_max_tokens_is_added_to_default_chat_body(self):
        args = modelgate_request.parse_args(["--max-tokens", "64"])

        request = modelgate_request.build_request(args)

        self.assertEqual(64, json.loads(request.body.decode("utf-8"))["max_tokens"])

    def test_max_tokens_does_not_modify_explicit_data_body(self):
        args = modelgate_request.parse_args([
            "--max-tokens", "64",
            "--data", '{"model":"claude-test","messages":[]}',
        ])

        request = modelgate_request.build_request(args)

        self.assertEqual(
            {"model": "claude-test", "messages": []},
            json.loads(request.body.decode("utf-8")),
        )

    def test_preset_fills_provider_and_default_model(self):
        args = modelgate_request.parse_args(["--preset", "groq"])

        request = modelgate_request.build_request(args)

        self.assertEqual(
            {"provider": "groq"},
            json.loads(request.headers["x-modelgate-config"]),
        )
        self.assertEqual(
            {
                "model": "llama-3.3-70b-versatile",
                "messages": [{"role": "user", "content": "Say hello from ModelGate in one sentence."}],
            },
            json.loads(request.body.decode("utf-8")),
        )

    def test_explicit_provider_and_model_override_preset_defaults(self):
        args = modelgate_request.parse_args([
            "--preset", "groq",
            "--provider", "openai",
            "--model", "gpt-4o-mini",
        ])

        request = modelgate_request.build_request(args)

        self.assertEqual(
            {"provider": "openai"},
            json.loads(request.headers["x-modelgate-config"]),
        )
        self.assertEqual(
            {
                "model": "gpt-4o-mini",
                "messages": [{"role": "user", "content": "Say hello from ModelGate in one sentence."}],
            },
            json.loads(request.body.decode("utf-8")),
        )

    def test_merges_config_data_and_extra_headers(self):
        fixture_dir = Path(__file__).resolve().parent / "fixtures"
        config_path = fixture_dir / "request_config.json"
        data_path = fixture_dir / "request_body.json"

        request = modelgate_request.build_request(argparse.Namespace(
            base_url="http://localhost:8787/",
            endpoint="v1/responses",
            method="POST",
            sdk_style=False,
            client_key=None,
            client_key_env=None,
            organization=None,
            project=None,
            beta=[],
            provider=None,
            model=None,
            custom_host=None,
            key="sk-test",
            key_env=None,
            config=f"@{config_path}",
            data=f"@{data_path}",
            message="ignored",
            header=["x-client-tenant: acme"],
        ))

        self.assertEqual("http://localhost:8787/v1/responses", request.url)
        self.assertEqual("acme", request.headers["x-client-tenant"])
        self.assertEqual(
            {"provider": "openai", "api_key": "sk-test", "cache": {"mode": "simple"}},
            json.loads(request.headers["x-modelgate-config"]),
        )
        self.assertEqual({"model": "gpt-4o-mini", "input": "hi"}, json.loads(request.body.decode("utf-8")))

    def test_reads_key_from_environment(self):
        os.environ["MODEL_GATE_TEST_KEY"] = "sk-env"
        self.addCleanup(os.environ.pop, "MODEL_GATE_TEST_KEY", None)

        request = modelgate_request.build_request(argparse.Namespace(
            base_url="http://localhost:8787",
            endpoint="/v1/chat/completions",
            method="POST",
            sdk_style=False,
            client_key=None,
            client_key_env=None,
            organization=None,
            project=None,
            beta=[],
            provider="inception",
            model="mercury-2",
            custom_host=None,
            key=None,
            key_env="MODEL_GATE_TEST_KEY",
            config=None,
            data=None,
            message="hello",
            header=[],
        ))

        self.assertEqual(
            {"provider": "inception", "api_key": "sk-env"},
            json.loads(request.headers["x-modelgate-config"]),
        )

    def test_dry_run_redacts_api_key(self):
        request = modelgate_request.build_request(argparse.Namespace(
            base_url="http://localhost:8787",
            endpoint="/v1/chat/completions",
            method="POST",
            sdk_style=False,
            client_key=None,
            client_key_env=None,
            organization=None,
            project=None,
            beta=[],
            provider="openai",
            model="gpt-4o-mini",
            custom_host=None,
            key="sk-1234567890",
            key_env=None,
            config=None,
            data=None,
            message="hello",
            header=[],
        ))

        payload = modelgate_request.dry_run_payload(request)

        self.assertIn("sk-1...7890", payload["headers"]["x-modelgate-config"])

    def test_dry_run_redacts_camel_case_api_key(self):
        args = modelgate_request.parse_args([
            "--config", '{"provider":"openai","apiKey":"sk-1234567890"}',
        ])

        payload = modelgate_request.dry_run_payload(modelgate_request.build_request(args))

        self.assertNotIn("sk-1234567890", payload["headers"]["x-modelgate-config"])
        self.assertIn("sk-1...7890", payload["headers"]["x-modelgate-config"])

    def test_dry_run_redacts_target_api_keys(self):
        args = modelgate_request.parse_args([
            "--config",
            '{"targets":[{"provider":"openai","api_key":"sk-1111111111"},{"provider":"anthropic","apiKey":"sk-2222222222"}]}',
        ])

        payload = modelgate_request.dry_run_payload(modelgate_request.build_request(args))

        self.assertNotIn("sk-1111111111", payload["headers"]["x-modelgate-config"])
        self.assertNotIn("sk-2222222222", payload["headers"]["x-modelgate-config"])
        self.assertIn("sk-1...1111", payload["headers"]["x-modelgate-config"])
        self.assertIn("sk-2...2222", payload["headers"]["x-modelgate-config"])

    def test_dry_run_redacts_config_header_case_insensitively(self):
        request = modelgate_request.BuiltRequest(
            url="http://localhost:8787/v1/chat/completions",
            method="POST",
            headers={"X-ModelGate-Config": '{"provider":"openai","api_key":"sk-1234567890"}'},
            body=b'{"messages":[]}',
        )

        payload = modelgate_request.dry_run_payload(request)

        self.assertNotIn("sk-1234567890", payload["headers"]["X-ModelGate-Config"])
        self.assertIn("sk-1...7890", payload["headers"]["X-ModelGate-Config"])

    def test_dry_run_redacts_provider_specific_secret_fields(self):
        args = modelgate_request.parse_args([
            "--config",
            (
                '{"provider":"azure-openai",'
                '"anthropic_api_key":"sk-anthropic1234",'
                '"azureAdToken":"aad-token-123456",'
                '"targets":[{"provider":"anthropic","anthropicApiKey":"sk-target1234"}]}'
            ),
        ])

        payload = modelgate_request.dry_run_payload(modelgate_request.build_request(args))
        config = payload["headers"]["x-modelgate-config"]

        self.assertNotIn("sk-anthropic1234", config)
        self.assertNotIn("aad-token-123456", config)
        self.assertNotIn("sk-target1234", config)
        self.assertIn("sk-a...1234", config)
        self.assertIn("aad-...3456", config)
        self.assertIn("sk-t...1234", config)

    def test_sdk_style_adds_openai_client_headers_without_json_quoting(self):
        args = modelgate_request.parse_args([
            "--sdk-style",
            "--client-key", "sk-client",
            "--organization", "org-a",
            "--project", "project-a",
            "--beta", "assistants=v2",
            "--preset", "openai",
            "--key", "sk-provider",
            "--custom-host", "http://localhost:9999",
            "--message", "hello from sdk",
        ])

        request = modelgate_request.build_request(args)

        self.assertEqual("Bearer sk-client", request.headers["authorization"])
        self.assertEqual("org-a", request.headers["openai-organization"])
        self.assertEqual("project-a", request.headers["openai-project"])
        self.assertEqual("assistants=v2", request.headers["openai-beta"])
        self.assertEqual(
            {
                "provider": "openai",
                "api_key": "sk-provider",
                "custom_host": "http://localhost:9999",
            },
            json.loads(request.headers["x-modelgate-config"]),
        )
        self.assertEqual(
            {
                "model": "gpt-4o-mini",
                "messages": [{"role": "user", "content": "hello from sdk"}],
            },
            json.loads(request.body.decode("utf-8")),
        )

    def test_sdk_style_reads_client_key_from_environment(self):
        os.environ["MODEL_GATE_CLIENT_KEY"] = "sk-client-env"
        self.addCleanup(os.environ.pop, "MODEL_GATE_CLIENT_KEY", None)

        args = modelgate_request.parse_args([
            "--sdk-style",
            "--client-key-env", "MODEL_GATE_CLIENT_KEY",
        ])

        request = modelgate_request.build_request(args)

        self.assertEqual("Bearer sk-client-env", request.headers["authorization"])

    def test_start_gateway_builds_maven_command_without_shell_quoting(self):
        args = modelgate_request.parse_args([
            "--start-gateway",
            "--gateway-port", "9090",
        ])

        command = modelgate_request.gateway_command(args, 9090)

        self.assertEqual(
            ["mvn", "-q", "-DskipTests", "exec:java", "-Dexec.args=--port 9090"],
            command,
        )

    def test_live_scenario_builds_request_from_manifest_and_honors_base_url_env(self):
        os.environ["OPENAI_API_KEY"] = "sk-live-openai"
        os.environ["MODELGATE_LIVE_OPENAI_BASE_URL"] = "https://example.test/openai/v1"
        os.environ["MODELGATE_LIVE_OPENAI_MODEL"] = "gpt-test"
        self.addCleanup(os.environ.pop, "OPENAI_API_KEY", None)
        self.addCleanup(os.environ.pop, "MODELGATE_LIVE_OPENAI_BASE_URL", None)
        self.addCleanup(os.environ.pop, "MODELGATE_LIVE_OPENAI_MODEL", None)

        args = modelgate_request.parse_args([
            "--live-scenario", "openai",
            "--dry-run",
        ])

        request = modelgate_request.build_request(args)

        self.assertEqual("http://localhost:8787/v1/chat/completions", request.url)
        config = json.loads(request.headers["x-modelgate-config"])
        body = json.loads(request.body.decode("utf-8"))
        self.assertEqual("openai", config["provider"])
        self.assertEqual("sk-live-openai", config["api_key"])
        self.assertEqual("https://example.test/openai/v1", config["custom_host"])
        self.assertEqual("gpt-test", body["model"])

    def test_live_scenario_reports_missing_required_environment(self):
        os.environ.pop("JINA_API_KEY", None)
        args = modelgate_request.parse_args(["--live-scenario", "jina"])

        with self.assertRaisesRegex(ValueError, "JINA_API_KEY"):
            modelgate_request.build_request(args)

    def test_live_scenario_treats_blank_required_environment_as_missing(self):
        os.environ["JINA_API_KEY"] = " "
        self.addCleanup(os.environ.pop, "JINA_API_KEY", None)
        args = modelgate_request.parse_args(["--live-scenario", "jina"])

        with self.assertRaisesRegex(ValueError, "JINA_API_KEY"):
            modelgate_request.build_request(args)

    def test_live_scenario_trims_base_url_environment(self):
        os.environ["OPENAI_API_KEY"] = "sk-live-openai"
        os.environ["MODELGATE_LIVE_OPENAI_BASE_URL"] = "  https://example.test/openai/v1  "
        self.addCleanup(os.environ.pop, "OPENAI_API_KEY", None)
        self.addCleanup(os.environ.pop, "MODELGATE_LIVE_OPENAI_BASE_URL", None)

        args = modelgate_request.parse_args(["--live-scenario", "openai"])

        request = modelgate_request.build_request(args)

        config = json.loads(request.headers["x-modelgate-config"])
        self.assertEqual("https://example.test/openai/v1", config["custom_host"])

    def test_live_scenario_resolves_endpoint_placeholders(self):
        os.environ["AWS_ACCESS_KEY_ID"] = "akid"
        os.environ["AWS_SECRET_ACCESS_KEY"] = "secret"
        os.environ["SAGEMAKER_ENDPOINT_NAME"] = "endpoint-a"
        self.addCleanup(os.environ.pop, "AWS_ACCESS_KEY_ID", None)
        self.addCleanup(os.environ.pop, "AWS_SECRET_ACCESS_KEY", None)
        self.addCleanup(os.environ.pop, "SAGEMAKER_ENDPOINT_NAME", None)

        args = modelgate_request.parse_args(["--live-scenario", "sagemaker"])

        request = modelgate_request.build_request(args)

        self.assertEqual("http://localhost:8787/v1/endpoints/endpoint-a/invocations", request.url)

    def test_live_scenario_uses_placeholder_fallback_for_blank_environment_value(self):
        os.environ["OPENAI_API_KEY"] = "sk-live-openai"
        os.environ["MODELGATE_LIVE_OPENAI_MODEL"] = " "
        self.addCleanup(os.environ.pop, "OPENAI_API_KEY", None)
        self.addCleanup(os.environ.pop, "MODELGATE_LIVE_OPENAI_MODEL", None)

        args = modelgate_request.parse_args(["--live-scenario", "openai"])

        request = modelgate_request.build_request(args)

        body = json.loads(request.body.decode("utf-8"))
        self.assertEqual("gpt-4o-mini", body["model"])

    def test_live_scenario_common_tag_is_ambiguous_and_reports_matching_scenarios(self):
        args = modelgate_request.parse_args(["--live-scenario", "chat"])

        with self.assertRaisesRegex(
            ValueError,
            "matched multiple scenarios: .*openai chat live smoke.*Use a provider id or exact scenario name",
        ):
            modelgate_request.build_request(args)

    def test_main_reports_missing_live_scenario_environment_with_exit_code_two(self):
        os.environ.pop("JINA_API_KEY", None)
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            exit_code = modelgate_request.main(["--live-scenario", "jina", "--dry-run"])

        self.assertEqual(2, exit_code)
        self.assertIn("JINA_API_KEY", stderr.getvalue())

    def test_live_scenario_rejects_blank_selector(self):
        with self.assertRaisesRegex(ValueError, "must not be blank"):
            modelgate_request.find_live_scenario(modelgate_request.DEFAULT_LIVE_MANIFEST, " ")

    def test_live_scenario_rejects_java_filter_tokens(self):
        for selector in ("all", "*", "openai,jina"):
            with self.subTest(selector=selector):
                with self.assertRaisesRegex(ValueError, "selects one scenario"):
                    modelgate_request.find_live_scenario(modelgate_request.DEFAULT_LIVE_MANIFEST, selector)

    def test_live_scenario_help_says_it_selects_one_scenario(self):
        parser_help = capture_parse_help(["--help"])

        self.assertIn("Build one live validation scenario request", parser_help)

    def test_validation_report_json_summarizes_matrix_without_environment(self):
        stdout = io.StringIO()

        with contextlib.redirect_stdout(stdout):
            exit_code = modelgate_request.main(["--validation-report", "--format", "json"])

        self.assertEqual(0, exit_code)
        report = json.loads(stdout.getvalue())
        self.assertEqual(76, report["summary"]["total"])
        self.assertEqual(16, report["summary"]["tiers"]["live-smoke"])
        self.assertEqual(22, report["summary"]["tiers"]["mocked-contract"])
        self.assertEqual(38, report["summary"]["tiers"]["mocked-smoke"])
        self.assertEqual(0, report["summary"]["production_ready"])
        self.assertEqual([], report["drift"])

    def test_validation_report_provider_includes_live_scenario_metadata(self):
        stdout = io.StringIO()

        with contextlib.redirect_stdout(stdout):
            exit_code = modelgate_request.main([
                "--validation-report",
                "--provider", "openai",
                "--format", "json",
            ])

        self.assertEqual(0, exit_code)
        report = json.loads(stdout.getvalue())
        self.assertEqual(1, report["summary"]["total"])
        provider = report["providers"][0]
        self.assertEqual("openai", provider["provider"])
        self.assertEqual("live-smoke", provider["validation_tier"])
        self.assertTrue(provider["live_validation"])
        self.assertEqual(["openai chat live smoke"], provider["live_scenarios"])
        self.assertEqual(["OPENAI_API_KEY"], provider["required_env"])

    def test_validation_report_unknown_provider_returns_exit_code_two(self):
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            exit_code = modelgate_request.main([
                "--validation-report",
                "--provider", "missing-provider",
                "--format", "json",
            ])

        self.assertEqual(2, exit_code)
        self.assertIn("missing-provider", stderr.getvalue())

    def test_validation_report_detects_manifest_matrix_drift(self):
        fixture_dir = Path(__file__).resolve().parent / "fixtures"
        matrix_path = fixture_dir / "validation_matrix_drift.json"
        manifest_path = fixture_dir / "validation_live_drift.json"
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            exit_code = modelgate_request.main([
                "--validation-report",
                "--validation-matrix", str(matrix_path),
                "--live-manifest", str(manifest_path),
                "--format", "json",
            ])

        self.assertEqual(2, exit_code)
        self.assertIn("drift", stderr.getvalue())
        self.assertIn("openai", stderr.getvalue())

    def test_validation_report_help_mentions_no_network(self):
        parser_help = capture_parse_help(["--help"])

        self.assertIn("Offline provider validation matrix report", parser_help)

    def test_validation_check_ready_provider_returns_zero(self):
        fixture_dir = Path(__file__).resolve().parent / "fixtures"
        stdout = io.StringIO()

        with contextlib.redirect_stdout(stdout):
            exit_code = modelgate_request.main([
                "--validation-check-ready", "openai",
                "--validation-matrix", str(fixture_dir / "validation_matrix_ready.json"),
                "--live-manifest", str(fixture_dir / "validation_live_ready.json"),
            ])

        self.assertEqual(0, exit_code)
        self.assertIn("openai production_ready=true", stdout.getvalue())

    def test_validation_check_ready_requires_structured_evidence(self):
        fixture_dir = Path(__file__).resolve().parent / "fixtures"
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            exit_code = modelgate_request.main([
                "--validation-check-ready", "openai",
                "--validation-matrix", str(fixture_dir / "validation_matrix_ready_missing_evidence.json"),
                "--live-manifest", str(fixture_dir / "validation_live_ready.json"),
            ])

        self.assertEqual(2, exit_code)
        self.assertIn("evidence", stderr.getvalue())

    def test_validation_check_not_ready_provider_returns_one(self):
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            exit_code = modelgate_request.main(["--validation-check-ready", "openai"])

        self.assertEqual(1, exit_code)
        self.assertIn("openai production_ready=false", stderr.getvalue())

    def test_validation_check_all_returns_one_when_any_provider_is_not_ready(self):
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            exit_code = modelgate_request.main(["--validation-check-ready", "all"])

        self.assertEqual(1, exit_code)
        self.assertIn("76 provider(s) are not production_ready", stderr.getvalue())

    def test_validation_check_drift_returns_two(self):
        fixture_dir = Path(__file__).resolve().parent / "fixtures"
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            exit_code = modelgate_request.main([
                "--validation-check-ready", "openai",
                "--validation-matrix", str(fixture_dir / "validation_matrix_drift.json"),
                "--live-manifest", str(fixture_dir / "validation_live_drift.json"),
            ])

        self.assertEqual(2, exit_code)
        self.assertIn("drift", stderr.getvalue())

    def test_validation_check_help_mentions_deploy_gate(self):
        parser_help = capture_parse_help(["--help"])

        self.assertIn("Offline production readiness gate", parser_help)


if __name__ == "__main__":
    unittest.main()
