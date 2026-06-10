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
