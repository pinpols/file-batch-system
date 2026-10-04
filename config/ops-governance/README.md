# 基础设施治理 profile

本目录保存 PostgreSQL、Kafka、Valkey/Redis、MinIO，以及文件通道、状态后端、
调度状态、拓扑、观测和外部端点在四类环境中的治理落地参数。
这些文件是环境治理基线，不绑定某一种部署形态。Compose `--env-file`、Helm values、
Kubernetes ConfigMap / Secret、Operator 参数、托管服务参数或跳板机脚本都只是适配层。

| profile | 用途 | 执行边界 |
|---|---|---|
| `local.env` | 本地开发 / IDE / Compose | 允许短 retention 和自动 MinIO lifecycle，重启基础服务生效 |
| `test.env` | 场景测试 / sim | 保留测试证据，仍保持有界 retention |
| `benchmark.env` | 压测 | 最短保留窗口，压测后仍需运行专用清理脚本 |
| `prod.env` | 生产基线 | 只作为生产覆盖值和巡检阈值基线；生产修改必须走变更审批 |

使用方式：

```bash
# 预览某个 profile
bash scripts/ops/apply-infra-governance.sh --profile local

# 按内置 profile 预览或显式应用支持脚本的治理项
bash scripts/ops/apply-infra-governance.sh --profile local --apply-kafka-topics --apply-minio-lifecycle

# 外部环境可使用自己的 env 基线，不要求放在 config/ops-governance/
bash scripts/ops/apply-infra-governance.sh --profile-file /etc/batch/prod-governance.env

# 只读检查 profile 是否覆盖运行时治理边界
bash scripts/ops/inspect-runtime-governance.sh --profile local
bash scripts/ops/inspect-runtime-governance.sh --profile-file /etc/batch/prod-governance.env
```

原则：

- PostgreSQL / Valkey 参数不在线改写，不使用 `ALTER SYSTEM` 或 `CONFIG SET` 留下不可审计状态。
- Kafka topic retention 由初始化脚本幂等下发，可对既有 topic 更新。
- MinIO lifecycle 由 `scripts/minio/apply-lifecycle.sh` 下发，生产 apply 需要显式确认。
- Compose 只用于本地 / 自托管样例；生产可映射到 Helm、Operator、云托管参数或平台变更单。
- `BATCH_DISPATCH_*` 覆盖 LOCAL / NAS / SFTP / OSS / API / API_PUSH / EMAIL 通道的沙箱、探测和生产安全基线。
- Worker Report Outbox、Quota、ShedLock、读副本、业务分片、Quartz、观测和 OpenLineage 在 profile 中登记默认治理口径；真实迁移仍走各自 cutover runbook。
- 所有生产清理仍走对应 runbook；这些 profile 不包含批量删除业务数据的动作。
