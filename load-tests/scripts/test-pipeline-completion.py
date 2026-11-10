# 用途：在本机回环 stub 上验证 Gatling 端到端完成场景的终态和超时判定。
# 用法：在仓库根目录执行 python3 load-tests/scripts/test-pipeline-completion.py；需本地已有 Maven 与 Gatling 依赖缓存。
"""检查每种业务终态及轮询耗尽的场景结果，不连接真实服务。"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http.cookies import SimpleCookie
import argparse
import json
from pathlib import Path
import subprocess
import threading
from urllib.parse import parse_qs, urlsplit


ROOT = Path(__file__).resolve().parents[2]
CASES = (
    ("即时成功", ("SUCCESS",), 0, 1),
    ("等待根运行创建后成功", (None, "SUCCESS"), 0, 2),
    ("等待后成功", ("RUNNING", "SUCCESS"), 0, 2),
    ("业务失败", ("FAILED",), 1, 1),
    ("业务终止", ("TERMINATED",), 1, 1),
    ("dry-run 成功", ("SUCCESS_DRY_RUN",), 1, 1),
    ("轮询超时", ("RUNNING",), 1, 2),
    ("查询被限流", ("RATE_LIMIT",), 1, 2),
)


def run_launch_only_case(accepted=True):
    class Handler(BaseHTTPRequestHandler):
        posts = 0
        gets = 0

        def log_message(self, *args):
            pass

        def do_POST(self):
            type(self).posts += 1
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            if self.path != "/api/triggers/launch":
                self.send_error(404)
                return
            if not accepted:
                self.send_error(503)
                return
            body = json.dumps({"data": {"instanceNo": "a42724bb-417e-4b7c-b7d8-70f21a7304dd"}}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            type(self).gets += 1
            self.send_error(400)

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        command = [
            str(ROOT / "mvnw"), "-o", "-ntp", "-f", "load-tests/pom.xml", "gatling:test",
            "-Dsimulation=io.github.pinpols.batch.loadtest.simulations.LaunchPipelineCompletionSimulation",
            f"-Dtrigger.baseUrl={base_url}", f"-Dconsole.baseUrl={base_url}",
            "-DworkflowDefinitionId=1", "-Dpipeline.completion.users=1", "-Dramp.seconds=1",
            "-Dpipeline.maxPolls=0", "-Dpipeline.pollIntervalSec=1", "-Dslo.maxErrorPct=0",
        ]
        try:
            result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=120)
        finally:
            server.shutdown()
            thread.join()

    output = result.stdout + result.stderr
    expected_exit = result.returncode == 0 if accepted else result.returncode != 0
    if not expected_exit or Handler.posts != 1 or Handler.gets != 0 or "launch_acceptance" not in output:
        raise AssertionError(
            f"仅受理模式: exit={result.returncode}, posts={Handler.posts}, gets={Handler.gets}\n{output}"
        )
    outcome = "受理成功" if accepted else "受理失败"
    print(f"通过：仅受理模式 {outcome}（未轮询终态）")


def run_case(name, statuses, expected_exit, expected_polls, legacy_token=False):
    class Handler(BaseHTTPRequestHandler):
        polls = 0
        bad_paths = []

        def log_message(self, *args):
            pass

        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            if self.path != "/api/triggers/launch":
                self.send_error(404)
                return
            if self.headers.get("X-Internal-Secret") != "stub-trigger-secret":
                self.send_error(401)
                return
            self.respond({"data": {"instanceNo": "a42724bb-417e-4b7c-b7d8-70f21a7304dd"}})

        def do_GET(self):
            cookies = SimpleCookie(self.headers.get("Cookie", ""))
            if not cookies.get("batch_console_token") or cookies["batch_console_token"].value != "stub-session-token":
                self.send_error(401)
                return
            target = urlsplit(self.path)
            query = parse_qs(target.query)
            if (target.path != "/api/console/queries/workflow-runs"
                    or query.get("requestId") != ["a42724bb-417e-4b7c-b7d8-70f21a7304dd"]
                    or not query.get("tenantId")):
                type(self).bad_paths.append(self.path)
                self.send_error(400)
                return
            index = min(type(self).polls, len(statuses) - 1)
            type(self).polls += 1
            if statuses[index] == "RATE_LIMIT":
                self.send_error(429)
                return
            items = [] if statuses[index] is None else [{"id": 31, "runStatus": statuses[index]}]
            self.respond({"data": {"items": items, "total": len(items)}})

        def respond(self, data):
            body = json.dumps(data).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            if self.command == "GET" and type(self).polls == 1:
                self.send_header("Set-Cookie", "XSRF-TOKEN=stub-csrf; Path=/")
            self.end_headers()
            self.wfile.write(body)

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        command = [
            str(ROOT / "mvnw"), "-o", "-ntp", "-f", "load-tests/pom.xml", "gatling:test",
            "-Dsimulation=io.github.pinpols.batch.loadtest.simulations.LaunchPipelineCompletionSimulation",
            f"-Dtrigger.baseUrl={base_url}", f"-Dconsole.baseUrl={base_url}",
            "-DworkflowDefinitionId=1", "-Dpipeline.completion.users=1", "-Dramp.seconds=1",
            "-Dpipeline.maxPolls=2", "-Dpipeline.pollIntervalSec=1", "-Dslo.maxErrorPct=0",
            "-Dinternal.secret=stub-trigger-secret",
            "-Dconsole.authToken=Bearer stub-session-token" if legacy_token
            else "-Dconsole.accessToken=stub-session-token",
        ]
        try:
            result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=120)
        finally:
            server.shutdown()
            thread.join()

    output = result.stdout + result.stderr
    global_http_ok = "Global: count of failed events is 0.0 : true" in output
    scenario_evaluated = "pipeline_completion: count of failed events is 0.0" in output
    exit_matches = (result.returncode == 0) if expected_exit == 0 else (result.returncode != 0)
    http_result_matches = global_http_ok == (statuses[0] != "RATE_LIMIT")
    if not (exit_matches and http_result_matches and scenario_evaluated and Handler.polls == expected_polls
            and not Handler.bad_paths):
        raise AssertionError(
            f"{name}: exit={result.returncode}, polls={Handler.polls}, "
            f"http_ok={global_http_ok}, scenario_evaluated={scenario_evaluated}, "
            f"bad_paths={Handler.bad_paths}\n{output}"
        )
    print(f"通过：{name}（轮询 {Handler.polls} 次）")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--launch-only", action="store_true", help="只验证零轮询的受理模式")
    parser.add_argument("--rate-limit", action="store_true", help="只验证轮询被限流")
    parser.add_argument("--cookie", action="store_true", help="只验证登录 Cookie")
    parser.add_argument("--legacy-cookie", action="store_true", help="只验证旧鉴权参数兼容")
    args = parser.parse_args()
    if args.rate_limit:
        run_case(*CASES[-1])
    elif args.cookie or args.legacy_cookie:
        run_case(*CASES[2], legacy_token=args.legacy_cookie)
    else:
        run_launch_only_case()
        run_launch_only_case(accepted=False)
    if not args.launch_only and not args.rate_limit and not args.cookie and not args.legacy_cookie:
        for case in CASES:
            run_case(*case)
