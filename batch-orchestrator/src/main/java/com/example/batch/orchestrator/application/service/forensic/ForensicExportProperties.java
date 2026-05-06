package com.example.batch.orchestrator.application.service.forensic;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** ADR-022 v0.1 forensic 取证配置。生产建议显式配置 storage-dir。 */
@Component
@ConfigurationProperties(prefix = "batch.forensic")
@Data
public class ForensicExportProperties {

  /** 落盘根目录；默认 ${java.io.tmpdir}/batch-forensic。 */
  private String storageDir = System.getProperty("java.io.tmpdir") + "/batch-forensic";

  /** 单次导出 instance 行数硬上限，防 unbounded（v0.1 默认 100k）。 */
  private int instanceRowCap = 100_000;

  /** v0.1 默认是否启用（false 时 export endpoint 返回 503）。 */
  private boolean enabled = true;
}
