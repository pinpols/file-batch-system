# 待办事项汇总(2026-06-23)

本文件汇总 `docs/backlog/` 与 `docs/plans/` 中**尚未完成**的事项,作为单一索引。明细以各来源文档为准;启动任一项前**务必以 `origin/main` 实际代码复核状态**(历史规划文档的"待办"标注可能已被后续实现取代)。

> 维护约定:某项完成后,在此处划线并在来源文档同步标注,避免索引与实现漂移。

## P0 —— 安全/正确性优先

- **多租户 RLS Phase A 三红线**(来源:`plans/multi-tenant-isolation-plan-2026-05-31.md` §Phase A)
  - R1:现役 `batch_user` 收敛为非 superuser / 非 owner / `NOBYPASSRLS`。
  - R2:RLS 由 transition 切到 strict(移除 fail-open 逃逸分支)。
  - R3:租户隔离守护从白名单改为闭世界 fail-fast。
  - 影响:三条未达成前 RLS 处于 fail-open,隔离形同未启用。
- **结算级缺口路线图**(来源:`plans/settlement-gap-remediation-roadmap-2026-06-20.md`)
  - Phase 0(ADR-041)+ Phase 1(对账完整性闭环 1.1–1.5):**已全部落 main 并各带单测,default-off**(2026-06-23 核实,ADR-041 已置 Accepted)。**不再是待办**。
  - 剩余:**Phase 2**(长任务 task 级心跳 / ADR-038 checkpoint 接入 Load/GenerateStep / 准入软节流⚠️需小 ADR)、**Phase 3**(maker-checker 双控 / dual-use 命令审计表 / 告警升级阶梯 / 人工数据修正受控 API⚠️需 ADR-021 对齐)、**Phase 4**(事件驱动到达 / 依赖感知 fire⚠️需 ADR / 实例 pause-resume / TriggerType.EVENT 消费者)。多数需 ADR 决策,待定。

## P1 —— 容量与验证基础设施

- **上线前容量专项**(来源:`backlog/pre-production-capacity-optimization-plan-2026-06-08.md`)
  - P1-5:export 真实云 S3/OSS 专项(multipart abort/retry、checksum、真 endpoint)。
  - P1-6:dispatch/atomic 真实外部依赖故障注入(真 SFTP/NAS/OSS/HTTP 超时/断连/权限失败 + 重试/DLQ)。
  - P1-7:process 故障画像(DIRECT copy 中途 kill worker / PG 临时断开 / 恢复后 staging 核对)。
  - P2-8:10 万 task 洪峰容量(当前本地不达标,需调 trigger/orchestrator 消费并发 + 接入层 backpressure 后重跑)。
  - P2-9:多租户公平性(需 tenant-aware outbox 选择 / launch 消费分区并发 / quota fair-share)。
- **验证基础设施 r3 系列**(来源:`plans/r3-1`…`r3-4`)
  - r3-1:Chaos/Toxiproxy 集成测试框架(Kafka 延迟 / Redis down / PG 连接重置)。
  - r3-2:运维剧本(部分已落地于 `docs/runbook/playbooks/`,**核对剩余篇目**)。
  - r3-3:24h Soak Test。**已知阻塞**:`BatchDateTimeSupport` 未读 `-Dbatch.testing.clock-offset`,跨日偏移当前不生效,需在 batch-common 加 `@Profile("soak")` 的 offsetClock。
  - r3-4:Forensic Bundle 本地回放工具(解包 + schema 还原 + replay + diff)。
- **ADR-046 Phase 2 worker 攒批生产启用门**(来源:`backlog/adr046-phase2-2.3-worker-batch-construction.md`)
  - 代码 2.0–2.4 + 2.3a–d 已全部落 main,flag `batch.worker.batch-claim.enabled` 默认关。
  - 待办:生产开 flag 前,在**独占全栈窗口**跑 `scripts/local/adr046-batch-consume-load.sh`(上万 fan-out 对照基线)达标。

## P2 —— 前端与按需增强(多为延后,触发条件出现再启动)

- **SDK / Atomic 前端**(来源:`plans/fe-worklist-2026-h2-atomic-sdk.md`、`plans/sdk-roadmap-2026-h2.md`):自托管 taskType 列表/详情页、工作流编辑器按 schema 渲染表单、任务详情 heartbeat 进度页、Atomic 内置四类配置 UI、worker 健康度看板。BE 多已就绪,FE 按运营需求启动。
- **SDK 进阶**(来源:`plans/sdk-roadmap-2026-h2.md`):OTel context 传播(延后);Phase 6 合规企业级 6 项(Payload 加密 / input JSON Schema 校验 / per-workflow 限流 / buildId 金丝雀路由 / 控制 topic push / 远程停机信号)—— 均 YAGNI,触发条件出现再单项启动。

## 设计存档(YAGNI,撞墙才执行)

- **biz Tiered 多租**(来源:`plans/biz-tiered-tenancy-plan-2026-06-14.md`):biz schema 多目标迁移 + drift 校验、路由数据源、Pooled 扩 shard、Silo 升舱工具。仅 biz 撞单机墙时执行;P0 多目标迁移是硬前置债。
- **跨平台脚本 D-1/D-2**(来源:`backlog/cross-platform-scripts-2026-05-24.md`):ops 脚本 host:port env 化、容器名 compose 解析。需 K8s/staging 真需求才做。
- **ADR-046 文件束坏数据隔离终态**(来源:`backlog/adr046-bundle-malformed-group-quarantine-2026-06-21.md`):QUARANTINED 状态 + console 人工恢复。已加 metric 回退;告警高频再上。
- **多租户隔离 Phase C/D/E**(来源:`plans/multi-tenant-isolation-plan-2026-05-31.md`):per-tenant schema 已否决;per-tenant worker pool 跑通 + 演练需 live 集群;per-tenant DB 仅合规硬要求时做。

## 近期已完成(原规划文档曾标"待办",勿误列)

- **ADR-046 Phase 2 多行 claim/report**(2.0–2.4 + worker 攒批 2.3a–d):已落 main(PR #682–#696);相关 plan `backlog/adr046-phase2-plan-2026-06-23.md` 的"待开工"标注已过期,仅剩上方"生产启用门"一项。
- **SPI → Atomic 重命名**(`plans/rename-spi-to-atomic-2026-05-31.md`):已落地(`AtomicTaskConsumer` 等),plan 的待办勾选已过期。

## 遗留:术语统一(非阻塞)

- "状态主机"一词在 CLAUDE.md 及约 30 篇文档中作为"orchestrator 唯一状态权威"的既定术语使用。其字面("主机")易被理解为物理机,建议未来做一次项目级术语评审决定是否统一改为"状态权威 / 状态机";因涉及面广(含 CLAUDE.md),本次文档清理未改动,保持一致。
