package io.github.pinpols.batch.worker.processes.sql;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 配置驱动 SQL 加工的安全边界，绑定前缀 {@code batch.worker.process.sql-transform}。 */
@Data
@ConfigurationProperties(prefix = "batch.worker.process.sql-transform")
public class SqlTransformComputeSecurityProperties {

  /** 业务 SQL 与目标表允许访问的 schema。默认仅允许 {@code biz}。 */
  private List<String> allowedSchemas = new ArrayList<>(List.of("biz"));

  /** 查询与写入语句超时时间（秒）。 */
  private int queryTimeoutSeconds = 900;

  /** 拒绝 {@code SELECT *} / {@code SELECT table.*}，要求配置显式列清单。 */
  private boolean forbidSelectStar = true;

  /**
   * 禁止在表达式 / FROM / WHERE 中调用的 PG 函数(大小写不敏感 AST 遍历,按函数家族前缀匹配)。覆盖:连接 dblink(含
   * dblink_exec/dblink_connect 家族) / 系统级 pg_terminate_backend / 文件系统 pg_read_file / 任意命令
   * copy_from_program / 拒绝服务 pg_sleep(含 pg_sleep_for/pg_sleep_until) 等。
   */
  private List<String> forbiddenFunctions =
      new ArrayList<>(
          List.of(
              "dblink",
              "pg_terminate_backend",
              "pg_cancel_backend",
              "pg_read_file",
              "pg_read_server_files",
              "pg_read_binary_file",
              "pg_ls_dir",
              "copy_from_program",
              "lo_import",
              "lo_export",
              "pg_sleep",
              "pg_sleep_for",
              "pg_sleep_until"));

  /**
   * 强制源 SQL 在顶层有 LIMIT 子句,防止无界 ResultSet 拖垮连接池/OOM。
   *
   * <p>默认 false:历史 pipeline 可能未带 LIMIT,开启会破坏既有配置。逐步治理后,生产 切到 true。开启后未带 LIMIT 或超过 {@link
   * #maxLimitRows} 的 SQL 拒绝。
   */
  private boolean requireLimit = false;

  /** {@link #requireLimit} 开启时,顶层 LIMIT 上限。SQL 中超过此值抛 IllegalArgumentException。 */
  private long maxLimitRows = 100_000L;
}
