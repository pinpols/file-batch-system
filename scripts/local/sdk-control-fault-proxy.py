#!/usr/bin/env python3
"""Loopback fault proxy for SDK control-directive E2E; never bind beyond localhost."""

from __future__ import annotations

import http.client
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


UPSTREAM = urlsplit(os.environ["SDK_CONTROL_UPSTREAM"])
STATE = {"workerCode": "", "paused": False, "pausedHeartbeats": 0, "normalHeartbeats": 0}
LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:
        if self.path == "/__control/status":
            self._reply(200, json.dumps(self._state()).encode())
            return
        self._forward()

    def do_POST(self) -> None:
        if self.path == "/__control":
            length = int(self.headers.get("Content-Length", "0"))
            request = json.loads(self.rfile.read(length) or b"{}")
            with LOCK:
                STATE["workerCode"] = str(request.get("workerCode", ""))
                STATE["paused"] = bool(request.get("paused", False))
            self._reply(200, json.dumps(self._state()).encode())
            return
        self._forward()

    def _forward(self) -> None:
        connection_type = http.client.HTTPSConnection if UPSTREAM.scheme == "https" else http.client.HTTPConnection
        port = UPSTREAM.port or (443 if UPSTREAM.scheme == "https" else 80)
        connection = connection_type(UPSTREAM.hostname, port, timeout=20)
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        headers = {
            name: value
            for name, value in self.headers.items()
            if name.lower() not in {
                "connection",
                "host",
                "content-length",
                "transfer-encoding",
                "accept-encoding",
            }
        }
        path = f"{UPSTREAM.path.rstrip('/')}{self.path}"
        try:
            connection.request(self.command, path, body=body, headers=headers)
            upstream_response = connection.getresponse()
            payload = upstream_response.read()
            status = upstream_response.status
            response_headers = {
                name: value
                for name, value in upstream_response.getheaders()
                if name.lower() not in {"connection", "content-length", "transfer-encoding", "content-encoding"}
            }
            if status == 200 and self.command == "POST" and self.path.endswith("/heartbeat"):
                payload = self._inject_pause(payload)
                response_headers.pop("Content-Type", None)
                response_headers.pop("content-type", None)
                response_headers["Content-Type"] = "application/json"
            self._reply(status, payload, response_headers)
        except (OSError, http.client.HTTPException) as error:
            self._reply(502, json.dumps({"error": str(error)}).encode())
        finally:
            connection.close()

    def _inject_pause(self, payload: bytes) -> bytes:
        try:
            directive = json.loads(payload or b"{}")
        except json.JSONDecodeError:
            return payload
        parts = self.path.split("/")
        worker_code = parts[-2] if len(parts) >= 2 else ""
        with LOCK:
            if worker_code != STATE["workerCode"]:
                return payload
            if STATE["paused"]:
                STATE["pausedHeartbeats"] += 1
                directive["platformStatus"] = "PAUSED"
                directive["shouldDrain"] = False
            else:
                STATE["normalHeartbeats"] += 1
        return json.dumps(directive, separators=(",", ":")).encode()

    @staticmethod
    def _state() -> dict[str, object]:
        with LOCK:
            return dict(STATE)

    def _reply(self, status: int, payload: bytes, headers: dict[str, str] | None = None) -> None:
        self.send_response(status)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt: str, *args: object) -> None:
        return


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
print(f"LISTENING {server.server_address[1]}", flush=True)
server.serve_forever()
