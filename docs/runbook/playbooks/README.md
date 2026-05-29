# 运维演练剧本 (Playbooks)

> 凌晨 3 点 on-call 抄写级别。每篇剧本回答 3 个问题:**怎么发现 → 怎么定位 → 怎么恢复**。

## 待补剧本(r3 validation-infra)

| 文件 | 场景 | 优先级 |
|---|---|---|
| `pg-primary-failover.md` | PG 主库挂,从库切主 | P0 |
| `redis-shedlock-down.md` | Redis 全断,ShedLock 切 jdbc fallback | P0 |
| `kafka-rebalance-stuck.md` | Consumer group lag 飙高,rebalance 卡 | P1 |
| `outbox-stuck-publishing.md` | `outbox_event.publish_status='PUBLISHING'` 卡死自愈 | P1 |
| `batch-day-not-settling.md` | `batch_day_instance` 卡 SETTLING 不进 SETTLED/FAILED | P2 |

## 剧本模板(每篇都按这个结构写)

```markdown
# <场景名>

## TL;DR
一句话:症状 + 一行修复命令。

## 怎么发现(告警 / 指标 / 用户反馈)
- Prometheus alert 名 + 阈值
- Grafana 面板 URL
- 用户报错关键字

## 怎么定位
1. 看 X 指标 / 日志
2. 跑 Y SQL / kafka 命令
3. ...

## 怎么恢复(按风险升序)
### 方案 A:无损自愈(2 min)
...
### 方案 B:有损降级(10 min)
...
### 方案 C:核武器(回滚版本)
...

## 事后
- 写 incident-response,关联本剧本
- 如果触发了 alert 没解释清楚 → 调阈值
- 如果剧本走不通 → 补一篇新剧本
```

## 联动

- 主入口:`docs/runbook/incident-response.md`
- 系统总览:`docs/architecture/`
- 配套 chaos IT:`batch-common/src/test/java/com/example/batch/testing/chaos/`(每个 P0 剧本应有对应 IT 验证恢复路径)
