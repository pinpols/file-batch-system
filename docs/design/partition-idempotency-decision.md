# 分区下的 outbox 幂等语义决策(2026-06-10)

## 问题
outbox_event 月分区后 UNIQUE 必须含分区键 → (tenant_id, event_key, created_at),
全局 (tenant_id, event_key) 唯一在分区表上不可表达,
`on conflict (tenant_id, event_key) do nothing` 失配。

## 决策:INSERT ... SELECT ... WHERE NOT EXISTS 替代
- 写路径改为:仅当 (tenant_id, event_key) 不存在任意行时插入(扫
  uk_outbox_event_p_key 前缀索引,代价等价)。
- 冲突时不插入、useGeneratedKeys 不回填 id —— 与 DO NOTHING 行为完全一致,
  调用方(OutboxDomainEventPublisher.publish 返回 entity.getId())零改动。

## 为什么竞态可接受
1. 事件发射在 @Transactional(MANDATORY) 中,与聚合状态变更同事务;
   同一聚合的状态迁移被 job_instance.version 乐观锁串行化 ——
   同一逻辑事件不存在并发双发路径。
2. event_key 本身按"意图唯一"设计(PartitionReclaimUnit 将 partition.version
   内嵌 key),全局 UNIQUE 历史上反而造成过静默丢事件(见该类注释)。
3. 极端竞态下重复事件的下游代价:Kafka 重复投递 → Worker CLAIM 唯一性兜底,
   不会双执行。
4. 残余风险等级:低;监控抓手:
   `SELECT tenant_id, event_key, count(*) FROM batch.outbox_event GROUP BY 1,2 HAVING count(*)>1`
   进 strict-verify 观测。

## 不做
- 不引入 advisory lock(写热点路径加锁,得不偿失)
- 不建独立 dedup 表(多一张表一致性负担,YAGNI)

## 不变量守护(2026-06-13 补:把"可接受性"的两个前提钉成机制)

NOT EXISTS 弱化只在两个前提同时成立时安全,二者原先都只是"口头约定",现固化:

1. **单一写入 choke point**:所有 outbox domain-event 写入只经 `OutboxDomainEventPublisher`
   (内部 `outboxEventMapper.insert` = NOT EXISTS)。守护:`OutboxWriteChokePointArchTest`
   静态扫描 main src,任何其他类出现 `outboxEventMapper.insert(` 即 fail —— 防止有人加裸 insert
   旁路去重、把竞态重新引入且无 DB 兜底。
2. **event_key 含与被锁聚合 version 绑定的判别位**(如 PartitionReclaimUnit 把 partition.version 嵌入 key)
   —— 这是"同一逻辑事件无并发双发"的根据。新增事件类型时**必须**沿用此约定;无法静态强制,
   写代码红线 + 下面的重复检测兜底。

   > **PR checklist(新增/改 outbox 事件类型必答)**:① 新 event_key 是否含被锁聚合的 version/唯一判别位?
   > ② 写路径是否仍只经 `OutboxDomainEventPublisher`(`OutboxWriteChokePointArchTest` 会拦旁路)?
   > ③ 若该事件可能并发产生同 key,是否已确认 `BatchOutboxDuplicateEventKeys` 告警接了 oncall?
   > —— 月分区后全局 `(tenant_id,event_key)` UNIQUE 不再硬兜底(改 NOT EXISTS),这三条是唯一防线。

## 重复检测告警(从 strict-verify 升级到 prod)

`§为什么竞态可接受 4` 的重复检测查询从"仅 strict-verify 观测"升级为 **prod 持续告警**:
`BatchBacklogMetricsScheduler` 周期统计近窗口内重复 `(tenant_id, event_key)` 组数,暴露
Micrometer gauge `batch.outbox.duplicate.event_keys`;Prometheus 规则 `BatchOutboxDuplicateEventKeys`
( > 0 持续即 warning)。任何实际漏过的重复都会被第一时间发现,而非等到对账。
