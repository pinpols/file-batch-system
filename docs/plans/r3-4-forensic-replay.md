# Plan #4 — Forensic Bundle 本地回放工具

> r3 validation-infra · 优先级 P1 · 估时 1.5 天

## 目标
ADR-022 `forensic_export` 已能导出生产证据包(job_instances + batch_day_audits + manifest + sha256),
本计划补"回放"侧 — 把生产证据包还原到本地 sim 跑一遍当前代码,SQL 比对结果,看新代码是否会改判。

## 价值
- **回归早期发现**:每周拉昨日生产证据包本地回放,比 staging 灰度更早暴露代码改判
- **复现真生产 bug**:线上诡异 case 直接 forensic export → 本地 replay 调试,不用扰动 prod
- **辅助 ADR-021 对账**:对账场景的"业务是否真做错"可通过回放对比验证

## 范围

**In scope**:
- `replay-forensic-bundle.sh` 主入口(已有骨架,补实现)
- bundle 解包 + sha256 校验 + schema 映射
- 还原 forensic data 到本地 sim 临时 namespace
- 跑当前代码做 replay(同 bizDate + 同租户)
- SQL diff harness:replay 结果 vs 证据包真生产结果,字段级 diff
- HTML/Markdown 报告

**Out of scope**:
- 不做生产数据脱敏(forensic export 侧已脱)
- 不做 OSS 自动拉(初版只接 zip 路径 + 后续接 OSS)
- 不做时间窗口比对(同 bizDate 一一对照)

## 设计

### Bundle 结构(已由 ForensicExportService.generate 定义)
```
forensic-bundle-{exportId}.zip
├── manifest.json              # exportId / tenantId / bizDate range / sha256
├── job-instances.csv          # 期间所有 job_instance 行 + 关联 step 历史
├── batch-day-audits.csv       # 同期 batch_day_operation_audit
└── attestation.json           # sha256 + 导出时间戳
```

### 回放流程
```
[bundle.zip]
   ↓ unzip + sha256 verify
[unpacked/]
   ↓ schema map: forensic.* → sim batch_business.* (临时 ns)
[sim_replay_${exportId}]
   ↓ orchestrator launch with original trigger params
[live system]
   ↓ wait until terminal status
[result snapshot]
   ↓ SQL diff vs bundle 原 job_instances.csv
[replay-report.md]
```

### Diff 维度
| 字段 | 比对方式 | 失败处理 |
|---|---|---|
| `instance_status` | 直接 == | 报"判定改变"(最严重) |
| `processed_count` | 容差 ±5% | 报"统计漂移"(警告) |
| `started_at` / `finished_at` | 相对耗时(±20%) | 报"性能漂移"(信息) |
| `error_code` / `error_message` | 直接 == | 报"错误路径改变" |
| `result_payload` JSONB | structural diff(jq) | 报字段级变化 |

### 报告输出
```markdown
# Replay Report — exportId={...}, bizDate=2026-05-28

## Summary
- Total: 1024 job_instances
- Identical: 1018 (99.4%)
- Status changed: 2 ⚠️
- Count drift > 5%: 3
- Time drift > 20%: 1

## Detail
### Status changes (2)
- jobInstance#42 (TA_IMPORT_CUSTOMER):  prod=SUCCESS  replay=FAILED
  Caused by: new validation rule introduced in commit abc123
  ...
```

## 步骤拆解

### Step 1 — bundle 解包 + 校验(2h)
- [ ] `unzip` + sha256 校验 manifest 内 attestation
- [ ] 解析 manifest.json,出 `tenantId / bizDate range / jobCodes` 摘要

### Step 2 — schema 映射 + 数据还原(3h)
- [ ] 临时 namespace 策略:`sim_replay_{exportId}` schema,跑完后 drop
- [ ] CSV → SQL COPY 导入 forensic.job_instance_snapshot 等表
- [ ] 关联 step / partition 历史也还原(保证 trigger params 完整)

### Step 3 — replay 触发(2h)
- [ ] 从 manifest 反推 launch request(tenantId / jobCode / bizDate / params)
- [ ] 通过 console-api `/internal/launch` 触发(走 sim console-api 18080)
- [ ] 轮询 `instance_status` 进终态

### Step 4 — diff harness(3h)
- [ ] 抽取 replay 结果 snapshot(同 forensic 的 csv 格式)
- [ ] diff 逻辑实现:5 个维度,容差可配
- [ ] 生成 markdown 报告

### Step 5 — 真实测试(2h)
- [ ] 用 sim 跑一个 ta 租户案例,生成 forensic bundle
- [ ] 改一个 bug 模拟"代码改判"
- [ ] 跑 replay,验证报告能识别改判

## 验收标准
- [ ] 用真 sim bundle 端到端跑通,报告输出正确
- [ ] 故意改业务逻辑能被识别(测试用)
- [ ] 报告里 5 个 diff 维度都有真实样本
- [ ] `scripts/local/replay-forensic-bundle.sh --help` 输出清晰用法
- [ ] 文档:`docs/runbook/forensic-replay-howto.md` 含每周操作流程

## 风险 / 依赖
- **依赖**:
  - sim 必须可用(`scripts/sim/`)
  - `console-api` 跑在 18080
  - forensic export 已经能正常导(已 production-ready per ADR-022)
- **风险**:
  - schema 演化 — forensic 包是某 schema 版本,replay 时新 schema → 失败
  - **缓解**:每个 bundle 带 schema version,replay 时检查 + warn
- **隐私风险**:
  - forensic 包含真生产数据(已脱敏但仍敏感)
  - **缓解**:replay 工具产物全在 `sim_replay_*` 临时 ns,跑完 drop;报告默认脱敏

## 检查点
| 里程碑 | 产出 |
|---|---|
| M1 | 解包 + 校验 + 数据还原 |
| M2 | replay 触发 + 终态等待 |
| M3 | diff + 报告生成 |
| M4 | 端到端真测 + 文档化 |
