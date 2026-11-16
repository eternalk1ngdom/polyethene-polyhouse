"""Real curl/loopback checks: no Render, database, account or AI is contacted."""

from contextlib import contextmanager, redirect_stdout
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
from pathlib import Path
import re
import threading
import time
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[2] / "Invoke-TrendWake.py"
SPEC = importlib.util.spec_from_file_location("trend_wake", SCRIPT)
wake = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(wake)
NOW = datetime(2026, 11, 15, 22, 20, tzinfo=timezone.utc)
READY = json.dumps({"code": 0, "data": {"status": "READY", "scope": "APPLICATION"}}).encode()


@contextmanager
def peer(responses):
    """A real HTTP peer records requests and can delay, redirect or stream bodies."""
    seen = []
    disconnected = threading.Event()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            index = len(seen)
            seen.append((self.path, dict(self.headers)))
            status, body, options = responses[min(index, len(responses) - 1)]
            time.sleep(options.get("delay", 0))
            try:
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Connection", "close")
                if not options.get("unknown_length"):
                    self.send_header("Content-Length", str(len(body)))
                for key, value in options.get("headers", {}).items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(body)
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                disconnected.set()
            self.close_connection = True

        def log_message(self, *_args):
            # Peer-controlled request data must not be printed by test fixtures.
            return None

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    worker = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
    worker.start()
    try:
        yield "http://127.0.0.1:" + str(server.server_port), seen
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)


