#!/usr/bin/env python3
"""为五语言 SDK 提供真实 loopback socket 的 Happy Eyeballs 故障矩阵。"""

from __future__ import annotations

import argparse
import contextlib
import json
import signal
import socket
import threading
import time
from pathlib import Path
from typing import Any

LOOPBACK = {
    "ipv4": (socket.AF_INET, "127.0.0.1"),
    "ipv6": (socket.AF_INET6, "::1"),
}


class Scenario:
    def __init__(
        self,
        name: str,
        states: dict[str, str],
        address_order: list[str],
        expected: str,
        counts: dict[str, int],
        counts_file: Path,
        counts_lock: threading.Lock,
    ) -> None:
        self.name = name
        self.states = states
        self.address_order = address_order
        self.expected = expected
        self.counts = counts
        self.counts_file = counts_file
        self.counts_lock = counts_lock
        self.sockets: list[socket.socket] = []
        self.fillers: list[socket.socket] = []
        self.threads: list[threading.Thread] = []
        self.stop = threading.Event()
        self.port = 0

    def start(self) -> dict[str, Any]:
        active_families = [family for family, state in self.states.items() if state != "off"]
        if not active_families:
            raise ValueError(f"scenario {self.name} has no active family")

        first_family = active_families[0]
        first = self._bound_socket(first_family, 0)
        self.port = int(first.getsockname()[1])
        sockets = {first_family: first}
        for family in active_families[1:]:
            sockets[family] = self._bound_socket(family, self.port)

        for family, sock in sockets.items():
            state = self.states[family]
            if state == "live":
                sock.listen(64)
                sock.settimeout(0.2)
                thread = threading.Thread(
                    target=self._serve,
                    args=(sock,),
                    name=f"he-live-{self.name}-{family}",
                    daemon=True,
                )
                thread.start()
                self.threads.append(thread)
            elif state == "blackhole":
                self._saturate_accept_queue(sock, family)
            else:
                raise ValueError(f"unsupported socket state: {state}")
            self.sockets.append(sock)

        return {
            "base_url": f"http://localhost:{self.port}",
            "addresses": self.address_order,
            "expected": self.expected,
            "timeout_ms": 900,
            "blackhole": any(state == "blackhole" for state in self.states.values()),
        }

    def close(self) -> None:
        self.stop.set()
        for sock in self.fillers + self.sockets:
            with contextlib.suppress(OSError):
                sock.close()
        for thread in self.threads:
            thread.join(timeout=1)

    def _bound_socket(self, family: str, port: int) -> socket.socket:
        af, host = LOOPBACK[family]
        sock = socket.socket(af, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if af == socket.AF_INET6:
            sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
        sock.bind((host, port))
        return sock

    def _saturate_accept_queue(self, listener: socket.socket, family: str) -> None:
        listener.listen(0)
        af, host = LOOPBACK[family]
        for _ in range(8192):
            filler = socket.socket(af, socket.SOCK_STREAM)
            filler.settimeout(0.01)
            try:
                filler.connect((host, self.port))
                self.fillers.append(filler)
            except TimeoutError:
                filler.close()
                break
            except OSError as exc:
                filler.close()
                raise RuntimeError(
                    f"failed to saturate {self.name}/{family} accept queue: {exc}"
                ) from exc
        else:
            raise RuntimeError(f"accept queue did not saturate: {self.name}/{family}")

        probe = socket.socket(af, socket.SOCK_STREAM)
        probe.settimeout(0.08)
        started = time.monotonic()
        try:
            probe.connect((host, self.port))
        except TimeoutError:
            return
        finally:
            probe.close()
        elapsed = time.monotonic() - started
        raise RuntimeError(
            f"blackhole probe connected unexpectedly: {self.name}/{family} after {elapsed:.3f}s"
        )

    def _serve(self, listener: socket.socket) -> None:
        while not self.stop.is_set():
            try:
                connection, _ = listener.accept()
            except TimeoutError:
                continue
            except OSError:
                return
            threading.Thread(
                target=self._respond,
                args=(connection,),
                name=f"he-http-{self.name}",
                daemon=True,
            ).start()

    def _respond(self, connection: socket.socket) -> None:
        with connection:
            connection.settimeout(2)
            payload = bytearray()
            try:
                while b"\r\n\r\n" not in payload:
                    chunk = connection.recv(4096)
                    if not chunk:
                        return
                    payload.extend(chunk)
                header, body = bytes(payload).split(b"\r\n\r\n", 1)
                content_length = 0
                for line in header.split(b"\r\n")[1:]:
                    key, _, value = line.partition(b":")
                    if key.strip().lower() == b"content-length":
                        content_length = int(value.strip())
                        break
                while len(body) < content_length:
                    chunk = connection.recv(4096)
                    if not chunk:
                        return
                    body += chunk
                self._record_request()
                response_body = b"{}"
                connection.sendall(
                    b"HTTP/1.1 200 OK\r\n"
                    b"Content-Type: application/json\r\n"
                    b"Content-Length: 2\r\n"
                    b"Connection: close\r\n\r\n" + response_body
                )
            except (OSError, ValueError):
                return

    def _record_request(self) -> None:
        with self.counts_lock:
            self.counts[self.name] += 1
            self.counts_file.write_text(json.dumps(self.counts, sort_keys=True), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix-file", required=True, type=Path)
    parser.add_argument("--counts-file", required=True, type=Path)
    args = parser.parse_args()

    definitions = {
        "ipv4_only": ({"ipv4": "live", "ipv6": "off"}, ["::1", "127.0.0.1"], "success"),
        "ipv6_only": ({"ipv4": "off", "ipv6": "live"}, ["127.0.0.1", "::1"], "success"),
        "dual_live": ({"ipv4": "live", "ipv6": "live"}, ["::1", "127.0.0.1"], "success"),
        "ipv6_blackhole": (
            {"ipv4": "live", "ipv6": "blackhole"},
            ["::1", "127.0.0.1"],
            "success",
        ),
        "ipv4_blackhole": (
            {"ipv4": "blackhole", "ipv6": "live"},
            ["127.0.0.1", "::1"],
            "success",
        ),
        "all_blackhole": (
            {"ipv4": "blackhole", "ipv6": "blackhole"},
            ["::1", "127.0.0.1"],
            "timeout",
        ),
    }
    counts = {name: 0 for name in definitions}
    counts_lock = threading.Lock()
    scenarios: list[Scenario] = []
    matrix: dict[str, Any] = {"scenarios": {}}
    try:
        for name, (states, order, expected) in definitions.items():
            scenario = Scenario(
                name,
                states,
                order,
                expected,
                counts,
                args.counts_file,
                counts_lock,
            )
            matrix["scenarios"][name] = scenario.start()
            scenarios.append(scenario)
        args.counts_file.write_text(json.dumps(counts, sort_keys=True), encoding="utf-8")
        args.matrix_file.write_text(json.dumps(matrix, sort_keys=True), encoding="utf-8")

        stopped = threading.Event()

        def stop_handler(_signum: int, _frame: object) -> None:
            stopped.set()

        signal.signal(signal.SIGTERM, stop_handler)
        signal.signal(signal.SIGINT, stop_handler)
        while not stopped.wait(0.5):
            pass
    finally:
        for scenario in scenarios:
            scenario.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
