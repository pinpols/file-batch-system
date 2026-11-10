# 用途：在本机回环 stub 验证控制台读压测与调度画像使用登录 Cookie。
# 用法：在仓库根目录执行 python3 load-tests/scripts/test-console-auth-profiles.py；需本地 Maven 和 Gatling 依赖缓存。
"""不访问真实服务；Trigger 与调度内部接口使用独立的内部密钥。"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http.cookies import SimpleCookie
import argparse
from pathlib import Path
import subprocess
import threading
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[2]


def run_profile(simulation, extra_args):
    class Handler(BaseHTTPRequestHandler):
        console_reads = 0
        rejected = 0

        def log_message(self, *args):
            pass

        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            self.respond(self.headers.get("X-Internal-Secret") == "stub-trigger-secret"
                         and self.headers.get("Cookie") is None)

        def do_GET(self):
            path = urlsplit(self.path).path
            if path.startswith("/api/console/"):
                type(self).console_reads += 1
                cookies = SimpleCookie(self.headers.get("Cookie", ""))
                authorized = bool(cookies.get("batch_console_token")) and cookies["batch_console_token"].value == "stub-session-token"
            elif path.startswith("/internal/"):
                authorized = (self.headers.get("X-Internal-Secret") == "stub-trigger-secret"
                              and self.headers.get("Cookie") is None)
            else:
                authorized = path == "/actuator/health"
            self.respond(authorized)

        def respond(self, authorized):
            if not authorized:
                type(self).rejected += 1
                self.send_error(401)
                return
            body = b'{}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            if self.command == "GET" and type(self).console_reads == 1:
                self.send_header("Set-Cookie", "XSRF-TOKEN=stub-csrf; Path=/")
            self.end_headers()
            self.wfile.write(body)

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        command = [
            str(ROOT / "mvnw"), "-o", "-ntp", "-f", "load-tests/pom.xml", "gatling:test",
            f"-Dsimulation=io.github.pinpols.batch.loadtest.simulations.{simulation}",
            f"-Dtrigger.baseUrl={base_url}", f"-Dconsole.baseUrl={base_url}",
            f"-Dorchestrator.baseUrl={base_url}", "-DworkflowDefinitionId=1",
            "-Dconsole.accessToken=stub-session-token", "-Dinternal.secret=stub-trigger-secret",
            "-Dduration.seconds=1", "-Dslo.read.p99ms=10000", "-Dslo.write.p95ms=10000",
            *extra_args,
        ]
        try:
            result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=90)
        finally:
            server.shutdown()
            thread.join()

    if result.returncode != 0 or Handler.console_reads == 0 or Handler.rejected:
        raise AssertionError(
            f"{simulation}: exit={result.returncode}, console_reads={Handler.console_reads}, "
            f"rejected={Handler.rejected}\n{result.stdout}\n{result.stderr}"
        )
    print(f"通过：{simulation}（控制台请求 {Handler.console_reads} 次）")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--scheduling", action="store_true", help="只验证调度画像")
    args = parser.parse_args()
    if not args.scheduling:
        run_profile("ConsoleQuerySimulation", ["-Dusers.peak=1"])
    run_profile("SchedulingBacklogUnderLoadSimulation", [
        "-Dscheduling.console.reads=true", "-Dscheduling.launch.rps=1", "-Dscheduling.read.rps=1"
    ])
