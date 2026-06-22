package com.example.batch.sdk.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * ADR-037 决策一 / 决策二 — <b>JDBC 同事务断点的默认实现示例(SKELETON)</b>。
 *
 * <p><b>这是一份带文档的示例骨架,不是开箱即用的生产实现。</b>它演示 ADR-037「业务数据提交与断点保存必须在同一个事务边界内」的<b>正确做法</b>:断点写与业务写
 * <b>共用同一个 {@link Connection}</b>,{@code save} 内 {@code UPDATE 断点行} 后由<b>同一个 connection 的 {@code
 * commit()}</b> 一次性提交业务数据 + 断点 —— 二者要么都成、要么都回滚。
 *
 * <h2>租户接入步骤</h2>
 *
 * <ol>
 *   <li>建一张控制表(列名随租户,下方 SQL 是参考):
 *       <pre>{@code
 * CREATE TABLE sdk_task_checkpoint (
 *   task_id        VARCHAR(64) PRIMARY KEY,
 *   break_position JSONB        NOT NULL,
 *   succeed_count  BIGINT       NOT NULL,
 *   fail_count     BIGINT       NOT NULL,
 *   completed      BOOLEAN      NOT NULL,
 *   updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
 * );
 * }</pre>
 *   <li>把<b>业务批次写入</b>和 {@link #save} <b>放进同一个 {@link Connection}</b>(关键):
 *       <pre>{@code
 * try (Connection c = dataSource.getConnection()) {
 *   c.setAutoCommit(false);                 // 手动事务边界
 *   businessUpsert(c, batch);               // 1) 业务数据(不单独 commit)
 *   checkpoint.save(c, taskId, state);      // 2) 断点行(不单独 commit)
 *   c.commit();                             // 3) 同一事务一次性提交,原子
 * }
 * }</pre>
 * </ol>
 *
 * <p>本类同时提供两种用法:{@link #save(Connection, String, SdkCheckpointState)}(<b>推荐</b>:传入业务正在用的
 * connection,真正同事务)与 {@link #save(String, SdkCheckpointState)}(便利:自管 connection +
 * commit,<b>仅当业务写也在本方法内、否则破坏同事务约束</b>)。 续跑模板默认通过 {@code SdkTaskContext.commit()} 调到无 connection
 * 的便利重载;真要业务 + 断点同事务的租户应改走 connection 重载, 把业务写也搬进同一 connection —— code review 卡这一点。
 *
 * <p>JSON 列用 {@link ObjectMapper} 序列化 {@code breakPosition};PostgreSQL {@code JSONB} 列用 {@code
 * ?::jsonb} 或 setObject 写入(下方用 setString,依赖隐式转换;严格场景租户自行加 {@code ::jsonb} cast)。
 */
public final class JdbcSdkCheckpoint implements SdkCheckpoint {

  private static final String UPSERT_SQL =
      """
      INSERT INTO sdk_task_checkpoint
          (task_id, break_position, succeed_count, fail_count, completed, updated_at)
      VALUES (?, ?, ?, ?, ?, now())
      ON CONFLICT (task_id) DO UPDATE SET
          break_position = EXCLUDED.break_position,
          succeed_count  = EXCLUDED.succeed_count,
          fail_count     = EXCLUDED.fail_count,
          completed      = EXCLUDED.completed,
          updated_at     = now()
      """;

  private static final String LOAD_SQL =
      "SELECT break_position, succeed_count, fail_count, completed"
          + " FROM sdk_task_checkpoint WHERE task_id = ?";

  private final DataSource dataSource;
  private final ObjectMapper objectMapper;

  public JdbcSdkCheckpoint(DataSource dataSource, ObjectMapper objectMapper) {
    this.dataSource = dataSource;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<SdkCheckpointState> load(String taskId) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(LOAD_SQL)) {
      ps.setString(1, taskId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new SdkCheckpointState(
                readBreakPosition(rs.getString(1)),
                rs.getLong(2),
                rs.getLong(3),
                rs.getBoolean(4)));
      }
    } catch (Exception e) {
      throw new IllegalStateException("checkpoint load failed for taskId=" + taskId, e);
    }
  }

  /**
   * 便利重载:自管 connection + 提交。<b>仅当业务写也在同一调用内时才同事务</b>,否则违反 ADR-037 强约束。续跑模板默认走这条; 真要业务 + 断点同事务请改用
   * {@link #save(Connection, String, SdkCheckpointState)}。
   */
  @Override
  public void save(String taskId, SdkCheckpointState state) {
    try (Connection c = dataSource.getConnection()) {
      boolean prevAutoCommit = c.getAutoCommit();
      c.setAutoCommit(false);
      try {
        save(c, taskId, state);
        c.commit();
      } catch (RuntimeException ex) {
        c.rollback();
        throw ex;
      } finally {
        c.setAutoCommit(prevAutoCommit);
      }
    } catch (Exception e) {
      throw new IllegalStateException("checkpoint save failed for taskId=" + taskId, e);
    }
  }

  /**
   * <b>推荐用法</b>:在业务正在用的 {@link Connection} 上 {@code UPDATE 断点行},<b>不在此 commit</b> —— 由调用方对同一
   * connection {@code commit()},把业务写与断点写合成一个原子事务。这才是 ADR-037 决策二「同事务强约束」的落地点。
   */
  public void save(Connection connection, String taskId, SdkCheckpointState state) {
    try (PreparedStatement ps = connection.prepareStatement(UPSERT_SQL)) {
      ps.setString(1, taskId);
      ps.setString(2, objectMapper.writeValueAsString(state.breakPosition()));
      ps.setLong(3, state.succeedCount());
      ps.setLong(4, state.failCount());
      ps.setBoolean(5, state.completed());
      ps.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException("checkpoint upsert failed for taskId=" + taskId, e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readBreakPosition(String json) throws Exception {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    return objectMapper.readValue(json, Map.class);
  }
}
