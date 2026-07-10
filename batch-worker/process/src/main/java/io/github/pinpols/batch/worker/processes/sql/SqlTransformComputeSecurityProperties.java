package io.github.pinpols.batch.worker.processes.sql;

import io.github.pinpols.batch.common.sql.SelectSqlAstValidator;
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
   * 禁止在表达式 / FROM / WHERE 中调用的 PG 函数(大小写不敏感 AST 遍历,按函数家族前缀匹配)。默认值取自单一权威源 {@link
   * SelectSqlAstValidator#DEFAULT_FORBIDDEN_FUNCTIONS}（与 sensor/DQ、export 侧同源，防止清单漂移）；yml
   * 可在此基础上追加或覆盖。
   */
  private List<String> forbiddenFunctions =
      new ArrayList<>(SelectSqlAstValidator.DEFAULT_FORBIDDEN_FUNCTIONS);

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
