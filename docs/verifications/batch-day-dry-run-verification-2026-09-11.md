# 整批量日 Dry-run 本地验证报告

日期：2026-09-11
分支：`feature/backend-optimization`
范围：V202 数据契约、重放控制面、Worker/SDK capability、Console 契约与配置同步。

## 结论

代码实现与本地自动化验证通过，功能开关保持默认关闭。生产启用仍以 staging 的五类真实依赖零副作用快照和
1,000-entry 崩溃恢复测试为前置条件；本报告不把未执行的环境测试标记为通过。

## 已通过

| 验证项 | 结果 |
|---|---|
| Maven release 21 编译 | PASS |
| Replay service / dispatcher / terminal reconciler / worker selector 定向单测 | PASS |
| ResultVersionRetentionScheduler 与 Process Compute dry-run 定向单测 | PASS |
| Import / Export / Process / Dispatch / Atomic dry-run 负向单测 | PASS |
| 五类 Worker DryRunGuardConventionTest 真实目录扫描 | PASS |
| V202 Flyway 全迁移、NOT VALID guard、archive schema drift、候选约束 PostgreSQL IT | PASS |
| Feature Switch registry 同步检查 | PASS（41 switches / 80 env vars） |
| Java SDK 定向单测 | PASS |
| Go SDK `go test ./...` | PASS |
| Python SDK pytest / Ruff / mypy | PASS |
| Rust SDK cargo test | PASS |
| TypeScript SDK build 与 lifecycle 定向测试 | PASS |
| Console typecheck / i18n / lint | PASS |

## 已修复的验证阻断

1. `BatchDayReplayService` 的手写兼容构造抑制 Lombok 必参构造，Spring 上下文无法启动；已改为单一完整构造。
2. V202 entry 约束漏掉 `result_version_id` 候选，`OUTPUTS_ONLY` 会被拒绝；已兼容三种候选形态。
3. V202 CHECK 曾停在 `NOT VALID`，启动守卫拒绝服务；迁移已显式执行 `VALIDATE CONSTRAINT`。
4. Worker 架构守护使用历史目录名，存在零文件假绿；已改为实际模块目录并覆盖 Atomic executor。
5. `retention-days` 曾只有配置没有消费者；现由 result-version retention scheduler 原子归档后清理。
6. Dispatch 演练曾在跳过远端投递后继续写投递记录并推进正式文件状态；现已在任何持久化之前短路，并以 mock 零交互断言守护。

## 未计为通过

- TypeScript SDK 全量测试中已有 timeout 重试用例出现偶发 `expected 2 attempts, got 3` 并残留测试 server；
  本次改动的 build 和 lifecycle 定向测试通过，该独立 flaky 用例未作为 dry-run 验收证据。
- staging 五类真实依赖零副作用快照、1,000-entry、dispatcher/Worker/Orchestrator 三崩溃窗口尚未在本地伪造结论。
