#!/usr/bin/env python3
# =====================================================================
# mock-partner.py
# 极简外部伙伴 HTTP 服务 mock，用于本地联调 API / API_PUSH dispatch 渠道。
# - 对任意 POST 返回 200 + {"accepted":true,"ts":...,"path":...}
# - 对任意 GET 返回 200 + {"ok":true,"path":...}
#
# 用法：python3 scripts/local/mock-partner.py 1080
# 然后把 file_channel_config（api_dispatch / api_push_dispatch）的
# target_endpoint 临时指向 http://localhost:1080/api/receive 或 /api/push。
# =====================================================================
from http.server import BaseHTTPRequestHandler, HTTPServer
import json, sys, time

class H(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args): pass
    def _ok(self, body):
        self.send_response(200)
        self.send_header("Content-Type","application/json")
        body_bytes = json.dumps(body).encode()
        self.send_header("Content-Length", str(len(body_bytes)))
        self.end_headers()
        self.wfile.write(body_bytes)
    def do_POST(self):
        length = int(self.headers.get("Content-Length","0"))
        self.rfile.read(length)  # drain
        receipt = {"accepted": True, "ts": int(time.time()*1000), "path": self.path}
        self._ok(receipt)
    def do_GET(self):
        self._ok({"ok": True, "path": self.path})

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv)>1 else 1080
    HTTPServer(("localhost", port), H).serve_forever()
