# 运维演练剧本 (Playbooks)

> 面向 on-call 的可直接照做的操作剧本。每篇剧本回答 3 个问题:**怎么发现 → 怎么定位 → 怎么恢复**。
> 上一级总框架:[`docs/runbook/incident-response.md`](../incident-response.md)

## 目录

| 剧本 | 场景 | 优先级 |
|---|---|---|
| [`pg-primary-failover.md`](pg-primary-failover.md) | PG 主库挂,从库切主 | P0 |
| [`redis-shedlock-down.md`](redis-shedlock-down.md) | Redis 全断,ShedLock 切 jdbc fallback | P0 |
| [`kafka-rebalance-stuck.md`](kafka-rebalance-stuck.md) | Consumer group lag 飙高,rebalance 卡 | P1 |
| [`outbox-stuck-publishing.md`](outbox-stuck-publishing.md) | `outbox_event.publish_status='PUBLISHING'` 长期停滞自愈 | P1 |
| [`batch-day-not-settling.md`](batch-day-not-settling.md) | `batch_day_instance` 卡 SETTLING 不进 SETTLED/FAILED | P2 |

## 怎么用

1. 收到告警 → 在表里找最贴的剧本(按"症状关键字"匹配)
2. 跟着 **TL;DR → 怎么发现 → 怎么定位 → 怎么恢复**(A/B/C 三档,按风险升序)走
3. **A = 无损自愈**(2 min,优先尝试);**B = 有损降级**(10 min);**C = 最后手段(破坏性操作)**(回滚,15+ min)
4. 跑完照"事后"清单:写 incident-response 关联、看 alert 阈值要不要调、剧本不通就补一篇新的

## 模板(写新剧本时拷贝)

```markdown
# <场景名>

> 优先级 PX · 最后核对版本:YYYY-MM · 配套 chaos IT:`XxxChaosIT`(TODO/已联调)

## TL;DR
一句症状 + 一行修复命令。

## 怎么发现
- Prometheus alert 名 + 阈值(没建就写 TODO 待 ops 补)
- Grafana 面板 URL(同上)
- 日志关键字(grep-able)
- 用户报错关键字

## 怎么定位
1. 看 X 指标 / 日志
2. 跑 Y SQL / kafka / redis 命令
3. 关键决策点(决定走 A/B/C)

## 怎么恢复
### 方案 A:无损自愈(2 min)
...
### 方案 B:有损降级(10 min)
...
### 方案 C:最后手段(破坏性操作,回滚版本)
...

## 事后
- 写 incident-response 关联本剧本
- alert 阈值是否需调
- 剧本走不通 → 补一篇新剧本

## 关联
- 代码 / schema / 配置 路径
- 上一级:[`docs/runbook/incident-response.md`](../incident-response.md)
```

## 联动

- **主入口**:[`docs/runbook/incident-response.md`](../incident-response.md)
- **系统总览**:`docs/architecture/`
- **配套 chaos IT**(Plan #1):`batch-common/src/test/java/io/github/pinpols/batch/testing/chaos/` — 每个 P0 剧本应有对应 IT 验证恢复路径
- **alert 名待补清单**(本仓尚未集中维护):由 ops 团队拉单维护;每篇剧本里出现的 `TODO 待 ops 补` 都是待办项

## 维护规则

- 代码改动触发(`outbox_event` / `batch_day_instance` / `shedlock` schema 变更、scheduler 调度间隔变化、ShedLock provider 默认值切换)→ **同 PR 同步剧本**
- 每篇剧本头部 "最后核对版本" 字段:reviewer 在剧本相关代码变更 PR 上必须更新
