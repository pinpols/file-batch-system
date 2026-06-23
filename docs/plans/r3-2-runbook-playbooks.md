# Plan #2 — 运维演练剧本

> r3 validation-infra · 优先级 P0 · 估时 0.5 天

## 目标
为 on-call 凌晨 3 点的真实故障写"抄写级"剧本,每篇回答 3 个问题:**怎么发现 → 怎么定位 → 怎么恢复**。
现有 `docs/runbook/incident-response.md` 是总框架,缺具体场景的逐步操作。

## 价值
- on-call 不需要进代码 / 查文档,3 分钟内开始恢复
- 演练剧本与 chaos IT(Plan #1)配对:剧本写"怎么救",IT 验"救得回"
- 复盘时直接关联剧本编号,知识沉淀闭环

## 范围

**5 篇剧本**:
| 剧本 | 场景 | 优先级 |
|---|---|---|
| `pg-primary-failover.md` | PG 主库挂,从库切主 | P0 |
| `redis-shedlock-down.md` | Redis 全断,ShedLock 切 jdbc fallback | P0 |
| `kafka-rebalance-stuck.md` | Consumer group lag 飙高,rebalance 卡 | P1 |
| `outbox-stuck-publishing.md` | `outbox_event.publish_status='PUBLISHING'` 长期停滞自愈 | P1 |
| `batch-day-not-settling.md` | `batch_day_instance` 卡 SETTLING 不进终态 | P2 |

**Out of scope**:
- 不写"做项目 / 发版"类 runbook(那是 `runbook/releasing.md` 等的事)
- 不做应用代码改动(纯文档 + 必要的 ops SQL/命令片段)

## 剧本结构(每篇统一)

```markdown
# <场景名>

## TL;DR
一句症状 + 一行修复命令。

## 怎么发现
- Prometheus alert 名 + 阈值
- Grafana 面板 URL
- 用户报错关键字

## 怎么定位
1. 看 X 指标 / 日志
2. 跑 Y SQL / kafka / redis 命令
3. 关键决策点

## 怎么恢复(按风险升序)
### 方案 A:无损自愈(2 min)
### 方案 B:有损降级(10 min)
### 方案 C:最后手段(破坏性操作) — 回滚版本

## 事后
- 写 incident-response 关联本剧本
- 阈值是否需调
- 剧本走不通 → 补一篇新剧本
```

## 步骤拆解

### Step 1 — 5 篇 P0/P1 剧本(3h)
按表内顺序,每篇 ~30 min:阅码确认现有路径 + 整理实际操作命令。

### Step 2 — P2 剧本 + 模板细化(1h)
P2 一篇 + 把模板中 "Prometheus alert 名 / Grafana URL" 占位符替换成本仓真实清单。

### Step 3 — 关联 incident-response(0.5h)
- `incident-response.md` 顶部加目录指向 playbooks/
- 每篇剧本末尾加"事后"链接回 incident-response

## 验收标准
- [ ] 5 篇剧本全 PR,每篇按模板结构
- [ ] `playbooks/README.md` 指向具体剧本(从清单变为目录)
- [ ] `incident-response.md` 引用 playbooks/
- [ ] 至少 1 篇剧本能跟 Plan #1 的 chaos IT 配对(剧本说"怎么救",IT 验"救得回")

## 风险 / 依赖
- **依赖**:需要查 `application.yml` / `BatchShedLockAutoConfiguration` / `OutboxPublishCircuitBreaker` 等代码,了解真实降级路径
- **风险**:剧本过期(代码改了但剧本没改)→ 每篇头部加"最后核对版本",PR 改触发点(`outbox_event`、`batch_day_instance` schema)时必须同步剧本

## 检查点
| 里程碑 | 产出 |
|---|---|
| M1 | 模板敲定 + 第 1 篇(PG failover)完成,样本可对齐 |
| M2 | 5 篇全部完成 |
| M3 | incident-response 整合,搜索路径通 |
