# 后端架构审核整改方案

基线 `51c82f49b`。两轮审核共 8 个运行或部署问题（3 个 P1、5 个 P2），另有 2 处文档偏差。7 个问题有本地反例，取证包问题已核对代码和生产 Helm 配置，尚待双副本实测。以下为整改方案与关闭条件，逐项实施状态见文末“实施状态”。[首轮审核](../audit/backend-architecture-review-2026-11-21.md) · [全后端审核](../audit/backend-architecture-review-2026-11-22.md) · [当前待办](todo-master.md)

## 第一批 · 先恢复文件正确性和调度可用性

| 原审核编号 | 修改位置与方案 | 关闭条件 |
|---|---|---|
| ARCH-20261122-01 · P1 导出恢复 | Worker export 的 `DelimitedExportFormat`、`FixedWidthExportFormat` 和 `GenerateStep` 区分首次空输入与已有断点。首次为空时按模板写出合法空产物并清理旧字节。有断点时按保存的游标、字节偏移和累计行数续写；列布局无法确定时返回可诊断的失败，保留位点与残文件，阻止 STORE 和登记。 | CSV、定长均测试首次为空、首页写完后中断并正常续跑、重派时首页为空且列布局不可恢复。成功时核对实际交付字节和登记行数；失败时没有完成标记或新登记。把审核中残留 11、34 字节的场景转为回归用例。 |
| ARCH-20261121-01 · P1 补槽线程池阻塞 | Orchestrator 的终态唤醒改成非阻塞协调：认领后向有界执行器提交逐项恢复，提交者释放线程。所有已提交任务结束后清除租户去重标记；空批、异常和拒绝提交也要正确收尾。保留逐项租户上下文、认领租约和定时扫描的一轮完成语义。 | 用真实执行器分别设并发度 1 和默认 4，构造多个租户同时终态。终态唤醒和定时扫描都能继续 launch；单项失败不会卡住同租户下一轮，未执行的认领可在租约到期后恢复。 |
| ARCH-20261121-02 · P1 等待请求用不了部分空槽 | `WorkflowLaunchWaitMapper.claimDue` 按与新 launch 一致的租户、Workflow、业务日、dry-run 和有效槽位上限判断候选。保留同请求已有根运行的幂等收尾、到期条件和组内顺序，最终占槽仍由数据库唯一索引裁决。 | PostgreSQL 验证上限 1、上限 2 全满、只释放 1 槽。释放后由仍在排队的请求实际启动；与新 launch 抢同槽时不超限、不重复创建根运行。 |

导出与 Workflow 可以并行。两项 Workflow 修复分别验证线程推进和 SQL 准入，再联合运行“终态释放一个槽 → 已排队请求启动”的场景；单独观察线程池恢复或 SQL 领到一行均不足以关闭这条链路。

## 第二批 · 恢复和投递的并发所有权

| 原审核编号 | 修改位置与方案 | 关闭条件 |
|---|---|---|
| ARCH-20261121-03 · P2 人工恢复争用原槽 | `WorkflowRunManagementApplicationService` 与 `WorkflowRunMapper` 在同一事务给既有 PAUSED run 取得有效空槽并更新状态。原槽被占时可选其他槽；并发碰撞按唯一索引结果有限重试，满槽则保留暂停并向 Console 返回可识别的容量结果。事务提交成功后再推进 DAG。 | 上限 2，A 暂停、B 占原槽后，A 以原 run ID 在另一空槽恢复，已完成节点不重跑。双恢复、与新 launch 竞争、满槽三种情况下均不超限；Console 可区分容量不足和系统故障。 |
| ARCH-20261121-04 · P2 派发 Outbox 旧结果覆盖新领取 | Orchestrator 让领取语句原子返回事件 ID 和本轮凭据，传至 Kafka 回调。优先核实 `publish_attempt` 在所有重放、人工重置路径都不会复用，满足后直接用它作为代次；否则按已有 Trigger 实践生成独立领取凭据。成功、失败、放弃都校验租户、事件、状态和凭据，审计按生效行数记录。 | PostgreSQL 构造两次领取。旧成功、旧失败、旧放弃各更新 0 行，新发布者确认更新 1 行。再交错 Kafka ACK 和 DB 回写，核对事件最终状态和重试审计。 |
| ARCH-20261122-03 · P2 告警通知误回收新领取 | Console 备用告警 Outbox 在领取时生成本轮凭据。超时复位同时校验扫描时的凭据、当前状态和超时条件；成功、失败、放弃也要求本轮凭据。若新增字段，采用向后兼容的迁移，处理升级前遗留的 PUBLISHING，并在旧发布器退出后启用完整保护。 | 打开备用通知开关。A 扫到过期项，B 回收并重领后，A 的旧复位及旧回写均更新 0 行，B 的确认更新 1 行；再验证未被重领的真实超时项仍能重试。 |

