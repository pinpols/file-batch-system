# 应用治理收口（2026-09-02）

本文件是应用运行时治理入口。权威清单位于 [`../governance/application-governance-contract.yaml`](../governance/application-governance-contract.yaml)，由 `scripts/ci/check-application-governance.py` 校验并纳入 PR 与 Full Gate。

## 五项横切治理

| 项目 | 已补内容 | 当前边界 |
|---|---|---|
| 超时预算 | PG session、调度、依赖 readiness、Worker 执行和优雅停机已有配置与 Runbook；统一清单防止遗漏 | 目标环境需按实际 SLA 校准，不把所有超时强行改成一个值 |
| 运行时兼容 | Java/Python/Go/TS/Rust、Docker/非 Docker、IPv4/IPv6、`psql` fallback 已有矩阵和脚本门禁 | 外部服务双栈、宿主机工具版本仍需部署环境复验 |
| 故障注入 | Kafka Outbox、PG/Redis/ShedLock、Worker lease、下游超时已有 IT、sim 和演练入口 | 真正的 PG failover、Kafka broker 故障和整组 Worker 演练必须在 staging 执行 |
| 告警与 Runbook | Prometheus 规则、Trace/MDC 字段、SLO、故障剧本和恢复动作集中索引 | 告警接收端、静默/升级策略需由目标监控环境确认 |
| 供应链 | CodeQL、Trivy、license review、SDK SBOM/provenance 入口已纳入发布流程 | 生产镜像签名、SLSA attestation 和制品仓库策略依赖发布环境，不在开发机伪造 |

## 统一约束

1. 应用治理优先复用现有 Spring、Kafka、PG、Helm、Prometheus 和 CI 能力，不新增配置中心、网关或调度组件。
2. 代码防线、CI 门禁、容器配置、真实环境演练分别记证据；任一项缺失都不能标记为完整上线证据。
3. 所有新治理项必须加入权威 YAML 清单并提供至少一个仓库内证据路径，否则 CI 失败。
4. 开发默认配置可以保持单机友好；生产安全值只能在 `helm/values-prod.yaml` 和外部 Secret 中声明。

## 复扫命令

```bash
python3 scripts/ci/check-application-governance.py
python3 scripts/ci/check-production-overlay-safety.py
python3 scripts/ci/check-config-defaults-sync.py --check
python3 scripts/ci/check-helm-env-sync.py
```

## 明确不做

不因治理名义引入 Nacos/Apollo、独立 API Gateway、通用工作流编排、Citus/分库或自研资源调度器；这些改变系统边界，需独立 ADR 和真实容量触发条件。
