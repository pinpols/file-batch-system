# Forensic Bundle 本地回放 — 操作手册

> ADR-022 forensic_export 已经能导生产证据包,本工具补"回放"侧:把生产包还原到本地 sim 跑当前代码,SQL 比对结果,看新代码是否会改判。
>
> 配套脚本:[`scripts/local/replay-forensic-bundle.sh`](../../scripts/local/replay-forensic-bundle.sh) / [`scripts/local/analyze-replay.sh`](../../scripts/local/analyze-replay.sh)
> 计划文档:[`docs/plans/r3-4-forensic-replay-2026-05-29.md`](../plans/r3-4-forensic-replay-2026-05-29.md)

## 何时用 / 何时不用

| 场景 | 用不用 |
|---|---|
| 每周回归:拉昨日生产 bundle 在本地跑当前 main,看是否改判 | ✅ |
| 线上诡异 case 复现:forensic export 单租户/单 bizDate → 本地 replay 调试 | ✅ |
| ADR-021 对账场景"业务是否真做错"二次验证 | ✅ |
| 实时合规审计 / SIEM | ❌(ADR-022 边界外) |
| 跨 bizDate 时间窗口比对 | ❌(本工具按同 bizDate 一一对照) |
| 跑 prod 数据直接修业务 | ❌(全部落在临时 schema,跑完 drop) |

## 每周回归流程(建议周一上午跑)

### 0. 前置环境

```bash
# sim + 本地 BE 必须先起
bash scripts/sim/02-start-sim.sh
bash scripts/local/start-all.sh   # 含 console-api on :18080

# 健康检查
bash scripts/local/health-check-infra.sh
curl -s http://localhost:18080/actuator/health | jq .
```

### 1. 拉昨日生产 forensic bundle

通过 Console 的 ROLE_ADMIN 已认证会话导出证据包并下载。下面的地址、cookie 文件、CSRF token 和幂等键由当前会话提供；操作人由服务端认证主体确定，不信任请求中的 `requestedBy`。

```bash
# 触发(prod console)
curl -X POST "$CONSOLE_BASE/api/console/forensic/export" \
  --cookie "$CONSOLE_COOKIE_FILE" \
  -H "X-XSRF-TOKEN: $CONSOLE_CSRF_TOKEN" \
  -H "Idempotency-Key: $EXPORT_REQUEST_ID" \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"ta","bizDateFrom":"2026-05-23","bizDateTo":"2026-05-23",
       "requestedBy":"weekly-replay"}'

# 返回 exportId,等几秒后下载
curl --cookie "$CONSOLE_COOKIE_FILE" -OJ \
  "$CONSOLE_BASE/api/console/forensic/export/fex_xxx/download?tenantId=ta"
```

### 2. 本地 replay

```bash
bash scripts/local/replay-forensic-bundle.sh ./fex_xxx.zip
```

观察输出:
- 报告路径打印在 `[Step 5/5]` 行
- 临时 schema `sim_replay_<exportId片段>_<本轮摘要>` 在成功后自动清理；失败或 `--keep` 时保留现场
- 解包目录 `build/forensic-replay/<bundle>.<pid>/unpacked/` 保留

退出码:
- `0`:全部 identical 或仅性能/数量漂移
- `2`:出现 **status 改判**、**error 路径变化**或回放结果缺失，保留现场
- `1` 或其它非零：查询/装载/触发/结果读取失败、已登记摘要不符或等待超时；保留现场，不清理为“成功”

### 3. 看报告 + 决策

打开 `replay-report.md`:

```markdown
## Summary
- Total compared: 1024
- Identical: 1018 (99.4%)
- Status changed: 2   ← ❗ 必查
- Count drift > 5%: 3
- Time drift > 20%: 1
- Error code/message changed: 0
- Payload JSONB structural drift: 5
- Missing in replay: 0
```

| 维度 | 容差 | 出现非 0 怎么办 |
|---|---|---|
| `statusChanged` | 0(严格) | **必排查**:对照 `git log` 找改判 commit,判定"修对了"还是"修错了" |
| `countDrift > 5%` | ±5% | 警告级:看是否新增过滤逻辑 / 去重规则 |
| `timeDrift > 20%` | ±20% | 信息级:性能漂移,sim 资源不可比,长期跟踪即可 |
| `errorChanged` | 0(严格) | **必排查**:错误路径改变 = 异常分类逻辑动了 |
| `payloadDrift` | 0(structural) | 字段级 diff,看 `payloadDiffPaths` 锁定改动字段 |
| `missingInReplay` | 0 | 触发失败 / launch 拒绝 / 调度未排上,先看 orchestrator 日志 |

### 4. 清理

