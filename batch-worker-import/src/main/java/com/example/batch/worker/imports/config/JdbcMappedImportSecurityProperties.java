package com.example.batch.worker.imports.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.import.jdbc-mapped")
public class JdbcMappedImportSecurityProperties {

  /** jdbc_mapped_import 允许使用的 schema（默认：仅 {@code biz} 业务 schema）。 */
  private List<String> allowedSchemas = new ArrayList<>(List.of("biz"));

  /**
   * A-3.5：严格幂等模式。开启后 {@code jdbc_mapped_import} 模板必须声明 {@code conflictColumns}， 否则 {@code
   * JdbcMappedImportSpec.parse} 抛 IllegalArgumentException 拒绝加载。
   *
   * <p><b>默认 false</b>（兼容模式），配合 {@code GenericJdbcMappedImportLoadPlugin} 输出 {@code
   * idempotency=OFF} WARN 日志让运维扫出"未开幂等"模板；逐个补 conflict_columns 后可在生产 profile 把 {@code
   * strictIdempotency} 翻为 true，阻止后续新模板遗漏。
   *
   * <p>推荐迁移路径：
   *
   * <ol>
   *   <li>先用 C-2.7 b 日志（在 plugin.loadChunk）扫出所有 idempotency=OFF 模板
   *   <li>补 conflictColumns；灰度跑一遍确认不破坏
   *   <li>prod profile 打开本开关，阻止后续遗漏
   * </ol>
   */
  private boolean strictIdempotency = false;
}
