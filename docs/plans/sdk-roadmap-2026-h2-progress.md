# SDK Roadmap 2026 H2 — Phase 进度 Retrospective

> Plan: [`sdk-roadmap-2026-h2.md`](./sdk-roadmap-2026-h2.md)
>
> 维护规则:每 phase 收官追加 5 行 retro(决策 §1 隐-1 强制),日期倒序。

## 2026-05-31 — Phase 0 — 协议演进基础 ✅

- **实际 vs plan**:plan 估 3 天,单 agent 单 PR 完成,代码量 ~700 LoC(含测 + yaml + 文档),略超 plan §1 的「≤ 600 LoC」目标 —— 多在 OpenAPI yaml + dual-rollout md 文档,代码部分 < 250 LoC。
- **关键决策**:`schemaVersion` 字段加在 SDK record **末尾**(非首位),配兼容构造器避免动 8 处既有测试调用点;`UnsupportedSchemaVersionException extends IllegalArgumentException` → 已被 `TaskDispatcher.onMessage` 现有 catch 兜住,无需改 dispatcher。
- **契约测试 placement**:plan §2.3 说 `mvn -pl batch-orchestrator test`,所以放 `batch-orchestrator/src/test/contract/`;批 SDK test-scope 加进 orchestrator pom(不污染生产 dep)。
- **OpenAPI 新文件**:`docs/api/orchestrator-internal.openapi.yaml` 首版收 5 个 wire schema + 5 个 endpoint,后续每个 phase 涉及 `/internal/*` controller 变更必须同 PR 更新本 yaml(已写入 `sdk-dual-rollout.md` §5 自查清单)。
- **下一步**:Phase 1 SDK 硬伤修复 4 个 PR;按 plan §15.8.A 依赖图,#SDK-P1-1 (stop 顺序 + ConsumerRebalanceListener) 是依赖根。
