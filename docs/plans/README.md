# r3 Validation Infrastructure Plans

补 `scripts/sim/` 之外的验证维度。详见 `docs/architecture/` 关于本系统验证分层的总图。

## 4 个 Plan

| # | Plan | 解决什么 | 优先级 | 估时 |
|---|---|---|---|---|
| 1 | [Chaos / Toxiproxy IT](./r3-1-chaos-toxiproxy-it.md) | broker / db 故障下的熔断 / 降级 / 自愈 | P0 | 1d |
| 2 | [运维剧本](./r3-2-runbook-playbooks.md) | on-call 凌晨 3 点的"怎么救" | P0 | 0.5d |
| 3 | [Soak Test](./r3-3-soak-tests.md) | 长期稳定性(泄漏 / 跨日 / 累计 lag) | P1 | 1d |
| 4 | [Forensic 回放](./r3-4-forensic-replay.md) | 用昨日生产数据本地回放看是否改判 | P1 | 1.5d |

## 并行度

- Plan #1 与 Plan #2 配对:剧本写"怎么救",IT 验"救得回" — 同一人接收益最大
- Plan #3 独立,可并行
- Plan #4 依赖 sim(已存在)+ console-api,可并行

## 总工期
完整 4 件套约 4 天工。可由 1 人顺序 / 多人并行。

## 总验收
4 个 Plan 各自验收完毕 + 在 `docs/architecture/` 加一节"验证维度全图",标注 sim / chaos IT / soak / forensic replay 各自负责的格子。
