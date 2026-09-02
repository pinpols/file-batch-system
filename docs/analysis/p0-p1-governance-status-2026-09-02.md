# P0/P1 治理收口状态（2026-09-02）

## 结论

本清单是后续复扫的基线。P0/P1 不等于“所有生产基础设施已经由本仓部署”：应用防线、代码门禁、容器配置和真实环境演练必须分别记录，不能把配置存在误写成 HA 已验证。

## P0

| 主题 | 当前状态 | 证据 / 未完成项 |
|---|---|---|
| 配置与运行时边界 | 已实现 | `check-config-defaults-sync.py`、`check-helm-env-sync.py`、IPv4/IPv6 运行时契约；生产 overlay 安全值由 `check-production-overlay-safety.py` 强制检查 |
| 状态机、租户与 CAS | 已实现 | 核心状态更新含租户条件、当前状态/version CAS；补充回归由各模块 unit/IT 覆盖 |
| PG 生命周期与 RLS | 代码门禁已实现 | 迁移安全、业务表 tenant/RLS、归档漂移检查已接入 CI；真实备份/PITR 恢复演练仍是部署验收项 |
| Kafka 一致性与反压 | 应用能力已实现 | Outbox、幂等 producer、DLQ、lag/反压和恢复 runbook 已有；RF=3、ISR、Broker 故障演练需目标环境验收 |

## P1

| 主题 | 当前状态 | 证据 / 未完成项 |
|---|---|---|
| 观测与告警 | 已实现 | trace/task 上下文、Prometheus 规则、SLO/runbook 已有；集中日志和真实告警接收需环境验收 |
| 五语言 SDK 契约 | 已实现 | conformance、claim/lease/report/cancel/retry/idempotency 契约及专用 CI 已有；外部租户 transport 仍需接入验收 |
| 部署与弹性 | 配置已实现 | Helm 副本、HPA/KEDA、PDB、drain、Atomic 隔离和生产安全 overlay 已有；集群扩缩容/滚动升级需 staging 演练 |
| 内部架构治理 | 持续收口 | 有界上下文、DTO/Map 约束、复杂类拆分和可读性门禁已建立；只在修改相关模块时继续拆分，不做无收益大重构 |

## 本轮新增门禁

`scripts/ci/check-production-overlay-safety.py` 校验生产 overlay 必须显式启用强密钥、全局 NetworkPolicy、登录加密 required、Redis quota fail-closed、Atomic 独立身份与隔离 NetworkPolicy，并拒绝 Atomic 出向 `0.0.0.0/0` / `::/0` 占位规则。开发 `helm/batch-platform/values.yaml` 保持原有本地默认，不受影响。

## 明确不在本轮新增

- 不引入 Nacos/Apollo、Nacos Gateway、Citus、Patroni、独立策略服务或新的 MQ。
- 不把生产 PostgreSQL/Kafka/Redis HA 的运维演练伪装成代码已完成；这些继续由 `docs/runbook/ha-readiness.md` 和目标环境记录。
- 不改变现有 API、Kafka wire protocol、状态枚举和开发容器拓扑。

## 复扫入口

```bash
python3 scripts/ci/check-production-overlay-safety.py
python3 scripts/ci/check-config-defaults-sync.py --check
python3 scripts/ci/check-helm-env-sync.py
python3 scripts/ci/check-biz-table-tenant-rls.py
```
