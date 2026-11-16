#!/usr/bin/env python3
"""Bounded Render application probe; never calls collection or paid endpoints.

Only Python's standard library and curl >= 8.4 are required. Configuration is
environment-only so URLs and future credentials are not shell-interpolated.
"""

import argparse
from datetime import datetime, time as wall_time, timedelta, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
from urllib.parse import urlsplit


BJT = timezone(timedelta(hours=8))
WINDOW_START = wall_time(6, 20)
WINDOW_END = wall_time(8, 0)
MAX_ATTEMPTS = 3
REQUEST_SECONDS = 45
BACKOFF_SECONDS = 30
TOTAL_SECONDS = 195
MAX_BODY_BYTES = 4096
RETRY_STATUSES = {0, 408, 425, 500, 502, 503, 504, 522, 523, 524}


def utc_now():
    """Use an aware clock; Beijing's fixed UTC+8 needs no host timezone data."""
    return datetime.now(timezone.utc)


def window_remaining(now):
    """Reject delayed workflow runs outside the approved daily wake window."""
    local = now.astimezone(BJT)
    if not WINDOW_START <= local.time() < WINDOW_END:
        return 0.0
    return (datetime.combine(local.date(), WINDOW_END, BJT) - local).total_seconds()


def probe_url(raw, allow_local=False):
    """Only an origin is accepted, preventing credentials and arbitrary targets.

    Loopback HTTP is explicitly opt-in for local tests, never used by Actions.
    Production custom domains require a separately reviewed allowlist change.
    """
    if not raw or not raw.isascii() or any(ord(c) <= 32 or ord(c) == 127 for c in raw) or "\\" in raw:
        raise ValueError("invalid-origin")
    parts = urlsplit(raw)
    if (parts.username is not None or parts.password is not None or "?" in raw or "#" in raw
            or parts.path not in ("", "/")):
        raise ValueError("invalid-origin")
    host, port = parts.hostname, parts.port
    if (parts.scheme == "https" and host is not None and port in (None, 443)
            and re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.onrender\.com", host)):
        origin = "https://" + host
    elif (allow_local and parts.scheme == "http" and host in ("127.0.0.1", "::1")
          and port is not None and 1024 <= port <= 65535):
        origin = "http://" + ("[::1]" if host == "::1" else host) + ":" + str(port)
    else:
        raise ValueError("invalid-origin")
    return origin + "/api/trend/readiness"


def curl_binary():
    """Require body-size enforcement even when a peer omits Content-Length."""
    binary = shutil.which("curl")
    if not binary:
        raise ValueError("curl-unavailable")
    result = subprocess.run([binary, "--disable", "--version"], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=5, check=False)
    version = re.match(rb"curl (\d+)\.(\d+)\.(\d+)", result.stdout)
    if result.returncode != 0 or not version or tuple(map(int, version.groups())) < (8, 4, 0):
        raise ValueError("curl-version")
    return binary


def request_once(binary, url, seconds):
    """Enforce connection, whole-transfer, subprocess and response-size bounds.

    Ignore curlrc and proxies so the probe sends no inherited authorization;
    redirects are never followed. Only a fixed status/category reaches logs.
    """
    with tempfile.TemporaryDirectory(prefix="polyhouse-trend-wake-") as directory:
        body = Path(directory) / "response.json"
        args = [binary, "--disable", "--silent", "--proxy", "", "--noproxy", "*",
                "--http1.1", "--request", "GET", "--header", "Accept: application/json",
                "--user-agent", "Polyhouse-Trend-Wake/1", "--proto", "=https" if url.startswith("https:") else "=http",
                "--connect-timeout", str(min(5, seconds)), "--max-time", str(seconds),
                "--max-filesize", str(MAX_BODY_BYTES), "--output", str(body),
                "--write-out", "%{http_code}", url]
        try:
            result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                    timeout=seconds + 0.2, check=False)
        except subprocess.TimeoutExpired:
            return False, True, "timeout"
        status = int(result.stdout) if re.fullmatch(rb"\d{3}", result.stdout) else 0
        if result.returncode == 63:
            return False, False, "body-limit"
        if result.returncode != 0:
            return False, status in RETRY_STATUSES, "transport"
        if status != 200:
            return False, status in RETRY_STATUSES, "http-" + str(status)
        with body.open("rb") as stream:
            payload = stream.read(MAX_BODY_BYTES + 1)
        if len(payload) > MAX_BODY_BYTES:
            return False, False, "body-limit"
        try:
            value = json.loads(payload.decode("utf-8"))
        except (ValueError, UnicodeError, RecursionError):
            return False, True, "not-ready"
        data = value.get("data") if isinstance(value, dict) else None
        ready = (isinstance(value, dict) and type(value.get("code")) is int and value["code"] == 0
                 and isinstance(data, dict) and data.get("status") == "READY" and data.get("scope") == "APPLICATION")
        return ready, not ready, "ready" if ready else "not-ready"


def execute(env, allow_local=False, now=utc_now, sleeper=time.sleep, monotonic=time.monotonic):
    """One invocation has bounded retries and cannot extend daily keepalive."""
    if env.get("TREND_WAKE_ENABLED") != "true":
        print("trend-wake: disabled")
        return 0
    if window_remaining(now()) <= 0:
        print("trend-wake: outside-window")
        return 0
    deadline = monotonic() + TOTAL_SECONDS
    try:
        url = probe_url(env.get("TREND_WAKE_BASE_URL", ""), allow_local)
        binary = curl_binary()
        for attempt in range(1, MAX_ATTEMPTS + 1):
            # Reserve subprocess cleanup time within both the daily and run deadlines.
            remaining = min(deadline - monotonic(), window_remaining(now())) - 0.2
            if remaining <= 0:
                break
            ready, retry, category = request_once(binary, url, min(REQUEST_SECONDS, remaining))
            print("trend-wake: attempt=" + str(attempt) + " result=" + category)
            if ready:
                print("trend-wake: application-ready; dependencies-and-task-not-checked")
                return 0
            if not retry or attempt == MAX_ATTEMPTS:
                break
            remaining = min(deadline - monotonic(), window_remaining(now()))
            if remaining <= BACKOFF_SECONDS:
                break
            sleeper(BACKOFF_SECONDS)
    except (OSError, ValueError, subprocess.SubprocessError):
        # URL, peer payload, process stderr and environment may contain private data.
        print("trend-wake: configuration-or-process-error")
        return 1
    print("trend-wake: application-not-ready")
    return 1


def main():
    """Local testing must opt in to a numeric loopback origin explicitly."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-local", action="store_true", help="allow numeric loopback HTTP for local checks only")
    args = parser.parse_args()
    return execute(os.environ, args.allow_local)


if __name__ == "__main__":
    raise SystemExit(main())
