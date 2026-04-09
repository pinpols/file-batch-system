# Phase 3 故障演练报告

更新时间：2026-03-28

## 结论

Phase 3 的本地可执行系统联调资产已经收口，包含静态 deploy smoke、巡检入口和现有系统级测试证据。  
但真实 staging kube context 下的 live rollout、故障注入和回滚观测仍需要在目标环境执行，当前环境无法替代。

## 已完成的验证

### 1. 静态 deploy smoke

执行命令：

```bash
bash scripts/ci/run-full-regression.sh --skip-default-tests --skip-it-suite --with-deploy-smoke
```

结果：

- `helm lint` 通过
- `helm template` 通过
- chart 关键对象断言通过
- 统一脚本整体返回 `FULL REGRESSION PASSED`

### 2. 已有系统级测试证据

仓库内已具备并保留的相关证据：

- `batch-orchestrator` 并发 claim 集成测试
- `batch-orchestrator` outbox / Kafka 投递失败分支测试
- `batch-console-api` 安全负向测试
- `batch-console-api` tenant mismatch 负向测试
- `batch-worker-core` backpressure、worker loop、lease/wrapper 的单测基础
- `batch-worker-dispatch` 现有 circuit breaker / health probe 相关测试

### 3. 巡检与自愈入口

可用于 Phase 3 演练后的恢复检查：

- `scripts/ops/inspect-all.sh`
- `scripts/ops/inspect-db.sh`
- `scripts/ops/inspect-workers.sh`
- `scripts/ops/inspect-observability.sh`
- `scripts/ops/heal-stuck-outbox.sh`
- `scripts/ops/heal-dead-letters.sh`
- `scripts/ops/heal-drain-timeout.sh`

## 尚未在当前环境完成的项

以下内容仍需要真实 staging 集群、可用的 kube context 和外部依赖后再执行：

- `helm upgrade --install --wait` 的 live rollout
- `kubectl rollout status`
- `port-forward + /actuator/health/readiness`
- PostgreSQL 短暂不可用注入
- Kafka broker 波动 / backlog 恢复注入
- MinIO 写失败注入
- Worker restart / drain / graceful shutdown 演练
- WireMock / 外部渠道 5xx、超时、重试耗尽演练
- 回滚 smoke 与回滚后业务验收

## 残余风险

- 当前报告覆盖的是本地可执行的系统联调资产，不等价于真实 staging 验收。
- 回滚 smoke 和故障注入必须在目标环境做最终确认，不能仅依赖单测或静态 deploy smoke。

## 相关入口

- `docs/testing/full-project-test-plan.md`
- `docs/testing/phase-1-test-coverage-matrix.md`
- `docs/testing/release-gate.md`
- `docs/testing/staging-live-deploy-smoke-checklist.md`
