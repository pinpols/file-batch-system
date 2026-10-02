package io.github.pinpols.batch.console.domain.rbac.infrastructure;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 批量开户预览的短期存储与已提交操作的持久化适配器。 */
@Repository
@RequiredArgsConstructor
public class ConsoleUserBatchProvisioningStore {

  private final StringRedisTemplate redis;
  private final JdbcTemplate jdbc;

  public record OperationRow(
      UUID operationId, UUID requestId, int accountCount, String tenantIds, String createdAt) {}

  public void savePreview(String key, String json, Duration ttl) {
    redis.opsForValue().set(key, json, ttl);
  }

  public String loadPreview(String key) {
    return redis.opsForValue().get(key);
  }

  public void insertOperation(
      UUID operationId,
      UUID requestId,
      String actor,
      String sourceDigest,
      int accountCount,
      String tenantIds) {
    jdbc.update(
        "insert into batch.console_user_batch_operation "
            + "(operation_id, request_id, actor_username, source_digest, account_count, tenant_ids) "
            + "values (?, ?, ?, ?, ?, ?)",
        operationId,
        requestId,
        actor,
        sourceDigest,
        accountCount,
        tenantIds);
  }

  public OperationRow findOperation(UUID operationId, String actor) {
    List<OperationRow> found = jdbc.query(
        "select operation_id, request_id, account_count, tenant_ids, created_at "
            + "from batch.console_user_batch_operation where operation_id = ? and actor_username = ?",
        (rs, index) -> new OperationRow(
            rs.getObject("operation_id", UUID.class),
            rs.getObject("request_id", UUID.class),
            rs.getInt("account_count"),
            rs.getString("tenant_ids"),
            rs.getTimestamp("created_at").toInstant().toString()),
        operationId,
        actor);
    return EmptyChecks.isEmpty(found) ? null : found.get(0);
  }

  public OperationRow findByRequestId(UUID requestId, String actor) {
    List<OperationRow> found = jdbc.query(
        "select operation_id, request_id, account_count, tenant_ids, created_at "
            + "from batch.console_user_batch_operation where request_id = ? and actor_username = ?",
        (rs, index) -> new OperationRow(
            rs.getObject("operation_id", UUID.class),
            rs.getObject("request_id", UUID.class),
            rs.getInt("account_count"),
            rs.getString("tenant_ids"),
            rs.getTimestamp("created_at").toInstant().toString()),
        requestId,
        actor);
    return EmptyChecks.isEmpty(found) ? null : found.get(0);
  }
}
