# 用途：验证控制面混合画像分别对实际启用的写请求执行延迟门槛。
# 用法：在仓库根目录执行 python3 load-tests/scripts/test-control-mixed-slo.py；需本地 Maven 和 Gatling 依赖缓存。
"""仅访问临时回环服务，以大量快速读请求对照一次慢写请求。"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import threading
import time


ROOT = Path(__file__).resolve().parents[2]


def check_profile(delay_seconds, should_pass, module="trigger"):
    class Handler(BaseHTTPRequestHandler):
        reads = 0
        writes = 0

        def log_message(self, *args):
            pass

        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            type(self).writes += 1
            time.sleep(delay_seconds)
            self.respond()

        def do_GET(self):
            type(self).reads += 1
            self.respond()

        def respond(self):
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"{}")

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        command = [
            str(ROOT / "mvnw"), "-o", "-ntp", "-f", "load-tests/pom.xml", "gatling:test",
            "-Dsimulation=io.github.pinpols.batch.loadtest.simulations.ControlPlaneMixedPressureSimulation",
            f"-Dtrigger.baseUrl={base_url}", f"-Dorchestrator.baseUrl={base_url}",
            f"-Dcontrol.modules={module}", f"-Dcontrol.{module}.workflowDefinitionId=1",
            f"-Dcontrol.{module}.rps=1", "-Dscheduling.launch.rps=1", "-Dscheduling.read.rps=30",
            "-Dduration.seconds=1",
            "-Dslo.write.p95ms=700", "-Dslo.maxErrorPct=1", "-Dinternal.secret=stub-secret",
        ]
        try:
            result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=120)
        finally:
            server.shutdown()
            thread.join()

    assertion_prefix = f"mixed {module} / POST /api/triggers/launch: 95th percentile"
    assertion_line = next((line for line in result.stdout.splitlines()
                           if line.startswith(assertion_prefix)), "")
    expected_outcome = " : true " if should_pass else " : false "
    expected_reads = 20 if module == "trigger" else 0
    if (Handler.writes != 1 or Handler.reads < expected_reads
            or (result.returncode == 0) != should_pass or expected_outcome not in assertion_line):
        raise AssertionError(
            f"delay={delay_seconds}, expected_pass={should_pass}, exit={result.returncode}, "
            f"writes={Handler.writes}, reads={Handler.reads}\n{result.stdout[-7000:]}\n{result.stderr}"
        )
    print(f"通过：{module} 写请求延迟 {delay_seconds} 秒，读请求 {Handler.reads} 次")


if __name__ == "__main__":
    check_profile(1.5, False)
    check_profile(0, True)
    check_profile(0, True, "process")