class TrendWakeTest(unittest.TestCase):
    def run_probe(self, origin, now=NOW, **kwargs):
        env = {"TREND_WAKE_ENABLED": "true", "TREND_WAKE_BASE_URL": origin,
               "DEEPSEEK_API_KEY": "fixture-private-key", "OWNER_TOKEN": "fixture-private-token"}
        sleeps, output = [], io.StringIO()
        with redirect_stdout(output):
            result = wake.execute(env, allow_local=True, now=lambda: now, sleeper=sleeps.append, **kwargs)
        self.assertNotIn("fixture-private", output.getvalue())
        self.assertNotIn(origin, output.getvalue())
        return result, output.getvalue(), sleeps

    def test_disabled_and_late_runs_never_resolve_or_contact_peer(self):
        with patch.object(wake, "curl_binary", side_effect=AssertionError("must not run")):
            for enabled in (None, "false", "TRUE", "1"):
                with redirect_stdout(io.StringIO()):
                    self.assertEqual(0, wake.execute({"TREND_WAKE_ENABLED": enabled}))
            for hour, minute in ((22, 19), (0, 0), (3, 0)):
                stamp = NOW.replace(hour=hour, minute=minute)
                self.assertEqual(0, self.run_probe("invalid", now=stamp)[0])

    def test_origin_allowlist_rejects_credentials_paths_queries_and_lookalikes(self):
        for raw in ("https://user:private@a.onrender.com", "https://a.onrender.com/?key=private",
                    "https://a.onrender.com/#private", "https://a.onrender.com/other",
                    "https://a.onrender.com.evil.test", "https://onrender.com", "https://a.b.onrender.com",
                    "http://a.onrender.com", "https://a.onrender.com:444", " https://a.onrender.com",
                    "https://a.onrender.com\n", "https://a.onrender.com\\@evil.test",
                    "http://localhost:8080", "http://192.0.2.1:8080", "http://127.0.0.1:80"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                wake.probe_url(raw, allow_local=True)
        with self.assertRaises(ValueError):
            wake.probe_url("http://127.0.0.1:8080")
        self.assertEqual("https://sample.onrender.com/api/trend/readiness",
                         wake.probe_url("https://sample.onrender.com:443/"))

    def test_ready_sends_only_fixed_get_without_credentials(self):
        with peer([(200, READY, {})]) as (origin, seen):
            code, output, sleeps = self.run_probe(origin)
        self.assertEqual(0, code)
        self.assertEqual([], sleeps)
        self.assertIn("dependencies-and-task-not-checked", output)
        self.assertEqual(1, len(seen))
        self.assertEqual("/api/trend/readiness", seen[0][0])
        self.assertNotIn("Authorization", seen[0][1])
        self.assertNotIn("Cookie", seen[0][1])

    def test_cold_start_503_then_application_ready(self):
        with peer([(503, b"warming", {}), (200, READY, {})]) as (origin, seen):
            code, output, sleeps = self.run_probe(origin)
        self.assertEqual(0, code)
        self.assertEqual([30], sleeps)
        self.assertEqual(2, len(seen))
        self.assertIn("http-503", output)

    def test_loading_page_and_invalid_success_never_claim_ready(self):
        for body in (b"<html>Render is starting</html>", b'{"code":false,"data":{"status":"READY","scope":"APPLICATION"}}',
                     b'{"code":0,"data":{"status":"READY","scope":"DATABASE"}}', b"[]", b"\xff"):
            with self.subTest(body=body), peer([(200, body, {})]) as (origin, seen):
                code, output, sleeps = self.run_probe(origin)
                self.assertEqual(1, code)
                self.assertEqual(3, len(seen))
                self.assertEqual([30, 30], sleeps)
                self.assertNotIn("application-ready;", output)

    def test_redirect_is_not_followed_or_retried(self):
        with peer([(200, READY, {})]) as (target, target_seen):
            with peer([(302, b"", {"headers": {"Location": target + "/private"}})]) as (origin, seen):
                code, _, sleeps = self.run_probe(origin)
            self.assertEqual([], target_seen)
        self.assertEqual(1, code)
        self.assertEqual(1, len(seen))
        self.assertEqual([], sleeps)

    def test_missing_unauthorized_and_rate_limited_are_not_retried(self):
        for status in (401, 403, 404, 429):
            with self.subTest(status=status), peer([(status, b"private-peer-body", {})]) as (origin, seen):
                code, output, sleeps = self.run_probe(origin)
                self.assertEqual(1, code)
                self.assertEqual(1, len(seen))
                self.assertEqual([], sleeps)
                self.assertNotIn("private-peer-body", output)

    def test_known_and_unknown_length_oversized_body_stops(self):
        for unknown in (False, True):
            with self.subTest(unknown=unknown), peer([(200, b"private" * 2000, {"unknown_length": unknown})]) as (origin, seen):
                code, output, sleeps = self.run_probe(origin)
                self.assertEqual(1, code)
                self.assertEqual(1, len(seen))
                self.assertEqual([], sleeps)
                self.assertIn("body-limit", output)
                self.assertNotIn("private", output)

    def test_real_http_read_timeout_is_bounded(self):
        with peer([(200, READY, {"delay": 0.5})]) as (origin, seen):
            with patch.object(wake, "REQUEST_SECONDS", 0.1), patch.object(wake, "MAX_ATTEMPTS", 1):
                start = time.monotonic()
                code, _, _ = self.run_probe(origin)
                self.assertLess(time.monotonic() - start, 2)
        self.assertEqual(1, code)
        self.assertEqual(1, len(seen))

    def test_retry_does_not_cross_daily_window_or_total_deadline(self):
        with peer([(503, b"warming", {})]) as (origin, seen):
            code, _, sleeps = self.run_probe(origin, now=NOW.replace(hour=23, minute=59, second=50))
            self.assertEqual(1, code)
            self.assertEqual(1, len(seen))
            self.assertEqual([], sleeps)
        with patch.object(wake, "request_once", side_effect=AssertionError("deadline exceeded")):
            with patch.object(wake, "TOTAL_SECONDS", 0):
                self.assertEqual(1, self.run_probe("https://sample.onrender.com")[0])

    def test_bad_configuration_and_old_curl_fail_without_leaking_values(self):
        with patch.object(wake, "curl_binary", side_effect=AssertionError("bad URL must fail first")):
            self.assertEqual(1, self.run_probe("https://private-key@a.onrender.com")[0])
        fake = type("Result", (), {"returncode": 0, "stdout": b"curl 8.3.0 fixture"})()
        with patch.object(wake.shutil, "which", return_value="curl"), patch.object(wake.subprocess, "run", return_value=fake):
            with self.assertRaises(ValueError):
                wake.curl_binary()

    def test_workflow_window_and_gates_match_approved_operation(self):
        workflow = SCRIPT.parents[2] / ".github/workflows/trend-wake.yml"
        text = workflow.read_text(encoding="utf-8")
        crons = re.findall(r"cron: '([^']+)'", text)
        self.assertEqual(["20-55/5 22 * * *", "*/5 23 * * *"], crons)
        # Expand UTC occurrences and compare actual Beijing minutes, including day rollover.
        actual = {(hour + 8) % 24 * 60 + minute for hour, minutes in
                  ((22, range(20, 60, 5)), (23, range(0, 60, 5))) for minute in minutes}
        self.assertEqual(set(range(6 * 60 + 20, 8 * 60, 5)), actual)
        for minute in actual:
            stamp = datetime(2026, 11, 16, minute // 60, minute % 60, tzinfo=wake.BJT)
            self.assertGreater(wake.window_remaining(stamp), 0)
        self.assertIn("vars.TREND_WAKE_ENABLED == 'true' && github.ref_name == github.event.repository.default_branch", text)
        self.assertIn("contents: read", text)
        self.assertIn("persist-credentials: false", text)
        self.assertIn("timeout-minutes: 5", text)
        self.assertIn("cancel-in-progress: false", text)
        self.assertIn("run: python3 polyhouse/scripts/Invoke-TrendWake.py", text)
        for forbidden in ("secrets.", "--allow-local", "workflow_call:", "id-token:", "pages:", "DEEPSEEK", "OWNER_TOKEN"):
            self.assertNotIn(forbidden, text)


if __name__ == "__main__":
    unittest.main()
