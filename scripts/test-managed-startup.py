#!/usr/bin/env python3
"""Real Chen/Wisp startup against a disposable, deliberately partial Core stub.

Tests retry policy and process/HTTP boundaries, not authentication, Core WebSocket
health, database sessions or audit delivery. All keys are synthetic and local.
"""
import argparse
import http.client
import http.server
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import threading
import time


def unused_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def status(port, path):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=4)
    try:
        conn.request("GET", path)
        response = conn.getresponse()
        body = response.read()
        assert b"synthetic-secret" not in body
        return response.status
    except OSError:
        return 0
    finally:
        conn.close()


def until(predicate, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.05)
    raise AssertionError("condition did not become true within deadline")


class CoreStub:
    def __init__(self, port, available):
        self.available = available
        self.profile_calls = 0
        self.setting_calls = 0
        self.setting_active = 0
        self.lock = threading.Lock()
        self.release = threading.Event()
        self.release.set()
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                code, data = 503, {}
                if self.path == "/api/v1/users/profile/":
                    with fixture.lock:
                        fixture.profile_calls += 1
                    if fixture.available:
                        code, data = 200, {"id": "synthetic-user"}
                elif self.path == "/api/v1/terminal/terminals/config/":
                    code, data = 200, {"TERMINAL_COMMAND_STORAGE": {"TYPE": "server"}, "TERMINAL_REPLAY_STORAGE": {"TYPE": "server"}}
                elif self.path == "/api/v1/settings/public/":
                    with fixture.lock:
                        fixture.setting_calls += 1
                        fixture.setting_active += 1
                    try:
                        fixture.release.wait(35)
                    finally:
                        with fixture.lock:
                            fixture.setting_active -= 1
                    code = 200 if fixture.available else 503
                # All other routes, including WebSocket upgrade, remain 503.
                payload = json.dumps(data).encode()
                try:
                    self.send_response(code)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


def scenario(args, name):
    core_port, http_port, wisp_port = unused_port(), unused_port(), unused_port()
    core = None
    process = None
    with tempfile.TemporaryDirectory(prefix="chen-managed-startup-") as directory:
        work = Path(directory)
        keys = work / "data/keys"
        keys.mkdir(parents=True)
        key = keys / ".access_key"
        key.write_text("synthetic-id:synthetic-secret")
        key.chmod(0o600)
        # Do not inherit real Core credentials, Wisp config, TLS or proxy settings.
        env = {k: os.environ[k] for k in ("PATH", "HOME", "TMPDIR") if k in os.environ}
        env.update(CHEN_BIN=str(args.chen.resolve()), WISP_BIN=str(args.wisp.resolve()),
                   CORE_HOST=f"http://127.0.0.1:{core_port}", WORK_DIR=directory,
                   CHEN_STATE_DIR=str(work / "data/go"), CHEN_HTTP_ADDR=f"127.0.0.1:{http_port}",
                   WISP_BIND_PORT=str(wisp_port), CHEN_STARTUP_TIMEOUT="500ms" if name == "chen_timeout" else "120s")
        log_path = work / "process.log"
        try:
            if name != "delayed_core":
                core = CoreStub(core_port, available=False)
            with log_path.open("w") as log:
                process = subprocess.Popen(["bash", str(args.entrypoint.resolve()), "managed"], cwd=directory,
                                           env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            started = time.monotonic()
            until(lambda: "waiting_for_wisp_initialization" in log_path.read_text())
            assert status(http_port, "/healthy") == 0
            assert status(http_port, "/ready") == 0
            if name == "delayed_core":
                until(lambda: "校验 access key failed" in log_path.read_text())
                assert process.poll() is None
                core = CoreStub(core_port, available=True)
                until(lambda: status(http_port, "/ready") == 200)
                assert core.profile_calls >= 1
                # Normal readiness performs one Core GET; liveness performs none.
                before = core.setting_calls
                for _ in range(5):
                    assert status(http_port, "/healthy") == 200
                assert core.setting_calls == before
                for _ in range(5):
                    assert status(http_port, "/ready") == 200
                assert core.setting_calls == before + 5
                core.available = False
                assert status(http_port, "/ready") == 503
                assert status(http_port, "/healthy") == 200
                core.available = True
                assert status(http_port, "/ready") == 200
                core.release.clear()
                probe_started = time.monotonic()
                assert status(http_port, "/ready") == 503
                assert time.monotonic() - probe_started < 3.5
                # Pinned Wisp does not pass the RPC context to its SDK HTTP call.
                # Observe the upstream timeout boundary without asserting full health.
                assert core.setting_active == 1
                assert status(http_port, "/healthy") == 200
                core.release.set()
                until(lambda: core.setting_active == 0)
                assert status(http_port, "/ready") == 200
                process.send_signal(signal.SIGTERM)
                assert process.wait(timeout=55) == 143
            else:
                deadline = started + (12 if name == "chen_timeout" else 75)
                while process.poll() is None and time.monotonic() < deadline:
                    assert status(http_port, "/healthy") == 0
                    time.sleep(0.1)
                assert process.poll() == 1, "startup failure was hidden or did not stop"
                log = log_path.read_text()
                if name == "chen_timeout":
                    assert "wisp_startup_timeout" in log
                    assert core.profile_calls < 10
                else:
                    assert core.profile_calls == 10, core.profile_calls
                    assert "校验 access key failed 退出" in log
                    assert "wisp_startup_timeout" not in log
                    assert time.monotonic() - started < 120
            assert key.read_text() == "synthetic-id:synthetic-secret"
            print(f"PASS {name}: {time.monotonic() - started:.1f}s", flush=True)
        finally:
            if core:
                core.close()
            if process:
                if process.poll() is None:
                    process.send_signal(signal.SIGTERM)
                    try:
                        process.wait(timeout=55)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait()
                # Also clean up child processes if supervision itself regressed.
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chen", type=Path, required=True)
    parser.add_argument("--wisp", type=Path, required=True)
    parser.add_argument("--entrypoint", type=Path, default=Path(__file__).resolve().parent.parent / "entrypoint.sh")
    args = parser.parse_args()
    for name in ("delayed_core", "chen_timeout", "wisp_retry_exhausted"):
        scenario(args, name)
