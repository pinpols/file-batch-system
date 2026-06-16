#!/usr/bin/env bash
# =========================================================
# analyze-replay.sh — forensic replay 5 维度 diff harness
#
# 独立可调用,输入:
#   --prod   <prod-job-instances.json>   ← bundle 里的 job-instances.json
#   --replay <replay-snapshot.json>      ← replay 后 SELECT 出的 json_agg
#   --out    <diff.json>                 ← 5 维度 diff 结果(供 markdown 报告渲染)
#
# 5 维度(对齐 plan §Diff 维度):
#   1. instance_status        严格 ==,任何差异 → statusDiff=true
#   2. processed_count        ±5% 容差,超出 → countDriftPct
#   3. started_at→finished_at ±20% 容差,超出 → timeDriftPct
#   4. error_code / message   严格 ==,差异 → errorDiff=true
#   5. result_payload JSONB   structural diff,差异路径 → payloadDiffPaths
#
# 容差可通过环境变量覆盖:
#   REPLAY_COUNT_TOL_PCT=5
#   REPLAY_TIME_TOL_PCT=20
#
# 用法:
#   bash scripts/local/analyze-replay.sh --prod a.json --replay b.json --out diff.json
# =========================================================

set -uo pipefail

COUNT_TOL="${REPLAY_COUNT_TOL_PCT:-5}"
TIME_TOL="${REPLAY_TIME_TOL_PCT:-20}"

PROD=""; REPLAY=""; OUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prod)   PROD="$2"; shift 2;;
    --replay) REPLAY="$2"; shift 2;;
    --out)    OUT="$2"; shift 2;;
    --help|-h) sed -n '2,28p' "$0"; exit 0;;
    *) echo "[fatal] unknown arg: $1" >&2; exit 1;;
  esac
done
[[ -f "$PROD"   ]] || { echo "[fatal] --prod 文件不存在: $PROD" >&2; exit 1; }
[[ -f "$REPLAY" ]] || { echo "[fatal] --replay 文件不存在: $REPLAY" >&2; exit 1; }
[[ -n "$OUT" ]]    || { echo "[fatal] --out 未给" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "[fatal] 缺 python3" >&2; exit 1; }

python3 - "$PROD" "$REPLAY" "$OUT" "$COUNT_TOL" "$TIME_TOL" <<'PY'
import json, sys, math
from collections import OrderedDict

prod_path, replay_path, out_path, count_tol, time_tol = sys.argv[1:6]
count_tol = float(count_tol)
time_tol  = float(time_tol)

def load(p):
    with open(p, 'r', encoding='utf-8') as f:
        s = f.read().strip()
    if not s:
        return []
    data = json.loads(s)
    return data if data is not None else []

prod   = load(prod_path)
replay = load(replay_path)

def key_of(row):
    # prod row 字段可能是 camelCase(JsonUtils),replay 来自 SQL alias 是 snake/lower
    jc = row.get('jobCode') or row.get('job_code')
    bd = row.get('bizDate') or row.get('biz_date')
    if isinstance(bd, str) and 'T' in bd:
        bd = bd.split('T', 1)[0]
    return (jc, str(bd) if bd else None)

def status_of(r):
    return r.get('instanceStatus') or r.get('instance_status')

def count_of(r):
    # processed_count 在 prod 是 success+failed partition;replay 我们 SQL 已算好
    if 'processed_count' in r:
        return r.get('processed_count') or 0
    s = r.get('successPartitionCount') or 0
    f = r.get('failedPartitionCount') or 0
    return (s or 0) + (f or 0)

def iso_to_sec(v):
    if not v: return None
    if isinstance(v, (int, float)):
        # epoch 毫秒?小于 1e12 当作秒
        return float(v) if v < 1e12 else float(v)/1000.0
    s = str(v).replace('Z', '+00:00')
    try:
        from datetime import datetime
        return datetime.fromisoformat(s).timestamp()
    except Exception:
        return None

def duration_of(r):
    a = iso_to_sec(r.get('startedAt')  or r.get('started_at'))
    b = iso_to_sec(r.get('finishedAt') or r.get('finished_at'))
    if a is None or b is None: return None
    return max(0.0, b - a)

def error_of(r):
    code = r.get('errorCode') or r.get('error_code')
    msg  = r.get('errorMessage') or r.get('error_message')
    # 不少情况下错误信息塞在 result_summary;允许 fallback
    if not code and not msg:
        rs = r.get('resultSummary') or r.get('result_summary')
        if rs and isinstance(rs, str) and ('error' in rs.lower() or 'fail' in rs.lower()):
            return ('', rs[:200])
    return (code or '', msg or '')

def payload_of(r):
    v = r.get('resultPayload') or r.get('result_payload') \
        or r.get('paramsSnapshot') or r.get('params_snapshot') \
        or r.get('resultSummary')  or r.get('result_summary')
    if v is None: return None
    if isinstance(v, str):
        try: return json.loads(v)
        except Exception: return v
    return v

