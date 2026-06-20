# 依赖感知 fire(Dependency-Aware Fire)

> ADR-043 Phase B 实现。让声明了上游依赖的触发器在 fire 前先确认上游就绪,**不盲 fire 注定无输入 / 半输入的批**。默认对存量触发器零影响。

## 机制

`job_definition.depends_on_job_code`(可空)声明本触发器 fire 前需就绪的上游 job。scheduled fire 路径(`DefaultTriggerService.launchScheduled`)在 bizDate 解析后、persist 前插一道闸:

- `depends_on_job_code` 为空(绝大多数存量触发器)→ 直接放行,**行为完全不变**。
- 非空 → 经 orchestrator 只读 API `GET /internal/readiness/job` 查上游同 bizDate 是否已 `SUCCESS`(`UpstreamReadinessChecker`):
  - 就绪 → 正常 fire。
  - 未就绪 → **跳过本次**(复用既有 `skipScheduled` 路径,scheduler 正常推进 `next_fire_time` 到下一调度点重试),记 INFO。

> trigger **不直连** orchestrator 状态表(读写分离 + 模块边界),就绪判定一律经 orchestrator 暴露的只读 internal API(携 `X-Internal-Secret`)。orchestrator 仍是唯一状态主机。

## fail-closed(结算优先)

就绪查询失败(orchestrator 不可达 / 超时 / 5xx)时,`UpstreamReadinessChecker` **不放行 fire**(返回未就绪)并记 `ERROR`。宁可不 fire 也不基于不确定状态盲 fire——结算链路要求。运维应对 ERROR 告警快速介入(多为 orchestrator 连通性 / secret 漂移)。

## 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `job_definition.depends_on_job_code` | NULL | 上游 job code;非空才启用本触发器的依赖闸 |
| `batch.trigger.readiness-gate.enabled` | `true` | 全局 kill-switch;设 `false` 时一律放行(等价关闭依赖感知,应急用) |

## 边界 / 后续(v1 范围)

- **v1 只支持单个上游 JOB + SAME_DAY 对齐**。多依赖、`PREV_DAY` 对齐、FILE_GROUP(文件组 TRIGGERED)就绪、`readinessWindow` + 超窗 misfire 的自动重评——均为 ADR-043 描述的后续增强项,本期未做(v1 用"跳过本次、下个调度点重试"的简单语义)。
- 只 gate `launchScheduled`(正常调度 fire);catch-up / 手动 fire 不走此闸。
- 不裁定上游业务对错(ADR-021 红线),只问"上游这个 job 这个批次日跑成功了没"。
