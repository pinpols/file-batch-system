import json
import shlex
import subprocess
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
READINESS_SCRIPT = ROOT / "scripts/lib/console-api-readiness.sh"


class HealthHandler(BaseHTTPRequestHandler):
    response_status = 200
    response_body = b'{"status":"UP"}'

    def do_GET(self):
        self.send_response(self.response_status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(self.response_body)

    def log_message(self, _format, *_args):
        pass


class ConsoleApiReadinessTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()
        self.addCleanup(self.server_thread.join)
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def assert_readiness(self, expected, *, status, body):
        HealthHandler.response_status = status
        HealthHandler.response_body = body
        health_url = f"http://127.0.0.1:{self.server.server_port}/actuator/health"
        command = (
            f"source {shlex.quote(str(READINESS_SCRIPT))}; "
            f"console_api_is_ready {shlex.quote(health_url)} {shlex.quote(sys.executable)}"
        )
        result = subprocess.run(["bash", "-c", command], check=False)
        self.assertEqual(expected, result.returncode == 0)

    def test_accepts_valid_health_json_when_dependency_returns_503(self):
        body = b'{"status":"DOWN","components":{"s3":{"status":"DOWN"}}}'
        self.assert_readiness(True, status=503, body=body)

    def test_accepts_healthy_json(self):
        self.assert_readiness(True, status=200, body=json.dumps({"status": "UP"}).encode())

    def test_rejects_non_json_or_missing_status(self):
        self.assert_readiness(False, status=503, body=b"unavailable")
        self.assert_readiness(False, status=200, body=b'{"components":{}}')

    def test_rejects_unreachable_console_api(self):
        self.server.shutdown()
        health_url = f"http://127.0.0.1:{self.server.server_port}/actuator/health"
        command = (
            f"source {shlex.quote(str(READINESS_SCRIPT))}; "
            f"console_api_is_ready {shlex.quote(health_url)} {shlex.quote(sys.executable)}"
        )
        result = subprocess.run(["bash", "-c", command], check=False)
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