先完成等待准入，再把同一槽位规则用于人工恢复。两种 Outbox 可并行修，各自保留发布与重试策略；混版期间旧进程仍可绕开新条件，切换完成后才验收旧持有者回写。

## 第三批 · SDK 兼容与取证包跨副本下载

| 原审核编号 | 修改位置与方案 | 关闭条件 |
|---|---|---|
| ARCH-20261122-02 · P2 SDK 幂等结果被旧执行清除 | TypeScript、Go、Rust 的有时限存储在 acquire 时返回持有者凭据，record 和 release 在同一原子操作内比较凭据再变更。用同一时序核对三种语言。给已有自定义存储提供可迁移的接口版本，明确旧接口的保护范围。Java、Python 先验证具体存储的过期接管语义。 | A 过期，B 接管且成功后，A 晚到的失败不能删除 B 的缓存，晚到的成功也不能覆盖它；第三次投递命中 B 的结果。正常失败能立即重试，已有适配器通过兼容性测试。此保护仅覆盖缓存结果的所有权。 |
| ARCH-20261122-04 · P2 取证 ZIP 局限在生成副本 | 优先沿用 ADR-022 的文件模式：生产 Helm 将同一持久化读写共享卷挂到所有 Orchestrator 的相同目录，显式配置 `batch.forensic.storage-dir`，校验运行身份可读写。切换前盘点各副本仍在保留期内的旧包，按原路径可读性安排迁移并核对摘要。目标环境不支持共享卷时，再评估使用现有对象存储。 | 在双 Pod 环境让 A 生成、B 下载，重建 A 后再次下载同一 ID，两次 SHA-256 均与库中记录相同。覆盖切换前已完成的包和跨租户拒绝下载。记录卷能力、保留期及实测结果后关闭。 |

## 两处文档校准

| 权威原文 | 拟改内容与验收 |
|---|---|
| [系统职责 §2.3](../architecture/system-scope-boundary.md) | 将 Outbox + Kafka 的投递保证改为“至少一次”，分别写清发布端重试和 Worker 幂等认领的责任；按“ACK 后、DB 回写前中断”的例子和 [ADR-002](../architecture/adr/ADR-002-transactional-outbox.md)核对措辞。可与派发 Outbox 修复同期完成。 |
| [ADR-050](../architecture/adr/ADR-050-multiple-active-runs-per-biz-date.md) | 说明归档表有 `active_slot` 列，但两条归档 INSERT 都省略该列，因此归档值取默认 1，不能用于还原历史实际槽号。按 [V386](../../db/legacy-migration/V386__workflow_active_runs_per_biz_date.sql) 和两条归档 SQL 核对。槽号按现行设计只用于活跃运行的并发裁决；如后续需要追溯实际槽号，再单列数据契约变更。 |

实施时按原编号回填测试、环境和状态。局部单测、真实 PostgreSQL 并发、Kafka 回调和双 Pod 下载分别记录；目标环境尚未执行的验收继续保留在待办中。

## 实施状态（2026-11-22）

