package com.example.batch.worker.processes.sql;

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
  private int queryTimeoutSeconds = 60;

  /** 拒绝 {@code SELECT *} / {@code SELECT table.*}，要求配置显式列清单。 */
  private boolean forbidSelectStar = true;
}
