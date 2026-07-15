#!/usr/bin/env python3
"""Render alertmanager.yml route tree from batch.alert_routing_config (迁移方案 §6.4).

alert_routing_config(V43)是 AM route 的关系型镜像(schema 注释即写 "Aligns with
Alertmanager route semantics")。本生成器把该表渲染成 alertmanager.yml 的 route 树 + receiver 列表,
使前端 CRUD 有了运行时意义:改配置 → 重新生成 → amtool check-config → reload AM。

不新增运行时路由消费者(路由交给 AM 进程);v1 手动触发,不做实时 watch。

用法:
  # 从 JSON 行(测试/离线)渲染:
  gen-alertmanager-config.py --input rows.json --output alertmanager.yml
  # 从数据库读取 enabled 行渲染(需 psycopg2):
  gen-alertmanager-config.py --db-url postgresql://... --output alertmanager.yml
  # 渲染后跑 amtool 校验:
  gen-alertmanager-config.py --input rows.json --amtool

行字段(对齐 V43):route_code, team, alert_group, severity, receiver,
group_by(逗号分隔或留空), group_wait_seconds, group_interval_seconds,
repeat_interval_seconds, enabled。
"""
import argparse
import json
import subprocess
import sys
import tempfile

# AM 出口端点(容器网络内 console-api);每个 receiver 的 webhook 指向 /internal/am-notify/{receiver}。
AM_NOTIFY_BASE = "http://batch-console-api:18080/internal/am-notify"
BEARER_PLACEHOLDER = "REPLACE_WITH_AM_NOTIFY_BEARER_TOKEN"
DEFAULT_RECEIVER = "batch-default"
DEFAULT_GROUP_BY = ["alertname", "team", "alert_group", "severity"]

# fbs severity(大写) → AM severity(小写词形),与 AlertLabels.amSeverity 一致。
SEVERITY_WORD = {
    "INFO": "info",
    "WARN": "warning",
    "WARNING": "warning",
    "ERROR": "error",
    "CRITICAL": "critical",
}


def am_severity(sev):
    if not sev:
        return None
    return SEVERITY_WORD.get(sev.strip().upper(), sev.strip().lower())


def load_rows(args):
    if args.input:
        with open(args.input, "r", encoding="utf-8") as f:
            return json.load(f)
    if args.db_url:
        import psycopg2  # 延迟导入:离线渲染不需要驱动
        import psycopg2.extras

        conn = psycopg2.connect(args.db_url)
        try:
            cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
            cur.execute(
                "select route_code, team, alert_group, severity, receiver, group_by, "
                "group_wait_seconds, group_interval_seconds, repeat_interval_seconds, enabled "
                "from batch.alert_routing_config where enabled = true "
                "order by tenant_id, route_code"
            )
            return [dict(r) for r in cur.fetchall()]
        finally:
            conn.close()
    raise SystemExit("需要 --input <json> 或 --db-url <postgresql://...>")


def secs(row, key, default):
    v = row.get(key)
    return int(v) if v not in (None, "") else default


def render(rows):
    # 稳定排序:route 树输出确定,便于 snapshot 契约测试。
    enabled = [r for r in rows if r.get("enabled", True)]
    enabled.sort(key=lambda r: str(r.get("route_code", "")))

    lines = []
    lines.append("global:")
    lines.append("  resolve_timeout: 5m")
    lines.append("")
    lines.append("route:")
    lines.append("  receiver: %s" % DEFAULT_RECEIVER)
    lines.append("  group_by: [%s]" % ", ".join(DEFAULT_GROUP_BY))
    lines.append("  group_wait: 30s")
    lines.append("  group_interval: 5m")
    lines.append("  repeat_interval: 2h")
    if enabled:
        lines.append("  routes:")
        for r in enabled:
            matchers = []
            if r.get("alert_group"):
                matchers.append('alert_group="%s"' % r["alert_group"])
            if r.get("team"):
                matchers.append('team="%s"' % r["team"])
            sev = am_severity(r.get("severity"))
            if sev:
                matchers.append('severity="%s"' % sev)
            lines.append("    - matchers:")
            for m in matchers:
                lines.append("        - %s" % m)
            lines.append("      receiver: %s" % r["receiver"])
            gb = r.get("group_by")
            if gb:
                cols = [c.strip() for c in str(gb).split(",") if c.strip()]
                if cols:
                    lines.append("      group_by: [%s]" % ", ".join(cols))
            lines.append("      group_wait: %ds" % secs(r, "group_wait_seconds", 30))
            lines.append("      group_interval: %ds" % secs(r, "group_interval_seconds", 300))
            lines.append("      repeat_interval: %ds" % secs(r, "repeat_interval_seconds", 3600))
    lines.append("")

    # receivers:default + 每个 route 的 receiver(去重、稳定序);webhook 指向 am-notify 端点。
    receivers = [DEFAULT_RECEIVER]
    for r in enabled:
        rc = r.get("receiver")
        if rc and rc not in receivers:
            receivers.append(rc)
    lines.append("receivers:")
    for rc in receivers:
        lines.append("  - name: %s" % rc)
        lines.append("    webhook_configs:")
        lines.append("      - url: %s/%s" % (AM_NOTIFY_BASE, rc))
        lines.append("        send_resolved: true")
        lines.append("        http_config:")
        lines.append("          authorization:")
        lines.append("            type: Bearer")
        lines.append("            credentials: %s" % BEARER_PLACEHOLDER)
    lines.append("")

    # inhibit:critical 压 warning(同 alertname/team/alert_group)。
    lines.append("inhibit_rules:")
    lines.append("  - source_matchers:")
    lines.append('      - severity="critical"')
    lines.append("    target_matchers:")
    lines.append('      - severity="warning"')
    lines.append("    equal: [alertname, team, alert_group]")
    lines.append("")
    return "\n".join(lines)


def validate_receivers_have_channels(rows, channel_codes):
    """生成器校验:每个 receiver 必须有对应 notification_channel,防 am.notify.skipped 静默蒸发(§6.4)。"""
    missing = []
    for r in rows:
        if not r.get("enabled", True):
            continue
        rc = r.get("receiver")
        if rc and channel_codes is not None and rc not in channel_codes:
            missing.append(rc)
    return sorted(set(missing))


def main():
    ap = argparse.ArgumentParser(description="Render alertmanager.yml from alert_routing_config")
    ap.add_argument("--input", help="JSON array of routing config rows")
    ap.add_argument("--db-url", help="postgresql://... (reads enabled rows)")
    ap.add_argument("--output", help="output file (default: stdout)")
    ap.add_argument("--amtool", action="store_true", help="run `amtool check-config` on output")
    args = ap.parse_args()

    rows = load_rows(args)
    rendered = render(rows)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(rendered)
    else:
        sys.stdout.write(rendered)

    if args.amtool:
        target = args.output
        if not target:
            tf = tempfile.NamedTemporaryFile("w", suffix=".yml", delete=False, encoding="utf-8")
            tf.write(rendered)
            tf.close()
            target = tf.name
        rc = subprocess.call(["amtool", "check-config", target])
        if rc != 0:
            raise SystemExit("amtool check-config failed (rc=%d)" % rc)
        sys.stderr.write("amtool check-config OK\n")


if __name__ == "__main__":
    main()