| 编号 | 状态 | 已落地与验证 | 仍开放 |
|---|---|---|---|
| ARCH-20261122-01 · P1 导出恢复 | 已实施 | 首跑空输入截断旧字节并登记零行；续跑空首页且无列声明返回 `EXPORT_GENERATE_RESUME_LAYOUT_UNKNOWN`，保留位点与残文件；声明列时按游标续写。`batch-worker/export` 266 项单测通过，新增 5 个 CSV/定长回归用例。 | 真实对象存储与全链路重派未在本轮执行。 |
| ARCH-20261121-01 · P1 补槽线程池 | 已实施 | 父任务认领后逐项提交并立即释放线程；本轮 future 完成（含空批/异常/拒绝提交）后清除租户标记；逐项保留 RLS、租约与定时扫描一轮语义。新增池并发 1/4、多租户推进、下一轮触发、拒绝提交用例，13 项测试通过。 | 目标环境多实例长稳观察。 |
| ARCH-20261121-02 · P1 等待准入 | 已实施 | `claimDue` 按 `1..max_active_runs_per_biz_date` 的可见空槽领取，与新 launch 同规则；唯一索引仍为最终裁决。PostgreSQL 集成用例验证上限 2、满载为空、只释放 1 槽后原等待请求恢复且活跃数不超限。 | 与真实新 launch 的高并发竞争未做压测。 |
| ARCH-20261121-03 · P2 人工恢复选槽 | 已实施 | 新增 `resumeIntoFreeSlot`：同一事务内为 `PAUSED` 运行选择空槽，保存点内对唯一索引冲突有限重试；满槽返回 `error.workflow.resume_capacity_full`。PostgreSQL 用例验证原槽被占时恢复到最后空槽、满槽保持暂停。 | 既有 `WorkflowRunResumeRecoveryIntegrationTest` 的“暂停失败恢复不重复派发”用例已通过，覆盖已完成节点不重跑的关键路径。 |
| ARCH-20261121-04 · P2 派发 Outbox 凭据 | 已实施 | `markPublishingBatch` 返回 `(id, publish_attempt)`；成功/失败/放弃回写均校验凭据。PostgreSQL 用例验证重新领取后旧发布者三种回写更新 0 行、新发布者成功更新 1 行。 | Kafka ACK 与 DB 回写交错的真实链路未在本轮重放。 |
| ARCH-20261122-03 · P2 告警 Outbox 凭据 | 已实施 | `V389` 增加可空 `claim_token`；领取写入 token，成功/失败/放弃与超时复位均校验 token，复位同时复核状态和超时条件。Console 通知单测 13 项与 PostgreSQL Mapper 用例 10 项通过，后者覆盖旧扫描不能复位新领取、新发布者可确认。 | 混版期间旧发布器的退出顺序待目标环境确认。 |
| ARCH-20261122-02 · P2 SDK 幂等凭据 | 已实施 | TypeScript、Go、Rust 的有时限存储在 acquire 时返回凭据，`record`/`release` 原子校验；旧四方法自定义存储保留兼容路径。三语言“A 过期、B 成功、A 迟到、第三次命中 B”用例通过。 | Java、Python 的 TTL 由租户存储决定，SDK 内置内存存储不做过期回收，未发现同类内置缺陷；租户自定义有时限存储需自行按原子凭据实现。 |
| ARCH-20261122-04 · P2 取证包共享卷 | 已实施，待环境验收 | Helm 新增 `orchestrator.forensic.persistence`，同一 RWX PVC 挂到所有 Orchestrator 并设置 `BATCH_FORENSIC_STORAGE_DIR`；生产 values 启用，`helm template` 渲染通过；ADR-022 记录迁移旧包要求。 | 双 Pod 下 A 生成、B 下载、重建 A 后再次下载，两次 SHA-256 一致及跨租户拒绝必须在目标环境实测。 |
| 文档 · 系统职责 | 已完成 | §2.3 改为“至少一次投递”，写明发布端重试与消费方幂等认领责任。 | — |
| 文档 · ADR-050 | 已完成 | 明确归档表有 `active_slot` 但两条归档 INSERT 省略该列、归档值恒为默认 1，不能还原历史槽号。 | 如需审计实际槽号，另立数据契约变更。 |

### V389 结构清单跟进

[#1445 合入后的 Full CI](https://github.com/pinpols/file-batch-system/actions/runs/44983932569)执行了 V389。PostgreSQL 实测新增可空列 `batch.alert_escalation_notification_outbox.claim_token`，但提交中的全新库结构清单仍是 5,085 列，造成 `LocalFlywayPlatformMigrationsIntegrationTest` 失败。使用 `bash scripts/local/refresh-fresh-schema-manifest.sh` 在 PostgreSQL 17.11 重生成后，只有 `COLUMN` 类别更新至 5,086 列，约束、索引及迁移种子清单一致。普通校验模式下 `LocalFlywayPlatformMigrationsIntegrationTest` 11 项通过。
