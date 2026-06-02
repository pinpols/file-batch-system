# 平台 Worker 续跑位点 howto (ADR-038 / Phase 4.5)

> Status: **P1 + P2 已就绪(2026-06-02)**;P3 Export 续跑暂留 follow-up(见末段「未做项」)。

## 解决的问题

平台 worker 的 Import LOAD 当前是「全量重跑」模型:任务超时 / 进程崩 / lease 被回收后,
下一个 worker 从**第 0 行**重头读 staging 文件 + 重新调 `plugin.loadChunk`。
在百万行级真实任务下,业务库被反复 INSERT/UPDATE 风暴冲击,SLA 受损。

本特性引入持久化「续跑位点」表 `batch.pipeline_progress`,LoadStep 每完成一个 chunk
就 UPSERT 当前行号 + 已处理累计;重派时从最后一次 advance 的行号续跑,前面已处理的 chunk
不重做。

## 开关

| 配置 | 默认 | 含义 |
|---|---|---|
| `batch.worker.checkpoint.enabled` | `false` | ADR-038 续跑位点总开关。**关闭时行为与未引入本特性完全一致**(从 0 跑、不写位点)。 |

灰度建议:
1. dev 环境长期开 → 跑日常用例验证不破坏正路径
2. staging 灰度 1~2 周,看 `batch.pipeline_progress` 表行数 / 重派任务命中续跑次数
3. prod 按租户 profile 渐进打开;**先在百万行级业务的租户上开**,小数据量租户继续 false 即可

## 同事务约束(important)

ADR-038 §决策二要求「chunk 业务写 + 位点更新同事务」。实际实施:

- **Import LOAD**:业务数据写到**租户业务 DB**(`importBusinessDataSource`),位点写到**平台 DB**
  (`batch.pipeline_progress`)。**跨库无法 1PC 原子**。本实现采用「业务先 commit → 位点后 advance」+
  插件幂等(多租 `UNIQUE(tenant_id, ...)` + `ON CONFLICT`)兜底。
- **崩溃窗口**:业务 commit 完但 advance 失败的瞬间崩溃,重派时位点落后 ≤ 1 chunk,
  会重做该 chunk;`strict-idempotency=true` 默认下 plugin 的 `INSERT ON CONFLICT DO NOTHING/UPDATE`
  保证数据一致,只浪费一次 chunk 的 CPU。
- **不可用场景**:plugin 未开 `strict-idempotency` + 业务表无唯一约束的场景,续跑会双写。
  **必须确认 plugin 是 `GenericJdbcMappedImportLoadPlugin` 且 `strict-idempotency=true` 再启用**。

## 反例(don't)

❌ **位点 advance 不能写到业务 DB** —— 跨库唯一约束 / RLS / 升级耦合,违反"位点是 worker 内部记录"
   (ADR-038 §范围边界)
❌ **不能改成 "位点先 advance → 业务后 commit"** —— 业务 commit 失败时位点已前进,
   重派会跳过未写入的 chunk = 数据丢失
❌ **不能用 `@Transactional` 把跨库写包成同事务** —— Spring 单 DataSource tx mgr 不支持;
   引入 XA / JTA 是 ADR-035 §决策 P3 明确否决的方向

## 操作

### 启用步骤

1. 验证 plugin 是 `GenericJdbcMappedImportLoadPlugin` 且 `strict-idempotency=true`
2. 配置 worker:`batch.worker.checkpoint.enabled=true`(application-<profile>.yml)
3. 重启 worker(配置非热加载)
4. 观察 `batch.pipeline_progress` 表行数增长 = 正常工作

### 验证续跑

```sql
-- 看进行中的 LOAD 位点
SELECT tenant_id, pipeline_instance_id, position_marker, processed_count, updated_at
FROM batch.pipeline_progress
WHERE stage = 'LOAD' AND completed = false
ORDER BY updated_at DESC LIMIT 20;

-- 看 24h 内完成的位点(归档前)
SELECT tenant_id, pipeline_instance_id, processed_count, completed_at
FROM batch.pipeline_progress
WHERE stage = 'LOAD' AND completed = true
  AND completed_at > current_timestamp - interval '24 hours'
ORDER BY completed_at DESC;
```

### 关闭 / 回滚

- 配置改回 `batch.worker.checkpoint.enabled=false` + worker 重启;**已写入的位点行不删除**
  (无害,下次开关再打开时,正在进行中的实例可续跑)
- 紧急回滚:删除 plugin 实现里的续跑逻辑代码无意义 —— 开关关掉即可,代码路径完全跳过。
  V164 migration 不可回滚(对 DB 结构无破坏),无需 backout 脚本。

## 未做项(follow-up)

| 项 | 跟进 PR |
|---|---|
| **Export GENERATE 续跑** | 需先定文件恢复策略(分片临时文件 + STORE 拼接 vs 单文件 APPEND 模式)。表与 stage 枚举已就位;本 PR 不接入 GenerateStep。 |
| **阶段级续跑**(ADR-038 §决策四 P4) | 与 ADR-020 batch-day-replay 语义重叠,需对齐后再做。本 PR 砍掉。 |

## 相关

- ADR-038(本特性的设计依据)
- ADR-020(batch-day-replay,与 P4 阶段级续跑的语义对齐对象)
- CLAUDE.md §archive 冷表对齐(V164 已配套 archive 镜像 + ArchiveSchemaDriftCheck 登记)
