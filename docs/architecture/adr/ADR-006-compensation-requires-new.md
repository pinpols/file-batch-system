# ADR-006: 补偿/重试方法使用 REQUIRES_NEW 防止死锁级联

- **状态**: 已采纳
- **日期**: 2026-03-25
- **决策人**: 后端平台团队

## 背景

`DefaultRetryGovernanceService` 处理超时任务的补偿逻辑：当心跳超时后，需要将 `job_task` 状态改回 `PENDING`（或标记 `FAILED`），并递增重试计数。

问题场景：如果调用方本身在一个 `@Transactional` 方法中（例如定时扫描任务 `@Scheduled` + `@Transactional`），补偿方法加入同一事务后：

1. 扫描方法持有 `job_task` 行的读锁（或意向锁）。
2. 补偿方法尝试对同一行执行 UPDATE，等待扫描方法释放锁。
3. 扫描方法等待补偿方法完成以决定是否继续扫描下一条记录。
4. **死锁**。

## 决策

补偿/重试方法标注 `@Transactional(propagation = Propagation.REQUIRES_NEW)`：

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void compensateTimedOutTask(Long taskId, String tenantId) {
    // UPDATE job_task SET status = 'PENDING', retry_count = retry_count + 1
    // WHERE id = ? AND status = 'RUNNING' AND lease_expires_at < NOW()
}
```

`REQUIRES_NEW` 会**挂起**调用方事务，在独立事务中执行补偿，提交后恢复调用方事务。这样：
- 补偿操作的锁在独立事务中获取和释放，不与调用方事务的锁竞争。
- 补偿失败时只回滚补偿事务，不影响调用方事务中已处理的其他任务。

## 约束

- 调用方必须在 Spring 管理的事务上下文中（`@Transactional` 或手动 `TransactionTemplate`）；否则 `REQUIRES_NEW` 与 `REQUIRED` 行为相同。
- `REQUIRES_NEW` 会创建新的数据库连接，高并发下需确保连接池容量足够（当前配置：`maximumPoolSize = 20`，扫描批量 ≤ 10，理论最大并发连接 = 扫描线程数 × (1 + 批量)）。

## 后果

**正面**：
- 消除了扫描 + 补偿场景下的死锁风险。
- 补偿结果（成功/失败）可独立观测，不依赖外层事务。

**负面**：
- `REQUIRES_NEW` 的额外连接开销；补偿方法不应在高频调用路径上使用。
- 如果调用方事务回滚，补偿事务的提交**不会**随之回滚（两个独立事务）；这在当前场景下是期望行为，但需要明确记录。
