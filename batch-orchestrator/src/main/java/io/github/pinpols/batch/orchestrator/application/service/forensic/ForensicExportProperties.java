package io.github.pinpols.batch.orchestrator.application.service.forensic;

import io.github.pinpols.batch.common.utils.PrivateTempFiles;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** ADR-022 v0.1 forensic 取证配置。生产建议显式配置 storage-dir。 */
@Component
@ConfigurationProperties(prefix = "batch.forensic")
@Data
public class ForensicExportProperties {

  /** 落盘根目录；默认 ${java.io.tmpdir}/batch-forensic。 */
  private String storageDir =
      PrivateTempFiles.resolveUnderTempRoot("batch-forensic").toString();

  /** 单次导出 instance 行数硬上限，防 unbounded（v0.1 默认 100k）。 */
  private int instanceRowCap = 100_000;

  /** 单次导出允许覆盖的最大自然日数，避免同步导出占满数据库和磁盘。 */
  private int maxDateRangeDays = 31;

  /** 单次导出的 batch-day 审计行数上限。 */
  private int auditRowCap = 100_000;

  /** 默认启用;显式关闭时 export endpoint 返回 503。 */
  private boolean enabled = true;
}