def payload_diff_paths(a, b, prefix=''):
    paths = []
    if type(a) != type(b):
        return [prefix or '<root>']
    if isinstance(a, dict):
        for k in set(a.keys()) | set(b.keys()):
            if k not in a or k not in b:
                paths.append(f"{prefix}.{k}" if prefix else k)
            else:
                paths.extend(payload_diff_paths(a[k], b[k], f"{prefix}.{k}" if prefix else k))
    elif isinstance(a, list):
        if len(a) != len(b):
            paths.append(f"{prefix}[len]")
        for i, (x, y) in enumerate(zip(a, b)):
            paths.extend(payload_diff_paths(x, y, f"{prefix}[{i}]"))
    else:
        if a != b:
            paths.append(prefix or '<root>')
    return paths

# 用 (jobCode, bizDate) 去重,保留最新(prod 有 multi-attempts 时取最新 finishedAt)
def index(rows):
    idx = {}
    for r in rows:
        k = key_of(r)
        if k[0] is None: continue
        prev = idx.get(k)
        if prev is None:
            idx[k] = r
        else:
            # 取 finishedAt 更晚的
            ta = iso_to_sec(r.get('finishedAt')    or r.get('finished_at'))    or 0
            tb = iso_to_sec(prev.get('finishedAt') or prev.get('finished_at')) or 0
            if ta >= tb: idx[k] = r
    return idx

prod_idx   = index(prod)
replay_idx = index(replay)

summary = dict(total=0, identical=0, statusChanged=0, countDrift=0,
               timeDrift=0, errorChanged=0, payloadDrift=0, missingInReplay=0)
details = []

for k, prow in prod_idx.items():
    summary['total'] += 1
    rrow = replay_idx.get(k)
    item = OrderedDict()
    item['jobCode'] = k[0]
    item['bizDate'] = k[1]
    item['prodStatus']   = status_of(prow)
    item['replayStatus'] = status_of(rrow) if rrow else None
    item['prodCount']    = count_of(prow)
    item['replayCount']  = count_of(rrow) if rrow else None
    item['prodDurationSec']   = duration_of(prow)
    item['replayDurationSec'] = duration_of(rrow) if rrow else None
    pe, pm = error_of(prow);     item['prodError'] = (pe + ':' + pm)[:200] if (pe or pm) else None
    if rrow:
        re_, rm = error_of(rrow); item['replayError'] = (re_ + ':' + rm)[:200] if (re_ or rm) else None
    else:
        item['replayError'] = None

    if not rrow:
        summary['missingInReplay'] += 1
        item['statusDiff']     = True
        item['countDriftPct']  = None
        item['timeDriftPct']   = None
        item['errorDiff']      = False
        item['payloadDiff']    = False
        item['payloadDiffPaths'] = []
        details.append(item)
        continue

    # 1. status
    sd = item['prodStatus'] != item['replayStatus']
    item['statusDiff'] = sd
    if sd: summary['statusChanged'] += 1

    # 2. count drift
    pc = item['prodCount'] or 0
    rc = item['replayCount'] or 0
    if pc == 0 and rc == 0:
        item['countDriftPct'] = 0.0
    elif pc == 0:
        item['countDriftPct'] = 100.0
    else:
        item['countDriftPct'] = round((rc - pc) / pc * 100.0, 2)
    if abs(item['countDriftPct']) > count_tol:
        summary['countDrift'] += 1

    # 3. time drift
    pd_ = item['prodDurationSec']
    rd  = item['replayDurationSec']
    if pd_ and rd is not None and pd_ > 0:
        item['timeDriftPct'] = round((rd - pd_) / pd_ * 100.0, 2)
        if abs(item['timeDriftPct']) > time_tol:
            summary['timeDrift'] += 1
    else:
        item['timeDriftPct'] = None

    # 4. error code/message
    ped = error_of(prow); red = error_of(rrow)
    ed = ped != red
    item['errorDiff'] = ed
    if ed: summary['errorChanged'] += 1

    # 5. payload structural
    pp = payload_of(prow); rp = payload_of(rrow)
    paths = []
    if pp is None and rp is None:
        pd_diff = False
    elif pp is None or rp is None:
        pd_diff = True; paths = ['<root>']
    else:
        paths = payload_diff_paths(pp, rp)
        pd_diff = bool(paths)
    item['payloadDiff'] = pd_diff
    item['payloadDiffPaths'] = paths[:20]  # 截断噪声
    if pd_diff: summary['payloadDrift'] += 1

    if not (sd or item['errorDiff'] or pd_diff
            or abs(item['countDriftPct'] or 0) > count_tol
            or abs(item['timeDriftPct'] or 0) > time_tol):
        summary['identical'] += 1

    details.append(item)

out = OrderedDict()
out['summary']  = summary
out['tolerances'] = {'countPct': count_tol, 'timePct': time_tol}
out['details'] = details
with open(out_path, 'w', encoding='utf-8') as f:
    json.dump(out, f, ensure_ascii=False, indent=2)

print(f"analyze: total={summary['total']} identical={summary['identical']} "
      f"statusChanged={summary['statusChanged']} countDrift={summary['countDrift']} "
      f"timeDrift={summary['timeDrift']} errorChanged={summary['errorChanged']} "
      f"payloadDrift={summary['payloadDrift']} missing={summary['missingInReplay']}")
PY
