# BE Acceptance Runbook

> 后端「启动 → 单测 / IT / E2E → 真实数据严格验证 → 违约扫描 → 重启 FE」一条龙验收 SOP。
> 权威 entry:`scripts/local/be-acceptance.sh`。本文档给项目成员看(不只 Claude),配套 skill `~/.claude/skills/be-acceptance/`。

## 何时跑

| 场景 | 命令 | 工期 |
|---|---|---|
| 完整全链路验收(发版 / 周末回归) | `bash scripts/local/be-acceptance.sh` | 50-70 min |
| 加速(本地日常,容忍并发资源争用) | `bash scripts/local/be-acceptance.sh --parallel` | 25-35 min |
| 只跑真实数据严格验证(无需重新跑测试) | `bash scripts/local/be-acceptance.sh --strict-only` | 30 s(+`--maintenance-switch` ~3 min) |
| 测试 + 修失败,不动 FE | `bash scripts/local/be-acceptance.sh --tests-only` | 25-40 min |
| 上次失败,从断点续跑 | `bash scripts/local/be-acceptance.sh --resume` | 看断点 step |

## flag 矩阵

```bash
# 选定步骤
--steps=5,6,10         # 只跑列出的 step
--from-step=5          # 从 step 5 起跑到底
--skip=3,4             # 跳过 step 3 + 4

# 续跑与状态
--resume               # 读 .be-acceptance-state,从 last_step + 1 起跑

# 并发
--parallel             # 单测 / IT / E2E 并发跑(三段同时启)

# Backlog 联动
--auto-issue-dry-run   # 验证 gh 就位,打印将发的 issue 命令(不真发,安全)
--auto-issue           # 真调 gh issue create,慎用
```

## 11 个 step 总览

| Step | 内容 | 串行耗时 | 失败可跳? |
|---|---|---|---|
| 0 | docker 容器 + 端口 + 磁盘 + tunnel 预检 | 5 s | ❌ 失败必须修 |
| 1 | mvn package + restart BE + 3min 启动日志守候 | 8-10 min | ❌ 启动挂直接停 |
| 2 | 单测 `run-tests.sh --unit --skip-build` | ~1 min | ⚠️ 记 backlog |
| 3 | IT `run-tests.sh --it --skip-build`(7 模块) | 15-20 min | ⚠️ 记 backlog |
| 4 | E2E(30 个 IT) | 25-35 min | ⚠️ 记 backlog |
| 5 | `strict-verify.sh` 15 项真实数据验证 | 30 s | ⚠️ 记 backlog |
| 6 | 扫近 3 天违约(FQN / 异常 / 命名 等) | 30 s | ⚠️ 记 backlog |
| 7 | FE build + preview 重启 | 1-2 min | ❌ FE 起不来必须修 |
| 8 | tunnel 自动探测 + 可达性测试 | 5 s | ⚠️ 探测不到 SKIP |
| 9 | 端到端冒烟(BE health + FE 首屏 + tunnel) | 10 s | ❌ 失败立即停 |
| 10 | 汇总写 `docs/backlog/be-acceptance-{date}.md` + 可选 `gh issue create` | 1 min | — |
| 11 | 提交 `.be-acceptance-state` 标记成败 | 5 s | — |

## 自动行为(2026-05-21)

### tunnel URL 自动探测
之前硬编码 cloudflared trycloudflare URL。现在每次 step 8 都从 `pgrep -fl cloudflared` 找进程,grep log 抓 `https://*.trycloudflare.com`,自动测可达性。
- 探测不到 → 静默 SKIP step 8 + 9 tunnel 段(本地 BE/FE 可达不受影响)
- 探测到但 HTTP ≠ 200 → 失败记 backlog

### resume 续跑
每个 step 完成后写 `.be-acceptance-state`:
```
last_step=5
last_status=ok        # 或 fail
last_started_at=2026-05-21T10:00:00Z
last_finished_at=2026-05-21T10:30:00Z
```
`--resume` 时:
- `last_status=ok` → 静默不跑(已成功无需重跑)
- `last_status=fail` → 从 `last_step` 起跑(包含失败那步重试)

### auto-issue
step 10 汇总后:
- 无 `--auto-issue*` → 只写 `docs/backlog/be-acceptance-{date}.md`
- `--auto-issue-dry-run` → 写 backlog + 打印将执行的 `gh issue create` 命令(不真发,验证 gh 就位)
- `--auto-issue` → 写 backlog + 真调 `gh issue create` 每条未解决项发 1 个 issue

## 前置环境

```bash
# 1) docker 容器(PG / Kafka / Redis / MinIO)
docker ps --format '{{.Names}}' | grep -E "batch-postgres|batch-kafka|batch-redis|batch-minio" | wc -l
# 期望 ≥ 4;否则 cd 项目根 docker compose up -d

# 2) 端口占用
for port in 18080 18081 18082 18083 18084 18085 18086 5173; do
  lsof -i :$port -sTCP:LISTEN >/dev/null 2>&1 && echo "  PORT $port: occupied"
done
# 18080(BE) / 5173(FE) 占用是正常,其它 worker 端口需 start-all.sh 起齐

# 3) 磁盘空间(测试 + log 需要 ~3GB)
df -h /Users/dengchao/Downloads/file-batch-system | tail -1 | awk '{print "free:", $4}'

# 4) tunnel(可选)
pgrep -fl cloudflared && echo "tunnel: running" || echo "tunnel: not active(隧道验证段跳过)"
```

## 失败分诊

按"工具链 / 环境 / 代码 bug"三类分类,只重跑失败的:

| 类型 | 例 | 处理 |
|---|---|---|
| 工具链 | Mockito + JDK 25 不兼容 / Spotless plugin 内部 NPE | backlog 跳过(等 plugin 升级) |
| 环境 | docker 容器没起 / 端口占 / 磁盘满 | 先解决环境再续跑 |
| 代码 bug | 真测试失败 + 业务逻辑错 | 修代码 → 单跑该测试 → 续跑剩余 |

## 输出位置

| 产物 | 路径 |
|---|---|
| 各 step 日志 | `logs/be-acceptance/step-{N}-{name}.log` |
| 状态文件 | `.be-acceptance-state`(git ignore) |
| backlog | `docs/backlog/be-acceptance-{YYYY-MM-DD}.md`(自动归档,可提交) |
| GH issues(可选) | 走 `gh issue create`,labels:`backlog,be-acceptance` |

## 关联文件

- `scripts/local/be-acceptance.sh`(449 行)— 一键 entry
- `scripts/local/strict-verify.sh`(177 行)— 真实数据 15 项严格验证
- `scripts/local/run-tests.sh` — 单测 / IT / E2E 分阶段子入口
- `~/.claude/skills/be-acceptance/SKILL.md` — Claude skill(`/be-acceptance` 唤起)
- `docs/backlog/be-acceptance-*.md` — 历次验收的 backlog 沉淀

## 维护规则

- 加新 step / flag → 同步改本 runbook + SKILL.md + 脚本内 usage 注释三处
- 加新 backlog 模板字段 → 同步改 step 10 模板生成段
- 默认串行不动,`--parallel` 是 opt-in(本地资源够才用)
