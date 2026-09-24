# 配置运维入口分层治理

> 目标：避免把所有 `${BATCH_*:default}` 都升级成 Helm / Compose 公共开关。公共入口越多，运维误配面、文档漂移面和灰度验证成本越高。

## 1. 分层定义

| 层级 | 名称 | 入口要求 | 适用配置 | 变更要求 |
|---|---|---|---|---|
| L0 | 生产一等开关 | `feature-switch-registry.yml` 登记，Compose / Helm 显式透传，`feature-switches.md` 说明默认值、风险和回滚 | 影响数据一致性、安全、消息路由、有状态后端、租户隔离、上线灰度的开关 | 必须有回滚路径；P0/P1 需补验证或说明测试缺口 |
| L1 | 运维覆盖参数 | `.env.example` 或 values 示例可见；必要时 Helm values 暴露；不进入公共能力开关表 | 容量、批大小、poll interval、retention、清理窗口、连接预算等调参 | 需要压测或现场指标支撑；默认值必须能安全运行 |
| L2 | 应急逃生阀 | 代码支持 env 覆盖，但不在常规 Compose / Helm 主入口公开 | 故障期间临时禁用某个后台 job、降级某个非主路径实现、兼容旧环境 | runbook 写明“临时、需复原”；不得作为长期生产基线 |
| L3 | 内部实现参数 | 只保留 yml / Java 默认值，不对运维公开 | 小范围实现细节、测试辅助、局部算法阈值、不会被现场独立调整的子参数 | 修改走代码评审；不承诺外部稳定 |

## 2. 升级为 L0 的判定

满足任一条件时，应升级为 L0：

- 切错会导致重复调度、丢消息、跨租、绕过安全、无法回滚或数据语义改变。
- 生产部署必须按环境选择不同值，例如对象存储后端、ShedLock provider、Kafka 路由模式。
- 多实例或多租户拓扑必须显式配置，例如 worker Kafka 订阅模式、租户 allowlist、Outbox 分片模式。
- 开关本身是灰度/回滚手段，例如 request signing、checkpoint、report outbox。

L0 的最小交付：

1. `docs/runbook/feature-switch-registry.yml` 登记。
2. `deploy/docker/compose/app.yml` 显式透传，除非该开关只在 Kubernetes 模板中有意义。
3. `helm/batch-platform/templates/configmap.yaml` 或 `secret.yaml` 显式渲染。
4. `docs/runbook/feature-switches.md` 记录默认、风险、回滚和测试状态。
5. 通过 `check-feature-switch-registry.py`、`check-config-defaults-sync.py`、`check-helm-env-sync.py`。

## 3. 保持 L1/L2/L3 的判定

不要升级为 L0 的典型情况：

- 只影响后台扫描频率、batch size、retention，且默认值已覆盖主流部署。
- 参数族内部存在大量相互关联的细粒度字段，公开后反而更容易误配。
- 只用于测试、benchmark、临时兼容或现场应急。
- 配置项是动态业务配置的底层实现，不应绕过 Console / DB 版本治理直接 env 改。

这类配置可以放在专项 runbook 中说明，但不进入公共开关表。确需 Helm 暴露时，也应放在对应模块 values 树下，并标明它是容量参数，不是特性开关。

## 4. 当前盘点结论

### 4.1 已升级为 L0

| 配置族 | 原问题 | 当前治理 |
|---|---|---|
| `BATCH_SHEDLOCK_PROVIDER` | 影响所有 `@SchedulerLock`，但原来只有文档和裸 env，Compose / Helm 无一等入口 | 已登记为公共开关，并补齐 Compose / Helm |
| `BATCH_WORKER_KAFKA_SUBSCRIBE_MODE` / `BATCH_WORKER_KAFKA_TENANT_ALLOWLIST` | worker 租户隔离和专用资源池依赖该配置，原来只在应用 yml 和部分 Helm 模板里出现 | 已登记为公共开关，并补齐 Compose / Helm 默认入口 |
| `BATCH_RESOURCE_SCHEDULER_WAITING_DISPATCH_KICK_ENABLED` / `KICK_DELAY_MILLIS` | WAITING 分片排空唤醒影响控制面延迟，Helm 有入口但 Compose 缺入口 | 已登记为公共开关，并补齐 Compose |
| `BATCH_WORKER_IMPORT_SCANNER_EVENT_ARRIVAL_ENABLED` | 事件驱动到达已实现但文档和入口不完整 | 已补 Compose / Helm / registry / `.env.example`，默认仍关闭 |