```bash
# 临时 schema(脚本不自动 drop 是故意的:留现场)
psql -h localhost -p 15432 -U batch_user -d batch_business \
  -c "DROP SCHEMA IF EXISTS <脚本输出的本轮schema> CASCADE;"

# 解包目录
rm -rf build/forensic-replay/<bundle>.<pid>
```

## 选项 / 环境变量

| 选项 / env | 默认 | 含义 |
|---|---|---|
| `--keep` | off | 成功时也保留临时 schema；解包证据始终保留 |
| `--no-launch` | off | 只解包 + 装载,不触发 replay(用于先看 manifest / 数据预审) |
| `REPLAY_COUNT_TOL_PCT` | 5 | processed_count 容差 % |
| `REPLAY_TIME_TOL_PCT` | 20 | 耗时容差 % |
| `REPLAY_WAIT_TIMEOUT_SEC` | 300 | 等终态最大秒数 |
| `REPLAY_WAIT_INTERVAL_SEC` | 3 | 轮询间隔 |
| `REPLAY_WORK_DIR` | `build/forensic-replay` | 工作目录根 |
| `TRIGGER_BASE` | 共享环境的 `TRIGGER_BASE_URL` | 实际接收 Workflow 启动请求的 Trigger 地址 |
| `REPLAY_HTTP_TIMEOUT_SEC` | 30 | 单次启动请求最大等待秒数 |

## 故障排查

| 症状 | 原因 / 解决 |
|---|---|
| `unzip 失败` | bundle 损坏 — 重下,核对 sha256 |
| `manifest.exportId 缺失` | 不是 forensic bundle(可能是 OSS 误导出) |
| `WARN: Trigger 健康检查 000` | Trigger 未起；启动失败会以非零退出 |
| `触发失败 jobCode=xxx: STATE_CONFLICT` | orchestrator draining 或租户/job 未导入 sim |
| 所有行 `missingInReplay` | sim 里没建对应 tenant/job_definition — 先跑 `scripts/sim/03-import-tenants.sh` |
| 已登记 `sha256` 不一致 | 同一租户/导出 ID 已有不同摘要，拒绝回放；检查包和来源。异机导出在本地无登记时仅显示本地计算摘要，不冒称已认证来源 |
| 长时间 pending | launch 进了 PENDING_DEPENDENCY / 调度未排上 — 看 `batch.job_instance.instance_status` |

## 隐私约束(ADR-022)

- 原始证据快照写入 `sim_replay_*`；实际回放会通过 Trigger/Workflow/Worker 创建本地运行并执行业务写入。只在指定本地测试环境执行，`--no-launch` 才跳过启动。
- 报告默认不打印 `params_snapshot` / `result_payload` 原文,只列改动**字段路径**(`payloadDiffPaths`)
- 如需 raw 数据排查,直接连 `<脚本输出的本轮schema>.forensic_job_instances.snapshot` JSONB
- 跑完务必 `DROP SCHEMA` 清场,尤其工作机外接共享

## 5 维度 diff 速查表

| 维度 | 字段 | 判定 |
|---|---|---|
| 状态 | `instance_status` | `prod != replay` → statusDiff |
| 处理数 | `success_partition_count + failed_partition_count` | `\|drift\| > 5%` |
| 时间 | `finished_at - started_at` | `\|drift\| > 20%` |
| 错误 | `(error_code, error_message)` | tuple 不等 |
| Payload | `result_payload` / `params_snapshot` JSONB | structural diff,列改动路径 |

完整 plan / 设计依据:`docs/plans/r3-4-forensic-replay-2026-05-29.md`,ADR-022 §forensic_export。


## DQ执行证据的冷热回查

V347起，DQ检查结果随Job运行树在同一事务中归档。搬运前锁定实例与检查行，复制后逐行核对完整内容；缺失或冲突时整个事务回滚，热Job及检查证据均保留。规则定义保留在在线 `batch.data_quality_rule`，不依赖独立冷镜像。

归档后使用[只读回查SQL](../../scripts/db/inspect/select-data-quality-evidence.sql)，在已确认的平台数据库连接上提供精确租户及Job实例：

```bash
psql ... -v tenant_id=ta -v job_instance_id=123 -f scripts/db/inspect/select-data-quality-evidence.sql
```

该查询和应用Mapper采用相同冷热读取语义，返回规则标识、判定、指标、样本和时间；重复冷/热身份只返回一份。它不把终态Job重新插回热表，不触发Worker执行。历史证据恢复使用现有数据库备份/PITR流程；业务重放使用正式Replay入口，不能把回填运行表当作重放。

遇到归档冲突，按日志中的失败批次定位精确Job和检查ID，对照冷热完整行核查；保留两份证据，不覆盖冷行或手工删除热行来绕过校验。冷表目前不自动删除，随运行证据保留；若新增冷存TTL，必须先定义备份、恢复和引用保护，不能复用热保留期直接清理。
