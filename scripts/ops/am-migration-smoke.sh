#!/usr/bin/env bash
# Alertmanager 迁移全链 smoke(迁移方案 §7.1)。分两层:
#
#  [A] AM-contract 层(本脚本默认跑,无需全栈):启动独立 AM(v0.28.1)+ 渲染 route +
#      验证 emit publisher / silence bridge / resolved 三个 AM-facing JSON 契约 + amtool check-config。
#      —— 验证 fbs 侧产出的 JSON 能被真实 AM 接受、route 生成器输出合法。
#
#  [B] app 全链层(需全栈起来,--full):emit(POST /internal/alerts)→ alert_event 落库 →
#      AM 分组/路由 → POST /internal/am-notify/{receiver} → sender → notification_delivery_log →
#      mockserver 收到通知;租户分流、silence 桥接、inhibit、回滚开关。§7.1 步骤 2-10。
#      本层依赖 start-all + observability overlay,留给本地全栈/CI。
#
# 用法:
#   bash scripts/ops/am-migration-smoke.sh            # 跑 [A]
#   bash scripts/ops/am-migration-smoke.sh --full     # 跑 [A]+[B](需全栈)
#
# 注意:跑前 unset BATCH_ENV_COMMON_ROOT(worktree profile 污染坑,见项目备忘)。
set -euo pipefail

AMPORT="${ALERTMANAGER_SMOKE_PORT:-19093}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
TEMPLATE="$REPO/docker/observability/alertmanager-batch-template.yml"
CONTAINER="am-migration-smoke"

iso() { python3 -c "import datetime;print(datetime.datetime.now(datetime.timezone.utc).isoformat())"; }
iso_plus() { python3 -c "import datetime,sys;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(minutes=int(sys.argv[1]))).isoformat())" "$1"; }

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "== [A] AM-contract smoke =="
cleanup
docker run -d --name "$CONTAINER" -p "${AMPORT}:9093" \
  -v "${TEMPLATE}:/etc/alertmanager/alertmanager.yml:ro" \
  prom/alertmanager:v0.28.1 --config.file=/etc/alertmanager/alertmanager.yml >/dev/null

echo "-- waiting for AM readiness --"
for _ in $(seq 1 30); do
  curl -sf "http://localhost:${AMPORT}/-/ready" >/dev/null 2>&1 && break || sleep 1
done
curl -sf "http://localhost:${AMPORT}/-/ready" >/dev/null || { echo "FAIL: AM not ready"; docker logs "$CONTAINER" | tail; exit 1; }
echo "AM ready"

echo "-- amtool check-config: shipped template --"
docker exec "$CONTAINER" amtool check-config /etc/alertmanager/alertmanager.yml

echo "-- gen route from alert_routing_config sample + amtool check-config --"
python3 "$HERE/gen-alertmanager-config.py" --input "$HERE/testdata/alert-routing-sample.json" --output /tmp/am-gen-smoke.yml
docker cp /tmp/am-gen-smoke.yml "$CONTAINER:/tmp/am-gen-smoke.yml"
docker exec "$CONTAINER" amtool check-config /tmp/am-gen-smoke.yml

NOW="$(iso)"; END="$(iso_plus 120)"

echo "-- STEP: POST /api/v2/alerts (emit publisher firing shape) --"
curl -sf -X POST "http://localhost:${AMPORT}/api/v2/alerts" -H 'Content-Type: application/json' \
  -d "[{\"labels\":{\"alertname\":\"JOB_SLA_BREACH\",\"tenant\":\"ta\",\"severity\":\"critical\",\"service\":\"batch-orchestrator\",\"alert_group\":\"sla\",\"team\":\"batch-sla\"},\"annotations\":{\"summary\":\"SLA breached\",\"trace_id\":\"trace-xyz\",\"fingerprint\":\"fp-123\",\"alert_id\":\"42\"},\"startsAt\":\"${NOW}\"}]"
sleep 1

echo "-- STEP: GET /api/v2/alerts assert labels --"
curl -sf "http://localhost:${AMPORT}/api/v2/alerts" | python3 -c "import sys,json;a=json.load(sys.stdin);m=[x for x in a if x['labels'].get('alertname')=='JOB_SLA_BREACH'];assert m,'alert missing';l=m[0]['labels'];assert l['tenant']=='ta' and l['severity']=='critical' and l['alert_group']=='sla',l;print('OK alert labels',l)"

echo "-- STEP: POST /api/v2/silences (silence bridge shape) --"
SID="$(curl -sf -X POST "http://localhost:${AMPORT}/api/v2/silences" -H 'Content-Type: application/json' \
  -d "{\"matchers\":[{\"name\":\"alertname\",\"value\":\"JOB_SLA_BREACH\",\"isRegex\":false,\"isEqual\":true},{\"name\":\"tenant\",\"value\":\"ta\",\"isRegex\":false,\"isEqual\":true}],\"startsAt\":\"${NOW}\",\"endsAt\":\"${END}\",\"createdBy\":\"batch-console\",\"comment\":\"console silence id=42\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['silenceID'])")"
echo "OK silence created id=$SID"

echo "-- STEP: GET /api/v2/silences assert active --"
curl -sf "http://localhost:${AMPORT}/api/v2/silences" | python3 -c "import sys,json;s=json.load(sys.stdin);m=[x for x in s if x['id']=='${SID}'];assert m,'silence missing';assert m[0]['status']['state'] in ('active','pending'),m[0]['status'];print('OK silence',m[0]['status']['state'])"

echo "-- STEP: POST resolved (close bridge endsAt=now) --"
curl -sf -X POST "http://localhost:${AMPORT}/api/v2/alerts" -H 'Content-Type: application/json' \
  -d "[{\"labels\":{\"alertname\":\"JOB_SLA_BREACH\",\"tenant\":\"ta\",\"severity\":\"critical\",\"alert_group\":\"sla\",\"team\":\"batch-sla\"},\"endsAt\":\"${NOW}\"}]"
echo "OK resolved accepted"

echo "== [A] AM-contract smoke PASSED =="

if [[ "${1:-}" == "--full" ]]; then
  echo "== [B] app 全链层需全栈(start-all + observability overlay)。=="
  echo "   步骤:emit POST /internal/alerts → alert_event 落库 → AM 路由 → am-notify → sender →"
  echo "   notification_delivery_log(eventType=ALERTMANAGER)→ mockserver;租户分流/inhibit/回滚开关。"
  echo "   本脚本不代跑全栈;请在起好全栈后按 §7.1 步骤 2-10 断言(交本地全栈/CI)。"
fi