### 4.2 建议保持 L1

| 配置族 | 原因 | 治理方式 |
|---|---|---|
| `BATCH_S3_MULTIPART_*` | 大文件上传性能参数，依赖对象大小、网络、S3 兼容实现；不改变业务语义 | 放入对象存储容量调优 runbook；需要压测数据后再决定 Helm values 是否一等化 |
| `BATCH_WORKER_IMPORT_STREAMING_ENABLED` / `BATCH_WORKER_EXPORT_STREAMING_ENABLED` | 流式读写是推荐实现，关闭属于性能回退 | 保留 env 逃生能力；不作为常规部署开关 |
| `BATCH_WORKER_IMPORT_SKIP_*` | 导入容错策略与模板/业务规则强相关，不能只靠全局 env 代表业务语义 | 后续优先迁入模板级配置；全局 env 仅做兼容和压测覆盖 |
| `BATCH_*_ARCHIVE_*` / cleanup batch size / retention | 容量和保留策略调参，不是特性灰度 | 在归档/清理 runbook 中说明容量建议，按环境 values 覆盖 |

### 4.3 建议保持 L2

| 配置族 | 原因 | 使用约束 |
|---|---|---|
| `BATCH_WORKER_DRAIN_*` | 优雅停机默认应开启，关闭只用于定位 shutdown 卡死 | 应急临时使用，复盘后恢复默认 |
| `BATCH_TRIGGER_LAUNCH_CREATED_RECOVERY_*` | 修复 CREATED 残留的后台补偿，正常生产不应关闭 | 只有补偿逻辑自身异常时短时关闭 |
| `BATCH_OUTBOX_CIRCUIT_BREAKER_ENABLED` | Outbox 保护逻辑默认应固定，关闭会放大下游故障 | 仅压测或故障定位使用 |
| `BATCH_ASSET_FRESHNESS_ENABLED` / `BATCH_SLA_ENABLED` | 观测和告警侧开关，关闭不会修业务问题 | 只允许在告警噪音治理期间临时调整 |

### 4.4 建议保持 L3

| 配置族 | 原因 |
|---|---|
| `BATCH_FILE_GOVERNANCE_*` 细粒度扫描、采样、延迟阈值 | 参数多且互相关联，应通过文件治理专项配置或 Console 管理，不应全部暴露为公共开关 |
| `BATCH_WORKER_IMPORT_SCANNER_*` 除 done/manifest/event-arrival 外的细节 | 属于扫描实现细节，公开过多会导致现场绕过模板和到达组语义 |
| `BATCH_MULTIPART_*` Spring multipart 上限 | Web 边界安全参数，应该按应用安全基线配置，不作为普通开关 |
| `BATCH_PG_SESSION_ENABLED` | 数据库会话上下文实现细节，误关会影响 RLS / 审计语义 |

## 5. 新增配置评审清单

新增 `${BATCH_*:default}` 前先回答：

1. 这是业务能力开关、部署拓扑开关、容量参数、还是实现细节？
2. 运维是否需要在不改代码的情况下独立调整？
3. 误配的失败模式是什么：启动失败、数据错乱、安全绕过、性能下降，还是仅日志噪音？
4. 是否需要 Compose、Helm、`.env.example`、runbook、Console 动态配置同时更新？
5. 是否需要 cutover-id、版本号、滚动重启或灰度顺序？

无法明确回答时，默认放 L3；等压测、上线演练或现场故障证明需要运维入口后，再升级为 L1/L0。
